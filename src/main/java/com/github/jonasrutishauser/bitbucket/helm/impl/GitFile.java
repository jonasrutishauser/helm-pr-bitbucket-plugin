package com.github.jonasrutishauser.bitbucket.helm.impl;

class GitFile {
    private final String objectId;
    private final String filename;

    public GitFile(String objectId, String filename) {
        this.objectId = objectId;
        this.filename = filename;
    }

    public String getObjectId() {
        return objectId;
    }

    public String getFilename() {
        return filename;
    }
}
