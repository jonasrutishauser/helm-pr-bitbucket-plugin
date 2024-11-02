package com.github.jonasrutishauser.bitbucket.helm.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scm.git.GitWorkTreeBuilderFactory;
import com.atlassian.bitbucket.scm.git.command.GitCommandBuilderFactory;
import com.atlassian.bitbucket.scm.git.worktree.GitWorkTree;
import com.atlassian.bitbucket.server.StorageService;
import com.atlassian.bitbucket.util.MoreFiles;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.github.jonasrutishauser.bitbucket.helm.impl.config.HelmConfiguration;
import com.ongres.process.FluentProcess;
import com.ongres.process.FluentProcessBuilder;
import com.ongres.process.Output;
import com.ongres.process.ProcessException;
import com.ongres.process.ProcessTimeoutException;

@Named
public class HelmTemplater extends AbstractTemplater {

    private static final Logger LOGGER = LoggerFactory.getLogger(HelmTemplater.class);

    @Inject
    public HelmTemplater(HelmConfiguration configuration,
            @ComponentImport GitWorkTreeBuilderFactory workTreeBuilderFactory,
            @ComponentImport GitCommandBuilderFactory commandBuilderFactory,
            @ComponentImport StorageService storageService) {
        super(configuration, workTreeBuilderFactory, commandBuilderFactory, storageService);
    }

    @Override
    protected String toolName() {
        return "helm";
    }

    @Override
    protected Collection<String> markerFilenames() {
        return singleton("Chart.yaml");
    }

    @Override
    protected Iterable<String> additionalConfigurations(Repository repository, Path chartDir) throws IOException {
        Path testValuesDirectory = chartDir.resolve(configuration.getTestValuesDirectory(repository));
        if (Files.isDirectory(testValuesDirectory)) {
            return Files.list(testValuesDirectory) //
                    .filter(Files::isRegularFile) //
                    .map(f -> f.getFileName()) //
                    .map(Path::toString) //
                    .filter(f -> f.endsWith(".yaml")) //
                    .map(f -> f.substring(0, f.length() - 5))::iterator;
        }
        return emptyList();
    }

    @Override
    protected void templateSingleFile(Repository repository, Path chartDir, GitWorkTree targetWorktree, Path targetFile,
            Path cacheDir, Optional<String> testValueFile) throws IOException {
        StringJoiner error = new StringJoiner(System.lineSeparator());
        try (Stream<String> stdErrStream = FluentProcess
                .builder(configuration.getHelmBinary(), "dependency", "build", chartDir.toString()) //
                .environment(getHelmEnvironment(cacheDir)) //
                .noStdout() //
                .start() //
                .withTimeout(Duration.ofMillis(configuration.getExecutionTimeout())) //
                .streamStderr()) {
            stdErrStream.forEach(error::add);
        } catch (ProcessException e) {
            LOGGER.warn("helm dependency build exited with {}: {}", e.getExitCode(), error.toString());
        }
        Output output = helmProcessBuilder(chartDir, getValues(repository, chartDir, cacheDir, testValueFile)) //
                .environment(getHelmEnvironment(cacheDir)) //
                .start() //
                .withTimeout(Duration.ofMillis(configuration.getExecutionTimeout())) //
                .tryGet();
        output.error().ifPresent(error::add);
        if (output.exception().isPresent() || (!output.output().isPresent() && error.length() > 0)) {
            output.exception().filter(ProcessTimeoutException.class::isInstance)
                    .map(ProcessTimeoutException.class::cast)
                    .ifPresent(exception -> error.add("timeout after " + exception.getTimeout()));
            writeContent(targetWorktree, targetFile, error.toString());
        } else {
            writeContent(targetWorktree, targetFile, output.output().orElse(""));
        }
    }

