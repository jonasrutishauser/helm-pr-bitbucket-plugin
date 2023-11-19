package com.github.jonasrutishauser.bitbucket.helm.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

import com.atlassian.bitbucket.scm.CommandOutputHandler;
import com.atlassian.bitbucket.scm.Watchdog;

class RevListCommandOutputHandler implements CommandOutputHandler<String[]> {
    private List<String> revs;

    @Override
    public String[] getOutput() {
        return revs.toArray(new String[revs.size()]);
    }

    @Override
    public void process(InputStream output) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(output, UTF_8))) {
            revs = reader.lines().collect(toList());
        }
    }

    @Override
    public void setWatchdog(Watchdog watchdog) {
        // ignore
    }
}
