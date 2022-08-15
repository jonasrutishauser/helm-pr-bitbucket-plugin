package com.github.jonasrutishauser.bitbucket.helm.impl.config;

import java.util.Arrays;

import javax.inject.Inject;
import javax.inject.Named;

import com.atlassian.bitbucket.AuthorisationException;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.permission.PermissionValidationService;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.project.ProjectService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

@Named
public class ScopeService {

    private final ProjectService projectService;
    private final RepositoryService repositoryService;
    private final PermissionValidationService permissionValidationService;

    @Inject
    public ScopeService(@ComponentImport ProjectService projectService,
            @ComponentImport RepositoryService repositoryService,
            @ComponentImport PermissionValidationService permissionValidationService) {
        this.projectService = projectService;
        this.repositoryService = repositoryService;
        this.permissionValidationService = permissionValidationService;
    }

    public Scope fromPathInfo(String pathInfo) {
        String[] parts;
        if (pathInfo.startsWith("/")) {
            parts = pathInfo.substring(1).split("/");
        } else {
            parts = pathInfo.split("/");
        }
        if (parts.length < 3) {
            return new ScopeImpl(projectService.getByKey(parts[0]), permissionValidationService);
        } else {
            return new ScopeImpl(repositoryService.getBySlug(parts[0], parts[2]), permissionValidationService);
        }
    }

    public static Scope scope(Repository repository) {
        return new ScopeImpl(repository, null);
    }

    public static Scope scope(Project project) {
        return new ScopeImpl(project, null);
    }

    private static class ScopeImpl implements Scope {
        private final Project project;
        private final Repository repository;
        private final PermissionValidationService permissionValidationService;

        private ScopeImpl(Project project, PermissionValidationService permissionValidationService) {
            this.project = project;
            this.repository = null;
            this.permissionValidationService = permissionValidationService;
        }

        private ScopeImpl(Repository repository, PermissionValidationService permissionValidationService) {
            this.project = repository.getProject();
            this.repository = repository;
            this.permissionValidationService = permissionValidationService;
        }

        @Override
        public Project getProject() {
            return project;
        }

        @Override
        public Repository getRepository() {
            return repository;
        }

        @Override
        public void validatePermission(Permission... permission) throws AuthorisationException {
            if (permissionValidationService == null) {
                throw new IllegalStateException();
            }
            Permission[] permissions = Arrays.stream(permission)
                    .filter(p -> p.isResource(repository == null ? Project.class : Repository.class))
                    .toArray(Permission[]::new);
            if (permissions.length != 1) {
                throw new IllegalStateException("not exactly one permission defined");
            }
            if (repository == null) {
                permissionValidationService.validateForProject(project, permissions[0]);
            } else {
                permissionValidationService.validateForRepository(repository, permissions[0]);
            }
        }
    }

}
