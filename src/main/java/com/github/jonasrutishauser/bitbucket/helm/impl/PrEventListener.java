package com.github.jonasrutishauser.bitbucket.helm.impl;

import static com.atlassian.bitbucket.content.ChangeType.DELETE;
import static com.atlassian.bitbucket.content.ContentTreeNode.Type.FILE;
import static com.atlassian.bitbucket.content.ContentTreeNode.Type.SUBMODULE;
import static org.apache.commons.lang3.StringUtils.capitalize;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
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
    private final List<AbstractTemplater> templaters;
    private final PluginUser pluginUser;

    @Inject
    public PrEventListener(@ComponentImport ContentService contentService,
            @ComponentImport PullRequestService prService, @ComponentImport NavBuilder navBuilder,
            @ComponentImport CommentService commentService, List<AbstractTemplater> templaters, PluginUser pluginUser) {
        this.contentService = contentService;
        this.prService = prService;
        this.navBuilder = navBuilder;
        this.commentService = commentService;
        this.templaters = templaters;
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
        removeDiffReference(event);
    }

    @EventListener
    public void onPrMerged(PullRequestMergedEvent event) {
        removeDiffReference(event);
    }

    @EventListener
    public void onPrDeclined(PullRequestDeclinedEvent event) {
        removeDiffReference(event);
    }

    private void updateHelmDiff(PullRequestEvent event) {
        for (AbstractTemplater templater : templaters) {
            if (templater.isActive(event.getPullRequest().getToRef().getRepository())) {
                Set<String> directories = getAffectedDirectories(event.getPullRequest(), templater.markerFilename());
                if (!directories.isEmpty()) {
                    LOGGER.debug("{} directories detected: {}", templater.toolName(), directories);
                    addDiff(event, directories, templater);
                }
            }
        }
    }

    private void removeDiffReference(PullRequestEvent event) {
        for (AbstractTemplater templater : templaters) {
            templater.removeReference(event.getPullRequest());
        }
    }

    private void addDiff(PullRequestEvent event, Set<String> directories, AbstractTemplater templater) {
        String[] refs = templater.addTemplatedCommits(event, directories);
        if (refs != null && refs.length > 1 && prStillExists(event.getPullRequest())) {
            pluginUser.impersonating("add pr comment")
                    .withPermission(event.getPullRequest().getToRef().getRepository(), Permission.REPO_READ)
                    .call(() -> commentService.addComment(new AddCommentRequest.Builder(event.getPullRequest(),
                            String.format("%s template diff generated ([view changes](%s))",
                                    capitalize(templater.toolName()),
                                    getPullRequestDiffUrl(event.getPullRequest(), refs[0], refs[1]))).build()));
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
        PullRequest pr = this.prService.getById(pullRequest.getToRef().getRepository().getId(), pullRequest.getId());
        return pr != null;
    }

    private String getPullRequestDiffUrl(PullRequest pullRequest, String commit, String since) {
        return navBuilder.repo(pullRequest.getToRef().getRepository()).pullRequest(pullRequest.getId()).commit(commit)
                .since(since).buildAbsolute();
    }

    private Set<String> getAffectedDirectories(PullRequest pullRequest, String filenameToSearch) {
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
        // only keep directories which contain filenameToSearch
        for (Iterator<String> iterator = chartDirs.iterator(); iterator.hasNext();) {
            String dir = iterator.next();
            try {
                if (FILE != contentService.getType(pullRequest.getFromRef().getRepository(),
                        pullRequest.getFromRef().getId(), dir + "/" + filenameToSearch)) {
                    iterator.remove();
                }
            } catch (NoSuchPathException e) {
                iterator.remove();
            }
        }
        return chartDirs;
    }
}
