package com.github.jonasrutishauser.bitbucket.helm.impl;

import javax.inject.Inject;
import javax.inject.Named;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.lifecycle.LifecycleAware;
import com.atlassian.scheduler.SchedulerService;

@Named
@ExportAsService
public class AddDiffJobRunnerRegistrator implements LifecycleAware {

    private final SchedulerService schedulerService;
    private final AddDiffJobRunner runner;

    @Inject
    public AddDiffJobRunnerRegistrator(@ComponentImport SchedulerService schedulerService, AddDiffJobRunner runner) {
        this.schedulerService = schedulerService;
        this.runner = runner;
    }

    @Override
    public void onStart() {
        schedulerService.registerJobRunner(AddDiffJobRunner.JOB_RUNNER_KEY, runner);
    }

    @Override
    public void onStop() {
        schedulerService.unregisterJobRunner(AddDiffJobRunner.JOB_RUNNER_KEY);
    }
}
