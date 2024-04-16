package com.github.jonasrutishauser.bitbucket.helm.impl;

import static com.atlassian.bitbucket.util.FilePermission.EXECUTE;
import static com.atlassian.bitbucket.util.FilePermission.READ;
import static com.atlassian.bitbucket.util.FilePermission.WRITE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;

import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scm.git.GitWorkTreeBuilderFactory;
import com.atlassian.bitbucket.scm.git.command.GitCommandBuilderFactory;
import com.atlassian.bitbucket.scm.git.worktree.GitWorkTree;
import com.atlassian.bitbucket.server.StorageService;
import com.atlassian.bitbucket.util.MoreFiles;
import com.atlassian.bitbucket.util.SetFilePermissionRequest;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.github.jonasrutishauser.bitbucket.helm.impl.config.HelmConfiguration;
import com.google.common.collect.ImmutableMap;
import com.ongres.process.FluentProcess;
import com.ongres.process.FluentProcessBuilder;
import com.ongres.process.Output;
import com.ongres.process.ProcessException;
import com.ongres.process.ProcessTimeoutException;

@Named
public class HelmfileTemplater extends AbstractTemplater {

    private static final Collection<String> MARKER_FILENAMES = asList("helmfile.yaml", "helmfile.yaml.gotmpl");

    @Inject
    public HelmfileTemplater(HelmConfiguration configuration,
            @ComponentImport GitWorkTreeBuilderFactory workTreeBuilderFactory,
            @ComponentImport GitCommandBuilderFactory commandBuilderFactory,
            @ComponentImport StorageService storageService) {
        super(configuration, workTreeBuilderFactory, commandBuilderFactory, storageService);
    }

    @Override
    protected String toolName() {
        return "helmfile";
    }

    @Override
    protected Collection<String> markerFilenames() {
        return MARKER_FILENAMES;
    }

    @Override
    protected Iterable<String> additionalConfigurations(Repository repository, Path directory) throws IOException {
        return stream(configuration.getHelmfileEnvironments(repository).split("\n")).map(String::trim)
                .collect(toList());
    }

    @Override
    protected void templateSingleFile(Repository repository, Path directory, GitWorkTree targetWorkTree,
            Path targetFile, Path cacheDir, Optional<String> additionalConfiguration) throws IOException {
        Output output = helmfileProcessBuilder(directory, additionalConfiguration.orElse("default")) //
                .environment(getHelmfileEnvironment(cacheDir)) //
                .start() //
                .withTimeout(Duration.ofMillis(configuration.getExecutionTimeout())) //
                .tryGet();
        if (output.exception().isPresent() || (!output.output().isPresent() && output.error().isPresent())) {
            StringJoiner content = new StringJoiner(System.lineSeparator());
            output.error().ifPresent(content::add);
            output.exception().filter(ProcessTimeoutException.class::isInstance)
                    .map(ProcessTimeoutException.class::cast)
                    .ifPresent(exception -> content.add("timeout after " + exception.getTimeout()));
            writeContent(targetWorkTree, targetFile, content.toString());
        } else {
            writeContent(targetWorkTree, targetFile, output.output().orElse(""));
        }
    }

    @Override
    protected void templateUseOutputDir(Repository repository, Path directory, GitWorkTree targetWorkTree,
            Path targetFolder, Path outputDir, Path cacheDir, Optional<String> additionalConfiguration)
            throws IOException {
        String stdErr = "";
        try (Stream<String> stdErrStream = helmfileProcessBuilder(directory, additionalConfiguration.orElse("default"), "--output-dir-template",
                    outputDir.toString() + "/{{ .Release.Name }}") //
                            .environment(getHelmfileEnvironment(cacheDir)) //
                            .noStdout() //
                            .start() //
                            .withTimeout(Duration.ofMillis(configuration.getExecutionTimeout())) //
                            .streamStderr()) {
            stdErr = stdErrStream.collect(Collectors.joining(System.lineSeparator()));
        } catch (ProcessTimeoutException e) {
            writeContent(targetWorkTree, targetFolder.resolve("error.txt"), "timeout after " + e.getTimeout());
        } catch (ProcessException e) {
            writeContent(targetWorkTree, targetFolder.resolve("error.txt"), stdErr);
        }
        for (Path path : (Iterable<Path>) Files.walk(outputDir).filter(Files::isRegularFile)::iterator) {
            Path relativePath = outputDir.relativize(path);
            String targetPath = targetFolder.resolve(relativePath).toString();
            targetWorkTree.mkdir(targetFolder.resolve(relativePath).getParent().toString());
            targetWorkTree.writeFrom(targetPath, UTF_8, () -> Files.newBufferedReader(path, UTF_8));
            targetWorkTree.builder().add().path(targetPath).build().call();
        }
    }

    private void writeContent(GitWorkTree targetWorkTree, Path targetFile, String content) throws IOException {
        targetWorkTree.mkdir(targetFile.getParent().toString());
        targetWorkTree.write(targetFile.toString(), UTF_8, writer -> writer.write(content));
        targetWorkTree.builder().add().path(targetFile.toString()).build().call();
    }

    private Map<String, String> getHelmfileEnvironment(Path cacheDir) throws IOException {
        Path secretsPluginDir = MoreFiles.resolve(cacheDir, "helm-data", "plugins", "secrets");
        if (!Files.isDirectory(secretsPluginDir)) {
            MoreFiles.mkdir(secretsPluginDir);
            try (InputStream pluginYaml = getClass().getResourceAsStream("/simple-helm-secrets/plugin.yaml")) {
                Files.copy(pluginYaml, secretsPluginDir.resolve("plugin.yaml"), REPLACE_EXISTING);
            }
            try (InputStream script = getClass().getResourceAsStream("/simple-helm-secrets/secrets.sh")) {
                Files.copy(script, secretsPluginDir.resolve("secrets.sh"), REPLACE_EXISTING);
                MoreFiles.setPermissions(new SetFilePermissionRequest.Builder(secretsPluginDir.resolve("secrets.sh"))
                        .ownerPermission(EXECUTE).ownerPermission(READ).ownerPermission(WRITE).build());
            }
        }
        return ImmutableMap.<String, String>builder() //
                .put("XDG_CACHE_HOME", cacheDir.resolve("helmfile-cache").toString()) //
                .put("HELMFILE_TEMPDIR", cacheDir.resolve("helmfile-temp").toString()) //
                .put("HELM_CACHE_HOME", cacheDir.resolve("helm-cache").toString()) //
                .put("HELM_CONFIG_HOME", cacheDir.resolve("helm-config").toString()) //
                .put("HELM_DATA_HOME", cacheDir.resolve("helm-data").toString()) //
                .build();
    }

    private FluentProcessBuilder helmfileProcessBuilder(Path directory, String environment, String... additionalArgs) {
        FluentProcessBuilder processBuilder = FluentProcess.builder( //
                configuration.getHelmfileBinary(), //
                "-b", configuration.getHelmBinary(), //
                "-k", configuration.getKustomizeBinary(), //
                "-f", directory.toString(), //
                "-e", environment, //
                "-q", //
                "template", //
                "--include-crds");
        for (String additionalArg : additionalArgs) {
            processBuilder = processBuilder.arg(additionalArg);
        }
        return processBuilder.dontCloseAfterLast();
    }
}
