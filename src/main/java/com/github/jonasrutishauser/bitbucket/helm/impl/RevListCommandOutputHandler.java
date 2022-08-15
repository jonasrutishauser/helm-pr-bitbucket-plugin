package com.github.jonasrutishauser.bitbucket.helm.impl;

import java.util.ArrayList;
import java.util.List;

import com.atlassian.bitbucket.scm.CommandOutputHandler;
import com.atlassian.utils.process.LineOutputHandler;

class RevListCommandOutputHandler extends LineOutputHandler implements CommandOutputHandler<String[]> {
    private List<String> revs = new ArrayList<>();

    @Override
    public String[] getOutput() {
        return revs.toArray(String[]::new);
    }

    @Override
    protected void processLine(int lineNum, String line) {
        revs.add(line);
    }
}
