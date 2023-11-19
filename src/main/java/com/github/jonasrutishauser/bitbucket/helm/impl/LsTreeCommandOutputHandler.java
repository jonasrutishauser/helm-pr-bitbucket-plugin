package com.github.jonasrutishauser.bitbucket.helm.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.atlassian.bitbucket.scm.CommandOutputHandler;
import com.atlassian.bitbucket.scm.Watchdog;

public class LsTreeCommandOutputHandler implements CommandOutputHandler<List<GitFile>> {
    private static final Pattern PATTERN = Pattern.compile(".*\\s([0-9a-f]+)\\t(.+)$");

    private List<GitFile> files;

    @Override
    public void process(InputStream output) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(output, UTF_8))) {
            files = reader.lines().map(this::toGitFile).collect(toList());
        }
    }

    @Override
    public List<GitFile> getOutput() {
        return files;
    }

    @Override
    public void setWatchdog(Watchdog watchdog) {
        // ignore
    }

    private GitFile toGitFile(String line) {
        Matcher matcher = PATTERN.matcher(line);
        matcher.find();
        return new GitFile(matcher.group(1), matcher.group(2));
    }
}
