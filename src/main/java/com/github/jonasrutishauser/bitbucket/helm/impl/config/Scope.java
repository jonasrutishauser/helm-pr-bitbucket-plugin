package com.github.jonasrutishauser.bitbucket.helm.impl.config;

import com.atlassian.bitbucket.AuthorisationException;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.repository.Repository;

public interface Scope {

    Project getProject();

    Repository getRepository();

    default boolean isProject() {
        return getRepository() == null;
    }

    default boolean isRepository() {
        return getRepository() != null;
    }

    void validatePermission(Permission... permission) throws AuthorisationException;

}
