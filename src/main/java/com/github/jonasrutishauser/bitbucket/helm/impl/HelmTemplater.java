package com.github.jonasrutishauser.bitbucket.helm.impl;

import static com.atlassian.bitbucket.scm.git.worktree.GitCheckoutType.NONE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atlassian.bitbucket.event.pull.PullRequestEvent;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.repository.RefChange;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.scm.git.GitWorkTreeBuilder;
import com.atlassian.bitbucket.scm.git.GitWorkTreeBuilderFactory;
import com.atlassian.bitbucket.scm.git.command.GitCommandBuilderFactory;
import com.atlassian.bitbucket.scm.git.command.GitScmCommandBuilder;
import com.atlassian.bitbucket.scm.git.worktree.GitWorkTree;
import com.atlassian.bitbucket.scm.git.worktree.GitWorkTreeRepositoryHookInvoker;
import com.atlassian.bitbucket.scm.git.worktree.PublishGitWorkTreeParameters;
import com.atlassian.bitbucket.server.StorageService;
import com.atlassian.bitbucket.util.MoreFiles;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.utils.process.ExternalProcess;
import com.atlassian.utils.process.ExternalProcessBuilder;
import com.atlassian.utils.process.StringProcessHandler;
import com.github.jonasrutishauser.bitbucket.helm.impl.config.HelmConfiguration;
import com.github.jonasrutishauser.bitbucket.helm.impl.config.HelmTemplateMode;

@Named
public class HelmTemplater {
    private static final Logger LOGGER = LoggerFactory.getLogger(HelmTemplater.class);

    private static final GitWorkTreeRepositoryHookInvoker NO_HOOKS = new GitWorkTreeRepositoryHookInvoker() {
        @Override
        public boolean preUpdate(GitWorkTree workTree, List<RefChange> refChanges) {
            return true;
        }

        @Override
        public void postUpdate(GitWorkTree workTree, List<RefChange> refChanges) {
            // don't call hooks
        }
    };

    private final HelmConfiguration configuration;
    private final GitWorkTreeBuilderFactory workTreeBuilderFactory;
    private final GitCommandBuilderFactory commandBuilderFactory;
    private final StorageService storageService;

    @Inject
    public HelmTemplater(HelmConfiguration configuration,
            @ComponentImport GitWorkTreeBuilderFactory workTreeBuilderFactory,
            @ComponentImport GitCommandBuilderFactory commandBuilderFactory,
            @ComponentImport StorageService storageService) {
        this.configuration = configuration;
        this.workTreeBuilderFactory = workTreeBuilderFactory;
        this.commandBuilderFactory = commandBuilderFactory;
        this.storageService = storageService;
    }

    public boolean isActive(Repository repository) {
        return configuration.isActive(repository);
    }

    public String[] addTemplatedCommits(PullRequestEvent event, Set<String> helmCharts) {
        GitWorkTreeBuilder builder = workTreeBuilderFactory.builder(event.getPullRequest().getToRef().getRepository())
                .commit(null);
        try {
            return builder.execute(workTree -> addTemplated(event, helmCharts, workTree));
        } catch (IOException e) {
            LOGGER.warn("Failed to add helm templated files", e);
        }
        return null;
    }

    public void removeReference(PullRequest pullRequest) {
        GitScmCommandBuilder scmCommandBuilder = commandBuilderFactory.builder(pullRequest.getToRef().getRepository());
        scmCommandBuilder.updateRef().delete(getRefName(pullRequest)).build().call();
    }

    private String[] addTemplated(PullRequestEvent event, Set<String> helmCharts, GitWorkTree workTree)
            throws IOException {
        // template old version
        workTreeBuilderFactory.builder(event.getPullRequest().getToRef().getRepository())
                .commit(event.getPullRequest().getToRef().getLatestCommit()).checkoutType(NONE)
                .execute(checkoutTree -> {
                    checkoutTree.builder().reset().hard().build().call();
                    Path sourcePath;
                    try {
                        sourcePath = (Path) checkoutTree.getClass().getMethod("getPath").invoke(checkoutTree);
                    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                        throw new IllegalStateException(e);
                    }
                    for (String chart : helmCharts) {
                        template(event.getPullRequest().getToRef().getRepository(), sourcePath.resolve(chart), workTree,
                                chart);
                    }
                    return "";
                });
        workTree.builder().commit().author(event.getUser()).message("Helm template").build()
                .call();

        // template new version
        workTree.builder().rm().path(".").recursive(true).build().call();
        workTreeBuilderFactory.builder(event.getPullRequest().getFromRef().getRepository())
                .commit(event.getPullRequest().getFromRef().getLatestCommit()).checkoutType(NONE)
                .execute(checkoutTree -> {
                    checkoutTree.builder().reset().hard().build().call();
                    Path sourcePath;
                    try {
                        sourcePath = (Path) checkoutTree.getClass().getMethod("getPath").invoke(checkoutTree);
                    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                        throw new IllegalStateException(e);
                    }
                    for (String chart : helmCharts) {
                        template(event.getPullRequest().getToRef().getRepository(), sourcePath.resolve(chart), workTree,
                                chart);
                    }
                    return "";
                });
        workTree.builder().commit().author(event.getUser()).message("Helm template").build().call();

        String refName = getRefName(event.getPullRequest());
        // this wont allow to create arbitrary refs (we need to rename this ref
        // afterwards)
        workTree.publish(new PublishGitWorkTreeParameters.Builder(NO_HOOKS).branch(refName, null).build());
        // rename the ref
        GitScmCommandBuilder scmCommandBuilder = commandBuilderFactory
                .builder(event.getPullRequest().getToRef().getRepository());
        scmCommandBuilder.updateRef().set(refName, "refs/heads/" + refName).deref(false).build().call();
        scmCommandBuilder.updateRef().delete("refs/heads/" + refName).build().call();

        return workTree.builder().revList().limit(2).rev("HEAD").build(new RevListCommandOutputHandler()).call();
    }

