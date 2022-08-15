package com.github.jonasrutishauser.bitbucket.helm.impl.config;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

import com.atlassian.bitbucket.AuthorisationException;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.permission.PermissionValidationService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.auth.LoginUriProvider;
import com.atlassian.soy.renderer.SoyException;
import com.atlassian.soy.renderer.SoyTemplateRenderer;
import com.google.common.collect.ImmutableMap;

@Named
public class GlobalConfiguration extends HttpServlet {

    private final PermissionValidationService permissionValidationService;
    private final LoginUriProvider loginUriProvider;
    private final SoyTemplateRenderer soyTemplateRenderer;
    private final HelmConfiguration configuration;

    @Inject
    public GlobalConfiguration(@ComponentImport PermissionValidationService permissionValidationService,
            @ComponentImport LoginUriProvider loginUriProvider,
            @ComponentImport SoyTemplateRenderer soyTemplateRenderer, HelmConfiguration configuration) {
        this.permissionValidationService = permissionValidationService;
        this.loginUriProvider = loginUriProvider;
        this.soyTemplateRenderer = soyTemplateRenderer;
        this.configuration = configuration;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            permissionValidationService.validateForGlobal(Permission.ADMIN);
        } catch (AuthorisationException e) {
            redirectToLogin(request, response);
            return;
        }

        render(response, "plugin.helmPr.globalConfigurationPage", ImmutableMap.of("configuration", configuration.getGlobalConfiguration()));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        permissionValidationService.validateForGlobal(Permission.ADMIN);
        
        ServletFileUpload upload = new ServletFileUpload(new DiskFileItemFactory());
        Map<String, List<FileItem>> parameters;
        try {
            parameters = upload.parseParameterMap(request);
        } catch (FileUploadException e) {
            throw new ServletException(e);
        }
        configuration.setHelmBinaryType(HelmBinaryType.valueOf(parameters.get("helm-binary").get(0).getString().toUpperCase()));
        configuration.setDefaultValues(parameters.get("default-values").get(0).getString());
        configuration.setTestValuesDirectory(parameters.get("test-values-directory").get(0).getString());
        configuration.setTemplateMode(HelmTemplateMode.valueOf(parameters.get("template-mode").get(0).getString().toUpperCase()));
        if (parameters.get("binary-upload") != null && parameters.get("binary-upload").get(0).getSize() > 0) {
            configuration.uploadBinary(parameters.get("binary-upload").get(0).getInputStream());
        }
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
