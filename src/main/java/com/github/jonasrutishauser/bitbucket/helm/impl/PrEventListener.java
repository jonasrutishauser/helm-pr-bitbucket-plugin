package com.github.jonasrutishauser.bitbucket.helm.impl;

import static com.atlassian.bitbucket.content.ChangeType.DELETE;
import static com.atlassian.bitbucket.content.ContentTreeNode.Type.FILE;
import static com.atlassian.bitbucket.content.ContentTreeNode.Type.SUBMODULE;

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
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestChangesRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.event.api.EventListener;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.scheduler.SchedulerService;
import com.atlassian.scheduler.SchedulerServiceException;
import com.atlassian.scheduler.config.JobConfig;

@Named
public class PrEventListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(PrEventListener.class);

    private final ContentService contentService;
    private final PullRequestService prService;
    private final SchedulerService scheduler;
    private final List<AbstractTemplater> templaters;

    @Inject
    public PrEventListener(@ComponentImport ContentService contentService,
            @ComponentImport PullRequestService prService, @ComponentImport SchedulerService scheduler,
            List<AbstractTemplater> templaters) {
        this.contentService = contentService;
        this.prService = prService;
        this.scheduler = scheduler;
        this.templaters = templaters;
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
                    try {
                        scheduler.scheduleJobWithGeneratedId(JobConfig.forJobRunnerKey(AddDiffJobRunner.JOB_RUNNER_KEY)
                                .withParameters(AddDiffJobRunner.getParameters(event, directories, templater)));
                    } catch (SchedulerServiceException e) {
                        LOGGER.warn("Failed to schedule diff generation", e);
                    }
                }
            }
        }
    }

    private void removeDiffReference(PullRequestEvent event) {
        for (AbstractTemplater templater : templaters) {
            templater.removeReference(event.getPullRequest());
        }
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
