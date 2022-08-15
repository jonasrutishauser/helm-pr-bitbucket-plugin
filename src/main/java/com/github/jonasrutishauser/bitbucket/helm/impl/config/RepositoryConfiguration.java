package com.github.jonasrutishauser.bitbucket.helm.impl.config;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.atlassian.bitbucket.AuthorisationException;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.auth.LoginUriProvider;
import com.atlassian.soy.renderer.SoyException;
import com.atlassian.soy.renderer.SoyTemplateRenderer;
import com.google.common.collect.ImmutableMap;

@Named
public class RepositoryConfiguration extends HttpServlet {

    private final LoginUriProvider loginUriProvider;
    private final SoyTemplateRenderer soyTemplateRenderer;
    private final ScopeService scopeService;
    private final HelmConfiguration configuration;

    @Inject
    public RepositoryConfiguration(@ComponentImport LoginUriProvider loginUriProvider,
            @ComponentImport SoyTemplateRenderer soyTemplateRenderer, ScopeService scopeService,
            HelmConfiguration configuration) {
        this.loginUriProvider = loginUriProvider;
        this.soyTemplateRenderer = soyTemplateRenderer;
        this.scopeService = scopeService;
        this.configuration = configuration;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        Scope scope = scopeService.fromPathInfo(request.getPathInfo());
        try {
            scope.validatePermission(Permission.PROJECT_ADMIN, Permission.REPO_ADMIN);
        } catch (AuthorisationException e) {
            redirectToLogin(request, response);
            return;
        }

        if (scope.isRepository()) {
            render(response, "plugin.helmPr.repositoryConfigurationPage", //
                    ImmutableMap.of("repository", scope.getRepository(), //
                            "configuration", configuration.getConfiguration(scope)));
        } else {
            render(response, "plugin.helmPr.projectConfigurationPage", //
                    ImmutableMap.of("project", scope.getProject(), //
                            "configuration", configuration.getConfiguration(scope)));
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        Scope scope = scopeService.fromPathInfo(request.getPathInfo());
        scope.validatePermission(Permission.PROJECT_ADMIN, Permission.REPO_ADMIN);

        configuration.setConfiguration(scope, request.getParameterMap());
        response.sendRedirect(request.getRequestURI());
    }

    private void render(HttpServletResponse response, String templateName, Map<String, Object> data)
            throws IOException, ServletException {
        response.setContentType("text/html;charset=UTF-8");
        try {
            soyTemplateRenderer.render(response.getWriter(),
                    "com.github.jonasrutishauser.bitbucket.helm-pr-bitbucket-plugin:configuration-soy", templateName,
                    data);
        } catch (SoyException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new ServletException(e);
        }
    }

    private void redirectToLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendRedirect(loginUriProvider.getLoginUri(getUri(request)).toASCIIString());
    }

    private URI getUri(HttpServletRequest request) {
        StringBuffer builder = request.getRequestURL();
        if (request.getQueryString() != null) {
            builder.append("?");
            builder.append(request.getQueryString());
        }
        return URI.create(builder.toString());
    }

}