    @Override
    protected void templateUseOutputDir(Repository repository, Path chartDir, GitWorkTree targetWorktree,
            Path targetFolder, Path outputDir, Path cacheDir, Optional<String> testValueFile) throws IOException {
        StringJoiner stdErr = new StringJoiner(System.lineSeparator());
        try (Stream<String> stdErrStream = FluentProcess
                .builder(configuration.getHelmBinary(), "dependency", "build", chartDir.toString()) //
                .environment(getHelmEnvironment(cacheDir)) //
                .noStdout() //
                .start() //
                .withTimeout(Duration.ofMillis(configuration.getExecutionTimeout())) //
                .streamStderr()) {
            stdErrStream.forEach(stdErr::add);
        } catch (ProcessException e) {
            LOGGER.warn("helm dependency build exited with {}: {}", e.getExitCode(), stdErr.toString());
        }
        try (Stream<String> stdErrStream = helmProcessBuilder(chartDir, getValues(repository, chartDir, cacheDir, testValueFile), "--output-dir",
                outputDir.toString()) //
                        .environment(getHelmEnvironment(cacheDir)) //
                        .noStdout() //
                        .start() //
                        .withTimeout(Duration.ofMillis(configuration.getExecutionTimeout())) //
                        .streamStderr()) {
            stdErrStream.forEach(stdErr::add);
        } catch (ProcessTimeoutException e) {
            writeContent(targetWorktree, targetFolder.resolve("error.txt"), "timeout after " + e.getTimeout());
        } catch (ProcessException e) {
            writeContent(targetWorktree, targetFolder.resolve("error.txt"), stdErr.toString());
        }
        for (Path path : (Iterable<Path>) Files.walk(outputDir).filter(Files::isRegularFile)::iterator) {
            Path relativePath = outputDir.relativize(path);
            relativePath = relativePath.subpath(1, relativePath.getNameCount());
            String targetPath = targetFolder.resolve(relativePath).toString();
            targetWorktree.mkdir(targetFolder.resolve(relativePath).getParent().toString());
            targetWorktree.writeFrom(targetPath, UTF_8, () -> Files.newBufferedReader(path, UTF_8));
            targetWorktree.builder().add().path(targetPath).build().call();
        }
    }

    private void writeContent(GitWorkTree targetWorkTree, Path targetFile, String content) throws IOException {
        targetWorkTree.mkdir(targetFile.getParent().toString());
        targetWorkTree.write(targetFile.toString(), UTF_8, writer -> writer.write(content));
        targetWorkTree.builder().add().path(targetFile.toString()).build().call();
    }

    private List<Path> getValues(Repository repository, Path chartDir, Path cacheDir, Optional<String> testValueFile)
            throws IOException {
        Path defaultValues = cacheDir.resolve("defaults.yaml");
        if (!Files.isRegularFile(defaultValues)) {
            MoreFiles.write(defaultValues, configuration.getDefaultValues(repository));
        }
        return testValueFile.map(
                name -> MoreFiles.resolve(chartDir, configuration.getTestValuesDirectory(repository), name + ".yaml")) //
                .map(testValues -> asList(defaultValues, testValues)) //
                .orElseGet(() -> asList(defaultValues));
    }

    private Map<String, String> getHelmEnvironment(Path cacheDir) {
        return Map.of("HELM_CACHE_HOME", cacheDir.resolve("helm-cache").toString(), //
                "HELM_CONFIG_HOME", cacheDir.resolve("helm-config").toString(), //
                "HELM_DATA_HOME", cacheDir.resolve("helm-data").toString());
    }
    
    private FluentProcessBuilder helmProcessBuilder(Path chartDir, List<Path> values, String... additionalArgs) {
        FluentProcessBuilder processBuilder = FluentProcess.builder( //
                configuration.getHelmBinary(), //
                "template", //
                "release-name", //
                chartDir.toString(), //
                "--include-crds");
        for (Path file : values) {
            processBuilder = processBuilder //
                    .arg("--values") //
                    .arg(file.toString());
        }
        for (String additionalArg : additionalArgs) {
            processBuilder = processBuilder.arg(additionalArg);
        }
        return processBuilder.dontCloseAfterLast();
    }
}
