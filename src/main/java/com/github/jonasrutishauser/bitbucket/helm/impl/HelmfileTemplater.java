package com.github.jonasrutishauser.bitbucket.helm.impl;

import static com.atlassian.bitbucket.util.FilePermission.EXECUTE;
import static com.atlassian.bitbucket.util.FilePermission.READ;
import static com.atlassian.bitbucket.util.FilePermission.WRITE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.ExecuteException;

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

@Named
public class HelmfileTemplater extends AbstractTemplater {

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
    protected Iterable<String> additionalConfigurations(Repository repository, Path directory) throws IOException {
        return stream(configuration.getHelmfileEnvironments(repository).split("\n")).map(String::trim)
                .collect(toList());
    }

    @Override
    protected void templateSingleFile(Repository repository, Path directory, GitWorkTree targetWorkTree,
            Path targetFile, Path cacheDir, Optional<String> additionalConfiguration) throws IOException {
        ByteArrayOutputStream stdErr = new ByteArrayOutputStream();
        ByteArrayOutputStream stdOut = new ByteArrayOutputStream();
        try {
            execute(buildHelmfileCommand(directory, additionalConfiguration.orElse("default")),
                    getHelmfileEnvironment(cacheDir), stdOut, stdErr);
        } catch (ExecuteException e) {
            stdOut.reset();
        }
        String result = stdOut.toString(UTF_8);
        if (result.isBlank() && stdErr.size() > 0) {
            targetWorkTree.mkdir(targetFile.getParent().toString());
            targetWorkTree.writeFrom(targetFile.toString(), UTF_8, () -> new StringReader(stdErr.toString(UTF_8)));
            targetWorkTree.builder().add().path(targetFile.toString()).build().call();
        } else {
            targetWorkTree.mkdir(targetFile.getParent().toString());
            targetWorkTree.writeFrom(targetFile.toString(), UTF_8, () -> new StringReader(result));
            targetWorkTree.builder().add().path(targetFile.toString()).build().call();
        }
    }

    @Override
    protected void templateUseOutputDir(Repository repository, Path directory, GitWorkTree targetWorkTree,
            Path targetFolder, Path outputDir, Path cacheDir, Optional<String> additionalConfiguration)
            throws IOException {
        ByteArrayOutputStream stdErr = new ByteArrayOutputStream();
        try {
            execute( //
                    buildHelmfileCommand(directory, additionalConfiguration.orElse("default"), "--output-dir-template",
                            outputDir.toString() + "/{{ .Release.Name}}"), //
                    getHelmfileEnvironment(cacheDir), new ByteArrayOutputStream(), stdErr);
        } catch (ExecuteException e) {
            Path targetFile = targetFolder.resolve("error.txt");
            targetWorkTree.mkdir(targetFile.getParent().toString());
            targetWorkTree.writeFrom(targetFile.toString(), UTF_8, () -> new StringReader(stdErr.toString(UTF_8)));
            targetWorkTree.builder().add().path(targetFile.toString()).build().call();
            return;
        }
        for (Path path : (Iterable<Path>) Files.walk(outputDir).filter(Files::isRegularFile)::iterator) {
            Path relativePath = outputDir.relativize(path);
            String targetPath = targetFolder.resolve(relativePath).toString();
            targetWorkTree.mkdir(targetFolder.resolve(relativePath).getParent().toString());
            targetWorkTree.writeFrom(targetPath, UTF_8, () -> Files.newBufferedReader(path, UTF_8));
            targetWorkTree.builder().add().path(targetPath).build().call();
        }
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
        Path kustomizePath = MoreFiles.resolve(cacheDir, "bin", "kustomize");
        if (!Files.isRegularFile(kustomizePath)) {
            MoreFiles.mkdir(kustomizePath.getParent());
            Files.copy(Paths.get(configuration.getKustomizeBinary()), kustomizePath, REPLACE_EXISTING);
        }
        return ImmutableMap.<String, String>builder() //
                .put("PATH", kustomizePath.getParent().toString() + File.pathSeparator + System.getenv("PATH")) //
                .put("XDG_CACHE_HOME", cacheDir.resolve("helmfile-cache").toString()) //
                .put("HELMFILE_TEMPDIR", cacheDir.resolve("helmfile-temp").toString()) //
                .put("HELM_CACHE_HOME", cacheDir.resolve("helm-cache").toString()) //
                .put("HELM_CONFIG_HOME", cacheDir.resolve("helm-config").toString()) //
                .put("HELM_DATA_HOME", cacheDir.resolve("helm-data").toString()) //
                .build();
    }

    private CommandLine buildHelmfileCommand(Path directory, String environment, String... additionalArgs) {
        CommandLine commandLine = buildCommandLine( //
                configuration.getHelmfileBinary(), //
                "-b", configuration.getHelmBinary(), //
                "-f", directory.toString(), //
                "-e", environment, //
                "-q", //
                "template", //
                "--include-crds");
        commandLine.addArguments(additionalArgs);
        return commandLine;
    }
}
