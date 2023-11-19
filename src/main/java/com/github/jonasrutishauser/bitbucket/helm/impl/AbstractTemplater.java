package com.github.jonasrutishauser.bitbucket.helm.impl;

import static java.util.Arrays.stream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atlassian.bitbucket.event.pull.PullRequestEvent;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestRef;
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
import com.github.jonasrutishauser.bitbucket.helm.impl.config.HelmConfiguration;
import com.github.jonasrutishauser.bitbucket.helm.impl.config.HelmTemplateMode;

abstract class AbstractTemplater {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractTemplater.class);

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

    protected final HelmConfiguration configuration;
    private final GitWorkTreeBuilderFactory workTreeBuilderFactory;
    private final GitCommandBuilderFactory commandBuilderFactory;
    private final StorageService storageService;

    protected AbstractTemplater(HelmConfiguration configuration, GitWorkTreeBuilderFactory workTreeBuilderFactory,
            GitCommandBuilderFactory commandBuilderFactory, StorageService storageService) {
        this.configuration = configuration;
        this.workTreeBuilderFactory = workTreeBuilderFactory;
        this.commandBuilderFactory = commandBuilderFactory;
        this.storageService = storageService;
    }

    public boolean isActive(Repository repository) {
        return configuration.isActive(repository);
    }

    public String[] addTemplatedCommits(PullRequestEvent event, Set<String> directoriesToTemplate) {
        GitWorkTreeBuilder builder = workTreeBuilderFactory.builder(event.getPullRequest().getToRef().getRepository())
                .commit(null);
        try {
            return builder.execute(workTree -> addTemplated(event, directoriesToTemplate, workTree));
        } catch (IOException e) {
            LOGGER.warn("Failed to add " + toolName() + " templated files", e);
        }
        return null;
    }

    public void removeReference(PullRequest pullRequest) {
        GitScmCommandBuilder scmCommandBuilder = commandBuilderFactory.builder(pullRequest.getToRef().getRepository());
        scmCommandBuilder.updateRef().delete(getRefName(pullRequest)).build().call();
    }

    protected abstract String toolName();

    private String[] addTemplated(PullRequestEvent event, Set<String> directoriesToTemplate, GitWorkTree workTree)
            throws IOException {
        // template old version
        template(event, directoriesToTemplate, workTree, event.getPullRequest().getToRef());

        // template new version
        workTree.builder().rm().path(".").recursive(true).build().call();
        template(event, directoriesToTemplate, workTree, event.getPullRequest().getFromRef());

        String refName = getRefName(event.getPullRequest());
        // this api wont allow to create arbitrary refs
        // (we need to rename this ref afterwards)
        workTree.publish(new PublishGitWorkTreeParameters.Builder(NO_HOOKS).branch(refName, null).build());
        // rename the ref
        GitScmCommandBuilder scmCommandBuilder = commandBuilderFactory
                .builder(event.getPullRequest().getToRef().getRepository());
        scmCommandBuilder.updateRef().set(refName, "refs/heads/" + refName).deref(false).build().call();
        scmCommandBuilder.updateRef().delete("refs/heads/" + refName).build().call();

        return workTree.builder().revList().limit(2).rev("HEAD").build(new RevListCommandOutputHandler()).call();
    }

    private void template(PullRequestEvent event, Set<String> directoriesToTemplate, GitWorkTree targetWorkTree,
            PullRequestRef ref) throws IOException {
        for (String directory : directoriesToTemplate) {
            Path contentDir = Files.createTempDirectory(storageService.getTempDir(), "content-");
            try {
                List<GitFile> files = targetWorkTree.builder().lsTree().tree(ref.getLatestCommit()).path(directory)
                        .recursive(true).build(new LsTreeCommandOutputHandler()).call();
                for (GitFile file : files) {
                    Path targetFile = contentDir.resolve(file.getFilename().substring(directory.length() + 1));
                    MoreFiles.mkdir(targetFile.getParent());
                    targetWorkTree.builder().catFile().pretty().object(file.getObjectId())
                            .build(new WriteToFileCommandOutputHandler(targetFile)).call();
                }
                template(event.getPullRequest().getToRef().getRepository(), contentDir, targetWorkTree, directory);
            } finally {
                MoreFiles.deleteQuietly(contentDir);
            }
        }
        targetWorkTree.builder().commit().author(event.getUser()).message(toolName() + " template").build().call();
    }

    private String getRefName(PullRequest pullRequest) {
        return "refs/pull-requests/" + pullRequest.getId() + "/" + toolName();
    }

    private void template(Repository repository, Path directory, GitWorkTree targetWorktree, String targetFolder)
            throws IOException {
        HelmTemplateMode templateMode = configuration.getTemplateMode(repository);
        Path outputDir = Files.createTempDirectory(storageService.getTempDir(), "rendered-");
        Path cacheDir = Files.createTempDirectory(storageService.getTempDir(), "cache-");
        MoreFiles.mkdir(cacheDir, "repo");
        try {
            if (templateMode.isUseOutputDir()) {
                templateUseOutputDir(repository, directory, targetWorktree, Paths.get(targetFolder, "default"),
                        outputDir, cacheDir, Optional.empty());
                MoreFiles.deleteQuietly(outputDir);
                MoreFiles.mkdir(outputDir);
            }
            if (templateMode.isSingleFile()) {
                templateSingleFile(repository, directory, targetWorktree, Paths.get(targetFolder, "default.yaml"),
                        cacheDir, Optional.empty());
            }
            for (String additionalConfiguration : additionalConfigurations(repository, directory)) {
                if (templateMode.isUseOutputDir()) {
                    templateUseOutputDir(repository, directory, targetWorktree,
                            Paths.get(targetFolder, additionalConfiguration), outputDir, cacheDir,
                            Optional.of(additionalConfiguration));
                    MoreFiles.deleteQuietly(outputDir);
                    MoreFiles.mkdir(outputDir);
                }
                if (templateMode.isSingleFile()) {
                    templateSingleFile(repository, directory, targetWorktree,
                            Paths.get(targetFolder, additionalConfiguration + ".yaml"), cacheDir,
                            Optional.of(additionalConfiguration));
                }
            }
        } finally {
            MoreFiles.deleteQuietly(cacheDir);
            MoreFiles.deleteQuietly(outputDir);
        }
    }

    protected CommandLine buildCommandLine(String executable, String... arguments) {
        CommandLine commandLine = new CommandLine(executable);
        stream(arguments).forEach(commandLine::addArgument);
        return commandLine;
    }

    protected void execute(CommandLine command, Map<String, String> environment, OutputStream stdOut, OutputStream stdErr)
            throws ExecuteException, IOException {
        DefaultExecutor executor = new DefaultExecutor();
        executor.setWatchdog(new ExecuteWatchdog(30_000));
        executor.setStreamHandler(new PumpStreamHandler(stdOut, stdErr));
        executor.execute(command, environment);
    }

    protected abstract Iterable<String> additionalConfigurations(Repository repository, Path directory) throws IOException;

    protected abstract void templateSingleFile(Repository repository, Path directory, GitWorkTree targetWorkTree,
            Path targetFile, Path cacheDir, Optional<String> additionalConfiguration) throws IOException;

    protected abstract void templateUseOutputDir(Repository repository, Path directory, GitWorkTree targetWorkTree,
            Path targetFolder, Path outputDir, Path cacheDir, Optional<String> additionalConfiguration)
            throws IOException;

}
