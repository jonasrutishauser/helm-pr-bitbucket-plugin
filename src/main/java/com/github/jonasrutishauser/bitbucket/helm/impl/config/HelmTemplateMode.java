package com.github.jonasrutishauser.bitbucket.helm.impl.config;

public enum HelmTemplateMode {

    SINGLE_FILE(true, false), SEPARATE_FILES(false, true), BOTH(true, true);

    private boolean singleFile;
    private boolean useOutputDir;

    private HelmTemplateMode(boolean singleFile, boolean useOutputDir) {
        this.singleFile = singleFile;
        this.useOutputDir = useOutputDir;
    }

    public boolean isSingleFile() {
        return singleFile;
    }

    public boolean isUseOutputDir() {
        return useOutputDir;
    }

}
