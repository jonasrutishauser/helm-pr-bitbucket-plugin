package com.github.jonasrutishauser.bitbucket.helm.impl.config;

import java.io.IOException;
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
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.websudo.WebSudoManager;
import com.atlassian.sal.api.websudo.WebSudoSessionException;
import com.atlassian.soy.renderer.SoyException;
import com.atlassian.soy.renderer.SoyTemplateRenderer;

@Named
public class GlobalConfiguration extends HttpServlet {

    private final WebSudoManager webSudoManager;
    private final SoyTemplateRenderer soyTemplateRenderer;
    private final HelmConfiguration configuration;

    @Inject
    public GlobalConfiguration(@ComponentImport WebSudoManager webSudoManager,
            @ComponentImport SoyTemplateRenderer soyTemplateRenderer, HelmConfiguration configuration) {
        this.webSudoManager = webSudoManager;
        this.soyTemplateRenderer = soyTemplateRenderer;
        this.configuration = configuration;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            webSudoManager.willExecuteWebSudoRequest(request);

            render(response, "plugin.helmPr.globalConfigurationPage",
                    Map.of("configuration", configuration.getGlobalConfiguration()));
        } catch (AuthorisationException e) {
            webSudoManager.enforceWebSudoProtection(request, response);
            return;
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        try {
            webSudoManager.willExecuteWebSudoRequest(request);

            ServletFileUpload upload = new ServletFileUpload(new DiskFileItemFactory());
            Map<String, List<FileItem>> parameters;
            try {
                parameters = upload.parseParameterMap(request);
            } catch (FileUploadException e) {
                throw new ServletException(e);
            }
            configuration.setBinaryType("helm", BinaryType.valueOf(parameters.get("helm-binary").get(0).getString().toUpperCase()));
            configuration.setBinaryType("helmfile", BinaryType.valueOf(parameters.get("helmfile-binary").get(0).getString().toUpperCase()));
            configuration.setBinaryType("kustomize", BinaryType.valueOf(parameters.get("kustomize-binary").get(0).getString().toUpperCase()));
            configuration.setDefaultValues(parameters.get("default-values").get(0).getString());
            configuration.setTestValuesDirectory(parameters.get("test-values-directory").get(0).getString());
            configuration.setTemplateMode(HelmTemplateMode.valueOf(parameters.get("template-mode").get(0).getString().toUpperCase()));
            configuration.setHelmfileEnvironments(parameters.get("helmfile-environments").get(0).getString());
            if (parameters.get("binary-upload") != null && parameters.get("binary-upload").get(0).getSize() > 0) {
                configuration.uploadBinary("helm", parameters.get("binary-upload").get(0).getInputStream());
            }
            if (parameters.get("helmfile-binary-upload") != null && parameters.get("helmfile-binary-upload").get(0).getSize() > 0) {
                configuration.uploadBinary("helmfile", parameters.get("helmfile-binary-upload").get(0).getInputStream());
            }
            if (parameters.get("kustomize-binary-upload") != null && parameters.get("kustomize-binary-upload").get(0).getSize() > 0) {
                configuration.uploadBinary("kustomize", parameters.get("kustomize-binary-upload").get(0).getInputStream());
            }
            response.sendRedirect(request.getRequestURI());
        } catch (WebSudoSessionException e) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
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
}