    private String getRefName(PullRequest pullRequest) {
        return "refs/pull-requests/" + pullRequest.getId() + "/helm";
    }

    private void template(Repository repository, Path chartDir, GitWorkTree targetWorktree, String targetFolder)
            throws IOException {
        HelmTemplateMode templateMode = configuration.getTemplateMode(repository);
        Path testValuesDirectory = chartDir.resolve(configuration.getTestValuesDirectory(repository));
        Path outputDir = Files.createTempDirectory(storageService.getTempDir(), "rendered-");
        Path cacheDir = Files.createTempDirectory(storageService.getTempDir(), "cache-");
        MoreFiles.mkdir(cacheDir, "repo");
        Path defaultValues = cacheDir.resolve("defaults.yaml");
        MoreFiles.write(defaultValues, configuration.getDefaultValues(repository));
        try {
            if (templateMode.isUseOutputDir()) {
                templateUseOutputDir(chartDir, targetWorktree, targetFolder + "/default", outputDir, cacheDir,
                        asList(defaultValues));
            }
            if (templateMode.isSingleFile()) {
                templateSingleFile(chartDir, targetWorktree, Paths.get(targetFolder, "default.yaml"), cacheDir,
                        asList(defaultValues));
            }
            if (Files.isDirectory(testValuesDirectory)) {
                for (String testValue : (Iterable<String>) Files.list(testValuesDirectory) //
                        .filter(Files::isRegularFile) //
                        .map(f -> f.getFileName()) //
                        .map(Path::toString) //
                        .filter(f -> f.endsWith(".yaml"))::iterator) {
                    if (templateMode.isUseOutputDir()) {
                        templateUseOutputDir(chartDir, targetWorktree,
                                targetFolder + "/" + testValue.replaceAll(".yaml$", ""), outputDir, cacheDir,
                                asList(defaultValues, testValuesDirectory.resolve(testValue)));
                    }
                    if (templateMode.isSingleFile()) {
                        templateSingleFile(chartDir, targetWorktree, Paths.get(targetFolder, testValue), cacheDir,
                                asList(defaultValues, testValuesDirectory.resolve(testValue)));
                    }
                }
            }
        } finally {
            MoreFiles.deleteQuietly(cacheDir);
            MoreFiles.deleteQuietly(outputDir);
        }
    }

    private void templateSingleFile(Path chartDir, GitWorkTree targetWorktree, Path targetFile, Path cacheDir,
            List<Path> values) throws IOException {
        StringProcessHandler handler = new StringProcessHandler();
        ExternalProcess helmTemplateProcess = new ExternalProcessBuilder() //
                .handler(handler) //
                .command(buildHelmCommand(chartDir, cacheDir, values)) //
                .build();
        helmTemplateProcess.execute();
        if (handler.getError() != null && !handler.getError().isEmpty()) {
            targetWorktree.mkdir(targetFile.getParent().toString());
            targetWorktree.writeFrom(targetFile.toString(), UTF_8, () -> new StringReader(handler.getError()));
            targetWorktree.builder().add().path(targetFile.toString()).build().call();
        } else {
            targetWorktree.mkdir(targetFile.getParent().toString());
            targetWorktree.writeFrom(targetFile.toString(), UTF_8, () -> new StringReader(handler.getOutput()));
            targetWorktree.builder().add().path(targetFile.toString()).build().call();
        }
    }

    private void templateUseOutputDir(Path chartDir, GitWorkTree targetWorktree, String targetFolder, Path outputDir,
            Path cacheDir, List<Path> values) throws IOException {
        StringProcessHandler handler = new StringProcessHandler();
        ExternalProcess helmTemplateProcess = new ExternalProcessBuilder() //
                .handler(handler) //
                .command(buildHelmCommand(chartDir, cacheDir, values, "--output-dir", outputDir.toString())) //
                .build();
        helmTemplateProcess.execute();
        if (handler.getError() != null && !handler.getError().isEmpty()) {
            Path targetFile = Paths.get(targetFolder, "error.txt");
            targetWorktree.mkdir(targetFile.getParent().toString());
            targetWorktree.writeFrom(targetFile.toString(), UTF_8, () -> new StringReader(handler.getError()));
            targetWorktree.builder().add().path(targetFile.toString()).build().call();
        }
        for (Path path : (Iterable<Path>) Files.walk(outputDir).filter(Files::isRegularFile)::iterator) {
            Path relativePath = outputDir.relativize(path);
            relativePath = relativePath.subpath(1, relativePath.getNameCount());
            String targetPath = targetFolder + "/" + relativePath.toString();
            targetWorktree.mkdir(targetFolder + "/" + relativePath.getParent().toString());
            targetWorktree.writeFrom(targetPath, UTF_8, () -> Files.newBufferedReader(path, UTF_8));
            targetWorktree.builder().add().path(targetPath).build().call();
        }
    }

    private List<String> buildHelmCommand(Path chartDir, Path cacheDir, List<Path> values, String... additionalArgs) {
        List<String> command = new ArrayList<>(asList( //
                configuration.getHelmBinary(), "template", "release-name", chartDir.toString(), "--dependency-update", //
                "--repository-cache", cacheDir.resolve("repo").toString(), //
                "--include-crds"));
        for (Path file : values) {
            command.add("--values");
            command.add(file.toString());
        }
        command.addAll(asList(additionalArgs));
        return command;
    }
}
