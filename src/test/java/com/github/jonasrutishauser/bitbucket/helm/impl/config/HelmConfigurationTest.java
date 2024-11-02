package com.github.jonasrutishauser.bitbucket.helm.impl.config;

import static com.github.jonasrutishauser.bitbucket.helm.impl.config.ScopeService.scope;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.atlassian.bitbucket.event.project.ProjectDeletedEvent;
import com.atlassian.bitbucket.event.repository.RepositoryDeletedEvent;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.server.StorageService;
import com.atlassian.bitbucket.util.MoreFiles;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;

class HelmConfigurationTest {

    private final PluginSettings settings = new MapPluginSettings();
    private final StorageService storageService = mock(StorageService.class);

    private HelmConfiguration testee;

    @BeforeEach
    void createTestee() {
        PluginSettingsFactory settingsFactory = mock(PluginSettingsFactory.class);
        when(settingsFactory.createGlobalSettings()).thenReturn(settings);
        when(storageService.getHomeDir()).thenReturn(Paths.get("target", "test-home"));
        when(storageService.getSharedHomeDir()).thenReturn(Paths.get("target", "test-shared-home"));
        testee = new HelmConfiguration(settingsFactory, storageService);
    }

    @AfterEach
    void cleanupHome() {
        MoreFiles.deleteQuietly(storageService.getHomeDir());
        MoreFiles.deleteQuietly(storageService.getSharedHomeDir());
    }

    @Test
    public void isActive_defaultsToTrue() {
        Repository repository = createRepository(13, 42);

        assertTrue(testee.isActive(repository));
    }

    @Test
    public void isActive_globalFalse() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:active", "false");

