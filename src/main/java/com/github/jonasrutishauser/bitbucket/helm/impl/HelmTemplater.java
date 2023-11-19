package com.github.jonasrutishauser.bitbucket.helm.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.ExecuteException;
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
import com.google.common.collect.ImmutableMap;

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
    protected String markerFilename() {
        return "Chart.yaml";
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
        ByteArrayOutputStream stdErr = new ByteArrayOutputStream();
        try {
            execute(buildCommandLine(configuration.getHelmBinary(), "dependency", "build", chartDir.toString()),
                    getHelmEnvironment(cacheDir), new ByteArrayOutputStream(), stdErr);
        } catch (ExecuteException e) {
            LOGGER.warn("helm dependency build exited with {}: {}", e.getExitValue(), stdErr.toString());
        }
        ByteArrayOutputStream stdOut = new ByteArrayOutputStream();
        try {
            execute(buildHelmCommand(chartDir, getValues(repository, chartDir, cacheDir, testValueFile)),
                    getHelmEnvironment(cacheDir), stdOut, stdErr);
        } catch (ExecuteException e) {
            stdOut.reset();
        }
        String result = stdOut.toString(UTF_8.name());
        if (result.isEmpty() && stdErr.size() > 0) {
            targetWorktree.mkdir(targetFile.getParent().toString());
            targetWorktree.writeFrom(targetFile.toString(), UTF_8, () -> new StringReader(stdErr.toString(UTF_8.name())));
            targetWorktree.builder().add().path(targetFile.toString()).build().call();
        } else {
            targetWorktree.mkdir(targetFile.getParent().toString());
            targetWorktree.writeFrom(targetFile.toString(), UTF_8, () -> new StringReader(result));
            targetWorktree.builder().add().path(targetFile.toString()).build().call();
        }
    }

    @Override
    protected void templateUseOutputDir(Repository repository, Path chartDir, GitWorkTree targetWorktree,
            Path targetFolder, Path outputDir, Path cacheDir, Optional<String> testValueFile) throws IOException {
        ByteArrayOutputStream stdErr = new ByteArrayOutputStream();
        try {
            execute(buildCommandLine(configuration.getHelmBinary(), "dependency", "build", chartDir.toString()),
                    getHelmEnvironment(cacheDir), new ByteArrayOutputStream(), stdErr);
        } catch (ExecuteException e) {
            LOGGER.warn("helm dependency build exited with {}: {}", e.getExitValue(), stdErr.toString());
        }
        try {
            execute( //
                    buildHelmCommand(chartDir, getValues(repository, chartDir, cacheDir, testValueFile), "--output-dir",
                            outputDir.toString()), //
                    getHelmEnvironment(cacheDir), new ByteArrayOutputStream(), stdErr);
        } catch (ExecuteException e) {
            Path targetFile = targetFolder.resolve("error.txt");
            targetWorktree.mkdir(targetFile.getParent().toString());
            targetWorktree.writeFrom(targetFile.toString(), UTF_8, () -> new StringReader(stdErr.toString(UTF_8.name())));
            targetWorktree.builder().add().path(targetFile.toString()).build().call();
            return;
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
        return ImmutableMap.of("HELM_CACHE_HOME", cacheDir.resolve("helm-cache").toString(), //
                "HELM_CONFIG_HOME", cacheDir.resolve("helm-config").toString(), //
                "HELM_DATA_HOME", cacheDir.resolve("helm-data").toString());
    }

    private CommandLine buildHelmCommand(Path chartDir, List<Path> values, String... additionalArgs) {
        CommandLine commandLine = buildCommandLine(configuration.getHelmBinary(), "template", "release-name", chartDir.toString(), "--include-crds");
        for (Path file : values) {
            commandLine.addArgument("--values");
            commandLine.addArgument(file.toString());
        }
        commandLine.addArguments(additionalArgs);
        return commandLine;
    }
}
