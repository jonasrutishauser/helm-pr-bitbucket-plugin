package com.github.jonasrutishauser.bitbucket.helm.impl;

import static com.atlassian.bitbucket.content.ChangeType.DELETE;
import static com.atlassian.bitbucket.content.ContentTreeNode.Type.FILE;
import static com.atlassian.bitbucket.content.ContentTreeNode.Type.SUBMODULE;

import java.io.IOException;
import java.util.Iterator;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atlassian.bitbucket.comment.AddCommentRequest;
import com.atlassian.bitbucket.comment.CommentService;
import com.atlassian.bitbucket.content.AbstractChangeCallback;
import com.atlassian.bitbucket.content.Change;
import com.atlassian.bitbucket.content.ContentService;
import com.atlassian.bitbucket.content.NoSuchPathException;
import com.atlassian.bitbucket.event.pull.PullRequestDeclinedEvent;
import com.atlassian.bitbucket.event.pull.PullRequestDeletedEvent;
import com.atlassian.bitbucket.event.pull.PullRequestEvent;
import com.atlassian.bitbucket.event.pull.PullRequestMergedEvent;
import com.atlassian.bitbucket.event.pull.PullRequestOpenedEvent;
import com.atlassian.bitbucket.event.pull.PullRequestRescopedEvent;
import com.atlassian.bitbucket.nav.NavBuilder;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestChangesRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.event.api.EventListener;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

@Named
public class PrEventListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(PrEventListener.class);

    private final ContentService contentService;
    private final PullRequestService prService;
    private final NavBuilder navBuilder;
    private final CommentService commentService;
    private final HelmTemplater helmTemplater;
    private final PluginUser pluginUser;

    @Inject
    public PrEventListener(@ComponentImport ContentService contentService,
            @ComponentImport PullRequestService prService, @ComponentImport NavBuilder navBuilder,
            @ComponentImport CommentService commentService, HelmTemplater helmTemplater, PluginUser pluginUser) {
        this.contentService = contentService;
        this.prService = prService;
        this.navBuilder = navBuilder;
        this.commentService = commentService;
        this.helmTemplater = helmTemplater;
        this.pluginUser = pluginUser;
    }

    @EventListener
    public void onPrCreated(PullRequestOpenedEvent event) {
        updateHelmDiff(event);
    }

    @EventListener
    public void onPrRescoped(PullRequestRescopedEvent event) {
        updateHelmDiff(event);
    }

    @EventListener
    public void onPrDeleted(PullRequestDeletedEvent event) {
        removeHelmDiffReference(event);
    }

    @EventListener
    public void onPrMerged(PullRequestMergedEvent event) {
        removeHelmDiffReference(event);
    }

    @EventListener
    public void onPrDeclined(PullRequestDeclinedEvent event) {
        removeHelmDiffReference(event);
    }

    private void updateHelmDiff(PullRequestEvent event) {
        if (helmTemplater.isActive(event.getPullRequest().getToRef().getRepository())) {
            Set<String> helmCharts = getAffectedHelmCharts(event.getPullRequest());
            if (!helmCharts.isEmpty()) {
                LOGGER.debug("Helm charts detected: {}", helmCharts);
                addHelmDiff(event, helmCharts);
            }
        }
    }

    private void removeHelmDiffReference(PullRequestEvent event) {
        helmTemplater.removeReference(event.getPullRequest());
    }

    private void addHelmDiff(PullRequestEvent event, Set<String> helmCharts) {
        String[] refs = helmTemplater.addTemplatedCommits(event, helmCharts);
        if (refs.length > 1) {
            pluginUser.impersonating("add pr comment")
                    .withPermission(event.getPullRequest().getToRef().getRepository(), Permission.REPO_READ).call(
                            () -> commentService
                                    .addComment(new AddCommentRequest.Builder(event.getPullRequest(),
                                            String.format("Helm Template diff generated ([view changes](%s))",
                                                    getPullRequestDiffUrl(event.getPullRequest(), refs[0], refs[1])))
                                                            .build()));
        }
    }

    private String getPullRequestDiffUrl(PullRequest pullRequest, String commit, String since) {
        return navBuilder.repo(pullRequest.getToRef().getRepository()).pullRequest(pullRequest.getId()).commit(commit)
                .since(since).buildRelative();
    }

    private Set<String> getAffectedHelmCharts(PullRequest pullRequest) {
        Set<String> chartDirs = new TreeSet<>();
        // get all changed directories
        prService.streamChanges(new PullRequestChangesRequest.Builder(pullRequest).withComments(false).build(),
                new AbstractChangeCallback() {
                    @Override
                    public boolean onChange(Change change) throws IOException {
                        if (DELETE != change.getType() && SUBMODULE != change.getNodeType()) {
                            int endIndex = change.getPath().getComponents().length;
                            if (FILE == change.getNodeType()) {
                                endIndex = endIndex - 1;
                            }
                            StringJoiner joiner = new StringJoiner("/");
                            for (int i = 0; i < endIndex; i++) {
                                joiner.add(change.getPath().getComponents()[i]);
                                chartDirs.add(joiner.toString());
                            }
                        }
                        return true;
                    }
                });
        // only keep directories which contain a chart
        for (Iterator<String> iterator = chartDirs.iterator(); iterator.hasNext();) {
            String dir = iterator.next();
            try {
                if (FILE != contentService.getType(pullRequest.getFromRef().getRepository(),
                        pullRequest.getFromRef().getId(), dir + "/Chart.yaml")) {
                    iterator.remove();
                }
            } catch (NoSuchPathException e) {
                iterator.remove();
            }
        }
        return chartDirs;
    }
}