        assertFalse(testee.isActive(repository));
    }

    @Test
    public void isActive_projectFalse() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:projects:42:active", "false");

        assertFalse(testee.isActive(repository));
    }

    @Test
    public void isActive_repoFalse() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:repos:13:active", "false");

        assertFalse(testee.isActive(repository));
    }

    @Test
    public void isActive_globelFalseButRepoTrue() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:active", "false");
        settings.put("helm-pr-bitbucket-plugin:repos:13:active", "true");

        assertTrue(testee.isActive(repository));
    }

    @Test
    public void isActive_globelFalseButProjectTrue() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:active", "false");
        settings.put("helm-pr-bitbucket-plugin:projects:42:active", "true");

        assertTrue(testee.isActive(repository));
    }

    @Test
    public void getHelmBinary_defaultsToEmbedded() {
        assertEquals("target/test-home/binaries/helm", testee.getHelmBinary());
    }

    @Test
    public void getHelmBinary_whenSystem() {
        settings.put("helm-pr-bitbucket-plugin:helm-binary-type", "SYSTEM");

        assertEquals("helm", testee.getHelmBinary());
    }

    @Test
    public void getHelmBinary_whenUploaded() {
        testee.setBinaryType("helm", BinaryType.UPLOADED);

        assertEquals("target/test-home/binaries/uploaded/helm", testee.getHelmBinary());
    }

    @Test
    public void getDefaultValues_defaultsToEmpty() {
        Repository repository = createRepository(13, 42);

        assertEquals("", testee.getDefaultValues(repository));
    }

    @Test
    public void getDefaultValues_globalSet() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:default-values", "some values");

        assertEquals("some values", testee.getDefaultValues(repository));
    }

    @Test
    public void getDefaultValues_projectSet() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:projects:42:default-values", "foo bar");

        assertEquals("foo bar", testee.getDefaultValues(repository));
    }

    @Test
    public void getDefaultValues_repoSet() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:repos:13:default-values", "repo values");

        assertEquals("repo values", testee.getDefaultValues(repository));
    }

    @Test
    public void getDefaultValues_allScopesSet() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:projects:42:default-values", "foo bar");
        settings.put("helm-pr-bitbucket-plugin:projects:42:default-values", "foo bar");
        settings.put("helm-pr-bitbucket-plugin:repos:13:default-values", "repo values");

        assertEquals("repo values", testee.getDefaultValues(repository));
    }

    @Test
    public void getTestValuesDirectory_defaultsToTestValues() {
        Repository repository = createRepository(13, 42);

        assertEquals("test-values", testee.getTestValuesDirectory(repository));
    }

    @Test
    public void getTestValuesDirectory_globalSet() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:test-values-directory", "some/dir");

        assertEquals("some/dir", testee.getTestValuesDirectory(repository));
    }

    @Test
    public void getTestValuesDirectory_projectSet() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:projects:42:test-values-directory", "foo/bar");

        assertEquals("foo/bar", testee.getTestValuesDirectory(repository));
    }

    @Test
    public void getTestValuesDirectory_repoSet() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:repos:13:test-values-directory", "repo/dir");

        assertEquals("repo/dir", testee.getTestValuesDirectory(repository));
    }

    @Test
    public void getTestValuesDirectory_allScopesSet() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:test-values-directory", "some/dir");
        settings.put("helm-pr-bitbucket-plugin:projects:42:test-values-directory", "foo/bar");
        settings.put("helm-pr-bitbucket-plugin:repos:13:test-values-directory", "repo/dir");

        assertEquals("repo/dir", testee.getTestValuesDirectory(repository));
    }

    @Test
    public void getTemplateMode_defaultsToBoth() {
        Repository repository = createRepository(13, 42);

        assertEquals(HelmTemplateMode.BOTH, testee.getTemplateMode(repository));
    }

    @Test
    public void getTemplateMode_globalSet() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:template-mode", "SINGLE_FILE");

        assertEquals(HelmTemplateMode.SINGLE_FILE, testee.getTemplateMode(repository));
    }

    @Test
    public void getTemplateMode_projectSet() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:projects:42:template-mode", "SEPARATE_FILES");

        assertEquals(HelmTemplateMode.SEPARATE_FILES, testee.getTemplateMode(repository));
    }

    @Test
    public void getTemplateMode_repoSet() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:repos:13:template-mode", "SINGLE_FILE");

        assertEquals(HelmTemplateMode.SINGLE_FILE, testee.getTemplateMode(repository));
    }

    @Test
    public void getTemplateMode_allScopesSet() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:template-mode", "SEPARATE_FILES");
        settings.put("helm-pr-bitbucket-plugin:projects:42:template-mode", "SINGLE_FILE");
        settings.put("helm-pr-bitbucket-plugin:repos:13:template-mode", "BOTH");

        assertEquals(HelmTemplateMode.BOTH, testee.getTemplateMode(repository));
    }

    @Test
    public void getTemplateMode_allScopesSetWithInvalidValue() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:template-mode", "SEPARATE_FILES");
        settings.put("helm-pr-bitbucket-plugin:projects:42:template-mode", "SINGLE_FILE");
        settings.put("helm-pr-bitbucket-plugin:repos:13:template-mode", "SINGLE_FILE1");

        assertEquals(HelmTemplateMode.BOTH, testee.getTemplateMode(repository));
    }

    @Test
    public void onRepositoryDeleted_deletesAllKeys() {
        Repository repository = createRepository(13, 42);
        settings.put("helm-pr-bitbucket-plugin:repos:13:active", "false");
        settings.put("helm-pr-bitbucket-plugin:repos:13:default-values", "values");
        settings.put("helm-pr-bitbucket-plugin:repos:13:test-values-directory", "some-dir");
        settings.put("helm-pr-bitbucket-plugin:repos:13:template-mode", "SINGLE_FILE");

        testee.onRepositoryDeleted(new RepositoryDeletedEvent(this, repository, emptyList()));

        assertNull(settings.get("helm-pr-bitbucket-plugin:repos:13:active"));
        assertNull(settings.get("helm-pr-bitbucket-plugin:repos:13:default-values"));
        assertNull(settings.get("helm-pr-bitbucket-plugin:repos:13:test-values-directory"));
        assertNull(settings.get("helm-pr-bitbucket-plugin:repos:13:template-mode"));
    }

    @Test
    public void onProjectDeleted_deletesAllKeys() {
        Project project = createProject(13);
        settings.put("helm-pr-bitbucket-plugin:projects:13:active", "false");
        settings.put("helm-pr-bitbucket-plugin:projects:13:default-values", "values");
        settings.put("helm-pr-bitbucket-plugin:projects:13:test-values-directory", "some-dir");
        settings.put("helm-pr-bitbucket-plugin:projects:13:template-mode", "SINGLE_FILE");

        testee.onProjectDeleted(new ProjectDeletedEvent(this, project));

        assertNull(settings.get("helm-pr-bitbucket-plugin:projects:13:active"));
        assertNull(settings.get("helm-pr-bitbucket-plugin:projects:13:default-values"));
        assertNull(settings.get("helm-pr-bitbucket-plugin:projects:13:test-values-directory"));
        assertNull(settings.get("helm-pr-bitbucket-plugin:projects:13:template-mode"));
    }

    @Test
    public void getGlobalConfiguration_defaults() {
        Map<String, Object> configuration = testee.getGlobalConfiguration();

        assertEquals(Boolean.TRUE, configuration.get("active"));
        assertEquals(BinaryType.EMBEDDED, configuration.get("helmBinaryType"));
        assertNotNull(configuration.get("systemVersion"));
        assertNotNull(configuration.get("embeddedVersion"));
        assertEquals("", configuration.get("uploadedVersion"));
        assertEquals("", configuration.get("defaultValues"));
        assertEquals("test-values", configuration.get("testValuesDirectory"));
        assertEquals(HelmTemplateMode.BOTH, configuration.get("templateMode"));
    }

    @Test
    public void getProjectConfiguration_defaults() {
        Map<String, Object> configuration = testee.getConfiguration(scope(createProject(42)));

        assertEquals("inherited", configuration.get("active"));
        assertEquals(Boolean.FALSE, configuration.get("overwritten"));
        assertEquals("", configuration.get("defaultValues"));
        assertEquals("test-values", configuration.get("testValuesDirectory"));
        assertEquals(HelmTemplateMode.BOTH, configuration.get("templateMode"));
    }

    @Test
    public void getRepoConfiguration_defaults() {
        Map<String, Object> configuration = testee.getConfiguration(scope(createRepository(13, 42)));

        assertEquals("inherited", configuration.get("active"));
        assertEquals(Boolean.FALSE, configuration.get("overwritten"));
        assertEquals("", configuration.get("defaultValues"));
        assertEquals("test-values", configuration.get("testValuesDirectory"));
        assertEquals(HelmTemplateMode.BOTH, configuration.get("templateMode"));
    }

    @Test
    public void getRepoConfiguration_overwritten() {
        testee.setConfiguration(scope(createRepository(13, 42)), Map.of( //
                "active", new String[] {"false"}, //
                "overwritten", new String[] {"on"}, //
                "default-values", new String[] {"values"}, //
                "test-values-directory", new String[] {"some-dir"}, //
                "template-mode", new String[] {"SINGLE_FILE"}));

        Map<String, Object> configuration = testee.getConfiguration(scope(createRepository(13, 42)));

        assertEquals(Boolean.FALSE, configuration.get("active"));
        assertEquals(Boolean.TRUE, configuration.get("overwritten"));
        assertEquals("values", configuration.get("defaultValues"));
        assertEquals("some-dir", configuration.get("testValuesDirectory"));
        assertEquals(HelmTemplateMode.SINGLE_FILE, configuration.get("templateMode"));
    }

    @Test
    public void uploadBinary() throws IOException {
        testee.uploadBinary("helm", getClass().getResourceAsStream("/binaries/helm"));

        assertEquals(testee.getVersion("helm", BinaryType.EMBEDDED), testee.getVersion("helm", BinaryType.UPLOADED));
    }

    private Repository createRepository(int repoId, int projectId) {
        Repository repository = mock(Repository.class);
        when(repository.getId()).thenReturn(repoId);
        Project project = createProject(projectId);
        when(repository.getProject()).thenReturn(project);
        return repository;
    }

    private Project createProject(int projectId) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(projectId);
        return project;
    }

}
