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

class HelmTemplaterTest {

    @Test
    void templateSingleFileValid(@TempDir Path helmDirectory, @TempDir Path cacheDir) throws IOException {
        HelmTemplater testee = createTestee();
        GitWorkTree targetWorkTree = mock(GitWorkTree.class, RETURNS_DEEP_STUBS);

        Files.write(helmDirectory.resolve("Chart.yaml"), Arrays.asList( //
                "apiVersion: v2", //
                "name: test", //
                "version: 1.0.0"));
        Files.createDirectory(helmDirectory.resolve("templates"));
        Files.write(helmDirectory.resolve("templates").resolve("test.yaml"), Arrays.asList( //
                "apiVersion: test/v2", //
                "name: test"));

        testee.templateSingleFile(null, helmDirectory, targetWorkTree, Paths.get("test", "default.yaml"), cacheDir,
                Optional.empty());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<IoConsumer<Writer>> captor = ArgumentCaptor.forClass(IoConsumer.class);
        verify(targetWorkTree).mkdir("test");
        verify(targetWorkTree).write(eq("test/default.yaml"), eq(UTF_8), captor.capture());
        StringWriter stringWriter = new StringWriter();
        captor.getValue().accept(stringWriter);
        assertThat(stringWriter.toString()).contains( //
                "# Source: test/templates/test.yaml", //
                "apiVersion: test/v2", //
                "name: test");
    }

    @Test
    void templateSingleFileInvalid(@TempDir Path helmDirectory, @TempDir Path cacheDir) throws IOException {
        HelmTemplater testee = createTestee();
        GitWorkTree targetWorkTree = mock(GitWorkTree.class, RETURNS_DEEP_STUBS);

        Files.write(helmDirectory.resolve("Chart.yaml"), Arrays.asList( //
                "apiVersion: v2", //
                "name: test", //
                "version: 1.0.0aaaa"));

        testee.templateSingleFile(null, helmDirectory, targetWorkTree, Paths.get("test", "default.yaml"), cacheDir,
                Optional.empty());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<IoConsumer<Writer>> captor = ArgumentCaptor.forClass(IoConsumer.class);
        verify(targetWorkTree).mkdir("test");
        verify(targetWorkTree).write(eq("test/default.yaml"), eq(UTF_8), captor.capture());
        StringWriter stringWriter = new StringWriter();
        captor.getValue().accept(stringWriter);
        assertThat(stringWriter.toString()).contains("1.0.0aaaa").doesNotContain("# Source: ");
    }

    @Test
    void templateUseOutputDirValid(@TempDir Path helmDirectory, @TempDir Path outputDir, @TempDir Path cacheDir)
            throws IOException {
        HelmTemplater testee = createTestee();
        GitWorkTree targetWorkTree = mock(GitWorkTree.class, RETURNS_DEEP_STUBS);

        Files.write(helmDirectory.resolve("Chart.yaml"), Arrays.asList( //
                "apiVersion: v2", //
                "name: test", //
                "version: 1.0.0"));
        Files.createDirectory(helmDirectory.resolve("templates"));
        Files.write(helmDirectory.resolve("templates").resolve("test.yaml"), Arrays.asList( //
                "apiVersion: test/v2", //
                "name: test"));

        testee.templateUseOutputDir(null, helmDirectory, targetWorkTree, Paths.get("test", "default"), outputDir,
                cacheDir, Optional.empty());

        verify(targetWorkTree, atLeastOnce()).mkdir("test/default/templates");
        verify(targetWorkTree).writeFrom(eq("test/default/templates/test.yaml"), eq(UTF_8), any());
    }

    @Test
    void templateUseOutputDirInvalid(@TempDir Path helmDirectory, @TempDir Path outputDir, @TempDir Path cacheDir)
            throws IOException {
        HelmTemplater testee = createTestee();
        GitWorkTree targetWorkTree = mock(GitWorkTree.class, RETURNS_DEEP_STUBS);

        Files.write(helmDirectory.resolve("Chart.yaml"), Arrays.asList( //
                "apiVersion: v2", //
                "name: test", //
                "version: 1.0.0aaaa"));

        testee.templateUseOutputDir(null, helmDirectory, targetWorkTree, Paths.get("test", "default"), outputDir,
                cacheDir, Optional.empty());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<IoConsumer<Writer>> captor = ArgumentCaptor.forClass(IoConsumer.class);
        verify(targetWorkTree).mkdir("test/default");
        verify(targetWorkTree).write(eq("test/default/error.txt"), eq(UTF_8), captor.capture());
        StringWriter stringWriter = new StringWriter();
        captor.getValue().accept(stringWriter);
        assertThat(stringWriter.toString()).contains("1.0.0aaaa").doesNotContain("# Source: ");
    }

    private HelmTemplater createTestee() {
        HelmConfiguration configuration = mock(HelmConfiguration.class);
        HelmTemplater testee = new HelmTemplater(configuration, null, null, null);
        when(configuration.getHelmBinary()).thenReturn(getClass().getResource("/binaries/helm").getPath());
        when(configuration.getDefaultValues(any())).thenReturn("");
        when(configuration.getExecutionTimeout()).thenReturn(10_000l);
        return testee;
    }
}
