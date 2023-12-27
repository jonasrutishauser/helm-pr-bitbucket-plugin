package com.github.jonasrutishauser.bitbucket.helm.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.atlassian.bitbucket.io.IoConsumer;
import com.atlassian.bitbucket.scm.git.worktree.GitWorkTree;
import com.github.jonasrutishauser.bitbucket.helm.impl.config.HelmConfiguration;

class HelmfileTemplaterTest {

    @Test
    void templateSingleFileValid(@TempDir Path helmfileDirectory, @TempDir Path cacheDir) throws IOException {
        HelmfileTemplater testee = createTestee();
        GitWorkTree targetWorkTree = mock(GitWorkTree.class, RETURNS_DEEP_STUBS);

        Files.write(helmfileDirectory.resolve("helmfile.yaml"), Arrays.asList( //
                "releases:", //
                "- name: test", //
                "  namespace: test-ns", //
                "  chart: oci://registry-1.docker.io/bitnamicharts/nginx", //
                "  version: 15.5.1"));

        testee.templateSingleFile(null, helmfileDirectory, targetWorkTree, Paths.get("test", "default.yaml"), cacheDir,
                Optional.empty());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<IoConsumer<Writer>> captor = ArgumentCaptor.forClass(IoConsumer.class);
        verify(targetWorkTree).mkdir("test");
        verify(targetWorkTree).write(eq("test/default.yaml"), eq(UTF_8), captor.capture());
        StringWriter stringWriter = new StringWriter();
        captor.getValue().accept(stringWriter);
        assertThat(stringWriter.toString()).contains("# Source: ");
    }

    @Test
    void templateSingleFileInvalid(@TempDir Path helmfileDirectory, @TempDir Path cacheDir) throws IOException {
        HelmfileTemplater testee = createTestee();
        GitWorkTree targetWorkTree = mock(GitWorkTree.class, RETURNS_DEEP_STUBS);

        Files.write(helmfileDirectory.resolve("helmfile.yaml"), Arrays.asList( //
                "releases:", //
                "- name: test", //
                "  namespace: test-ns", //
                "  chart: oci://registry-1.docker.io/bitnamicharts/nginx", //
                "  version: 15.5.1"));

        testee.templateSingleFile(null, helmfileDirectory, targetWorkTree, Paths.get("test", "default.yaml"), cacheDir,
                Optional.of("no-such-env"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<IoConsumer<Writer>> captor = ArgumentCaptor.forClass(IoConsumer.class);
        verify(targetWorkTree).mkdir("test");
        verify(targetWorkTree).write(eq("test/default.yaml"), eq(UTF_8), captor.capture());
        StringWriter stringWriter = new StringWriter();
        captor.getValue().accept(stringWriter);
        assertThat(stringWriter.toString()).contains("no-such-env").doesNotContain("# Source: ");
    }

    @Test
    void templateUseOutputDirValid(@TempDir Path helmfileDirectory, @TempDir Path outputDir, @TempDir Path cacheDir) throws IOException {
        HelmfileTemplater testee = createTestee();
        GitWorkTree targetWorkTree = mock(GitWorkTree.class, RETURNS_DEEP_STUBS);

        Files.write(helmfileDirectory.resolve("helmfile.yaml"), Arrays.asList( //
                "releases:", //
                "- name: test", //
                "  namespace: test-ns", //
                "  chart: oci://registry-1.docker.io/bitnamicharts/nginx", //
                "  version: 15.5.1"));

        testee.templateUseOutputDir(null, helmfileDirectory, targetWorkTree, Paths.get("test", "default"), outputDir,
                cacheDir, Optional.empty());

        verify(targetWorkTree, atLeastOnce()).mkdir("test/default/test/nginx/templates");
        verify(targetWorkTree).writeFrom(eq("test/default/test/nginx/templates/svc.yaml"), eq(UTF_8), any());
    }

    @Test
    void templateUseOutputDirInvalid(@TempDir Path helmfileDirectory, @TempDir Path outputDir, @TempDir Path cacheDir)
            throws IOException {
        HelmfileTemplater testee = createTestee();
        GitWorkTree targetWorkTree = mock(GitWorkTree.class, RETURNS_DEEP_STUBS);

        Files.write(helmfileDirectory.resolve("helmfile.yaml"), Arrays.asList( //
                "releases:", //
                "- name: test", //
                "  namespace: test-ns", //
                "  chart: oci://registry-1.docker.io/bitnamicharts/nginx", //
                "  version: 15.5.1"));

        testee.templateUseOutputDir(null, helmfileDirectory, targetWorkTree, Paths.get("test", "default"), outputDir,
                cacheDir, Optional.of("no-such-env"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<IoConsumer<Writer>> captor = ArgumentCaptor.forClass(IoConsumer.class);
        verify(targetWorkTree).mkdir("test/default");
        verify(targetWorkTree).write(eq("test/default/error.txt"), eq(UTF_8), captor.capture());
        StringWriter stringWriter = new StringWriter();
        captor.getValue().accept(stringWriter);
        assertThat(stringWriter.toString()).contains("no-such-env").doesNotContain("# Source: ");
    }

    private HelmfileTemplater createTestee() {
        HelmConfiguration configuration = mock(HelmConfiguration.class);
        HelmfileTemplater testee = new HelmfileTemplater(configuration, null, null, null);
        when(configuration.getHelmfileBinary()).thenReturn(getClass().getResource("/binaries/helmfile").getPath());
        when(configuration.getHelmBinary()).thenReturn(getClass().getResource("/binaries/helm").getPath());
        when(configuration.getKustomizeBinary()).thenReturn(getClass().getResource("/binaries/kustomize").getPath());
        when(configuration.getExecutionTimeout()).thenReturn(10_000l);
        return testee;
    }
}
