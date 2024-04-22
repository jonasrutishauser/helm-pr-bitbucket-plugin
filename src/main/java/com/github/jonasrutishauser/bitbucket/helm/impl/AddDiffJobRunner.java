package com.github.jonasrutishauser.bitbucket.helm.impl;

import static org.apache.commons.lang3.StringUtils.capitalize;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atlassian.bitbucket.comment.AddCommentRequest;
import com.atlassian.bitbucket.comment.CommentService;
import com.atlassian.bitbucket.event.pull.PullRequestEvent;
import com.atlassian.bitbucket.nav.NavBuilder;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.scheduler.JobRunner;
import com.atlassian.scheduler.JobRunnerRequest;
import com.atlassian.scheduler.JobRunnerResponse;
import com.atlassian.scheduler.config.JobRunnerKey;

@Named
public class AddDiffJobRunner implements JobRunner {

    public static final JobRunnerKey JOB_RUNNER_KEY = JobRunnerKey.of("com.github.jonasrutishauser.bitbucket:helm-pr-bitbucket-plugin:addDiffJobRunner");

    private static final Logger LOGGER = LoggerFactory.getLogger(AddDiffJobRunner.class);

    private static final String REPOSITORY_ID = "repositoryId";
    private static final String PULL_REQUEST_ID = "pullRequestId";
    private static final String DIRECTORIES = "directories";
    private static final String TOOL = "tool";

    private final PullRequestService prService;
    private final CommentService commentService;
    private final NavBuilder navBuilder;
    private final List<AbstractTemplater> templaters;
    private final PluginUser pluginUser;

    @Inject
    public AddDiffJobRunner(@ComponentImport PullRequestService prService,
            @ComponentImport CommentService commentService, @ComponentImport NavBuilder navBuilder,
            List<AbstractTemplater> templaters, PluginUser pluginUser) {
        this.prService = prService;
        this.commentService = commentService;
        this.navBuilder = navBuilder;
        this.templaters = templaters;
        this.pluginUser = pluginUser;
    }

    public static Map<String, Serializable> getParameters(PullRequestEvent event, Set<String> directories, AbstractTemplater templater) {
        Map<String, Serializable> parameters = new HashMap<>();
        parameters.put(REPOSITORY_ID, event.getPullRequest().getToRef().getRepository().getId());
        parameters.put(PULL_REQUEST_ID, event.getPullRequest().getId());
        parameters.put(DIRECTORIES, new HashSet<>(directories));
        parameters.put(TOOL, templater.toolName());
        return parameters;
    }

    @Override
    public JobRunnerResponse runJob(JobRunnerRequest request) {
        Map<String, Serializable> parameters = request.getJobConfig().getParameters();
        int repositoryId = ((Number) parameters.get(REPOSITORY_ID)).intValue();
        long pullRequestId = ((Number) parameters.get(PULL_REQUEST_ID)).longValue();
        PullRequest pullRequest = pluginUser.impersonating("get pr").withPermission(Permission.REPO_READ)
                .call(() -> prService.getById(repositoryId, pullRequestId));
        if (pullRequest != null) {
            @SuppressWarnings("unchecked")
            Collection<String> directories = (Collection<String>) parameters.get(DIRECTORIES);
            for (AbstractTemplater templater : templaters) {
                if (templater.toolName().equals(parameters.get(TOOL))) {
                    addDiff(pullRequest, directories, templater);
                }
            }
        } else {
            LOGGER.warn("Pull Request {} for project {} no longer exists", pullRequestId, repositoryId);
        }
        return JobRunnerResponse.success();
    }

    private void addDiff(PullRequest pullRequest, Collection<String> directories, AbstractTemplater templater) {
        String[] refs = templater.addTemplatedCommits(pullRequest, directories);
        if (refs != null && refs.length > 0 && prStillExists(pullRequest)) {
            String message;
            if (refs.length > 1) {
                message = String.format("%s template diff generated ([view changes](%s))",
                        capitalize(templater.toolName()), getPullRequestDiffUrl(pullRequest, refs[0], refs[1]));
            } else {
                message = String.format("%s template generated no diff", capitalize(templater.toolName()));
            }
            pluginUser.impersonating("add pr comment")
                    .withPermission(pullRequest.getToRef().getRepository(), Permission.REPO_READ)
                    .call(() -> commentService.addComment(new AddCommentRequest.Builder(pullRequest, message).build()));
        }
    }

    /**
     * There is a bug report, that when a comment is added to a PR after it got deleted, the database will be corrupt.
     * Since it takes a while to template all the stuff this is a risk. To reduce it, we check that the PR still exists
     * just before adding the comment. There is still a race condition, but it is much less likely to happen now.
     *
     * @see <a href="https://jira.atlassian.com/browse/BSERV-12953">Bug Report</a>
     *
     * @param pullRequest the pull request to check
     * @return true if it still exists
     */
    private boolean prStillExists(PullRequest pullRequest) {
        PullRequest pr = pluginUser.impersonating("get pr")
                .withPermission(pullRequest.getToRef().getRepository(), Permission.REPO_READ)
                .call(() -> prService.getById(pullRequest.getToRef().getRepository().getId(), pullRequest.getId()));
        return pr != null;
    }

    private String getPullRequestDiffUrl(PullRequest pullRequest, String commit, String since) {
        return navBuilder.repo(pullRequest.getToRef().getRepository()).pullRequest(pullRequest.getId()).commit(commit)
                .since(since).buildAbsolute();
    }

}
