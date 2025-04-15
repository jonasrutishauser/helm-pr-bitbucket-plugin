package com.github.jonasrutishauser.bitbucket.helm.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

import javax.annotation.Nonnull;

import com.atlassian.bitbucket.scm.CommandOutputHandler;
import com.atlassian.bitbucket.scm.Watchdog;

class LinesCommandOutputHandler implements CommandOutputHandler<String[]> {
    private List<String> lines;

    @Override
    public @Nonnull String[] getOutput() {
        return lines.toArray(new String[lines.size()]);
    }

    @Override
    public void process(@Nonnull InputStream output) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(output, UTF_8))) {
            lines = reader.lines().collect(toList());
        }
    }

    @Override
    public void setWatchdog(@Nonnull Watchdog watchdog) {
        // ignore
    }
}
