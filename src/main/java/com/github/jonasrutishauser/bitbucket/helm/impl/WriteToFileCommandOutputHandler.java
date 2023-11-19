package com.github.jonasrutishauser.bitbucket.helm.impl;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.atlassian.bitbucket.scm.CommandOutputHandler;
import com.atlassian.bitbucket.scm.Watchdog;

class WriteToFileCommandOutputHandler implements CommandOutputHandler<Void> {
    private final Path targetFile;

    public WriteToFileCommandOutputHandler(Path targetFile) {
        this.targetFile = targetFile;
    }

    @Override
    public void process(InputStream output) throws IOException {
        Files.copy(output, targetFile, REPLACE_EXISTING);
    }

    @Override
    public void setWatchdog(Watchdog watchdog) {
        // ignore
    }

    @Override
    public Void getOutput() {
        return null;
    }
}
