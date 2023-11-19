package com.github.jonasrutishauser.bitbucket.helm.impl.config;

import static com.atlassian.bitbucket.util.FilePermission.EXECUTE;
import static com.atlassian.bitbucket.util.FilePermission.READ;
import static com.atlassian.bitbucket.util.FilePermission.WRITE;
import static com.github.jonasrutishauser.bitbucket.helm.impl.config.ScopeService.scope;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Collections.emptyMap;
import static java.util.stream.Stream.concat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atlassian.bitbucket.event.project.ProjectDeletedEvent;
import com.atlassian.bitbucket.event.repository.RepositoryDeletedEvent;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.server.StorageService;
import com.atlassian.bitbucket.util.MoreFiles;
import com.atlassian.bitbucket.util.SetFilePermissionRequest;
import com.atlassian.event.api.EventListener;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;

@Named
public class HelmConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(HelmConfiguration.class);

    private static final String KEY_PREFIX = "helm-pr-bitbucket-plugin:";
    private static final String PROJECT_KEY_PREFIX = KEY_PREFIX + "projects:";
    private static final String REPO_KEY_PREFIX = KEY_PREFIX + "repos:";

    private static final String[] CONFIGURATION_KEYS = {"template-mode", "test-values-directory", "default-values", "helmfile-environments"};
    private static final String ACTIVE_KEY = ":active";

    private final PluginSettings settings;
    private final StorageService storageService;

    @Inject
    public HelmConfiguration(@ComponentImport PluginSettingsFactory settingsFactory,
            @ComponentImport StorageService storageService) {
        this.settings = settingsFactory.createGlobalSettings();
        this.storageService = storageService;
    }

    Map<String, Object> getGlobalConfiguration() {
        return ImmutableMap.<String, Object>builder() //
                .put("helmBinaryType", getBinaryType("helm")) //
                .put("systemVersion", getVersion("helm", BinaryType.SYSTEM)) //
                .put("embeddedVersion", getVersion("helm", BinaryType.EMBEDDED)) //
                .put("uploadedVersion", getVersion("helm", BinaryType.UPLOADED)) //
                .put("helmfileBinaryType", getBinaryType("helmfile")) //
                .put("helmfileSystemVersion", getVersion("helmfile", BinaryType.SYSTEM)) //
                .put("helmfileEmbeddedVersion", getVersion("helmfile", BinaryType.EMBEDDED)) //
                .put("helmfileUploadedVersion", getVersion("helmfile", BinaryType.UPLOADED)) //
                .put("kustomizeBinaryType", getBinaryType("kustomize")) //
                .put("kustomizeSystemVersion", getVersion("kustomize", BinaryType.SYSTEM)) //
                .put("kustomizeEmbeddedVersion", getVersion("kustomize", BinaryType.EMBEDDED)) //
                .put("kustomizeUploadedVersion", getVersion("kustomize", BinaryType.UPLOADED)) //
                .putAll(getConfiguration((Scope) null)) //
                .build();
    }

    Map<String, Object> getConfiguration(Scope scope) {
        Builder<String, Object> builder = ImmutableMap.<String, Object>builder() //
                .put("defaultValues", getDefaultValues(scope)) //
                .put("testValuesDirectory", getTestValuesDirectory(scope)) //
                .put("templateMode", getTemplateMode(scope)) //
                .put("helmfileEnvironments", getHelmfileEnvironments(scope)) //
                .put("active", getActive(scope));
        if (scope != null) {
            builder.put("overwritten", Boolean.valueOf(isOverwritten(scope)));
        }
        return builder.build();
    }

    public boolean isActive(Repository repository) {
        return getBooleanValue("active", scope(repository), true);
    }

    private Object getActive(Scope scope) {
        if (scope == null) {
            return Boolean.valueOf(getBooleanValue(ACTIVE_KEY.substring(1), null, true));
        }
        String active;
        if (scope.isProject()) {
            active = (String) settings.get(PROJECT_KEY_PREFIX + scope.getProject().getId() + ACTIVE_KEY);
        } else {
            active = (String) settings.get(REPO_KEY_PREFIX + scope.getRepository().getId() + ACTIVE_KEY);
        }
        return active == null ? "inherited" : Boolean.valueOf(active);
    }

    void setActive(boolean active) {
        settings.put(KEY_PREFIX + "active", Boolean.toString(active));
    }

    private BinaryType getBinaryType(String binary) {
        return getEnumValue(binary + "-binary-type", null, BinaryType.EMBEDDED);
    }

    void setBinaryType(String binary, BinaryType type) {
        settings.put(KEY_PREFIX + binary + "-binary-type", type.name());
    }

    void uploadBinary(String binary, InputStream inputStream) throws IOException {
        Path binaryPath = MoreFiles.resolve(storageService.getSharedHomeDir(), "binaries", "uploaded", binary);
        MoreFiles.mkdir(binaryPath.getParent());
        Files.copy(inputStream, binaryPath, REPLACE_EXISTING);
        MoreFiles.setPermissions(new SetFilePermissionRequest.Builder(binaryPath).ownerPermission(EXECUTE)
                .ownerPermission(READ).ownerPermission(WRITE).build());
        setBinaryType(binary, BinaryType.UPLOADED);
    }

    String getVersion(String binary, BinaryType type) {
        String binaryFile = getBinary(binary, type);
        if (binaryFile == null) {
            return "";
        }
        if ((binaryFile.contains("/") || binaryFile.contains("\\")) && !Files.exists(Path.of(binaryFile))) {
            return "";
        }
        CommandLine commandLine = new CommandLine(binaryFile);
        commandLine.addArgument("version");
        if ("helmfile".equals(binary)) {
            commandLine.addArgument("--output=short");
        } else if ("helm".equals(binary)) {
            commandLine.addArgument("--short");
        }
        DefaultExecutor executor = new DefaultExecutor();
        executor.setWatchdog(new ExecuteWatchdog(10_000));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PumpStreamHandler psh = new PumpStreamHandler(output);
        executor.setStreamHandler(psh);
        try {
            executor.execute(commandLine);
        } catch (ExecuteException e) {
            LOGGER.warn("failed to determine version", e);
        } catch (IOException e) {
            // ignore
        }
        return output.toString();
    }

    public String getHelmBinary() {
        return getBinary("helm", getBinaryType("helm"));
    }

    public String getHelmfileBinary() {
        return getBinary("helmfile", getBinaryType("helmfile"));
    }

    public String getKustomizeBinary() {
        return getBinary("kustomize", getBinaryType("kustomize"));
    }

    void setDefaultValues(String defaultValues) {
        settings.put(KEY_PREFIX + CONFIGURATION_KEYS[2], defaultValues);
    }

    public String getDefaultValues(Repository repository) {
        return getDefaultValues(scope(repository));
    }

    private String getDefaultValues(Scope scope) {
        return getSettingsValue(CONFIGURATION_KEYS[2], scope, "");
    }

    void setTestValuesDirectory(String directory) {
        settings.put(KEY_PREFIX + CONFIGURATION_KEYS[1], directory);
    }

    public String getTestValuesDirectory(Repository repository) {
        return getTestValuesDirectory(scope(repository));
    }

    private String getTestValuesDirectory(Scope scope) {
        return getSettingsValue(CONFIGURATION_KEYS[1], scope, "test-values");
    }

    void setTemplateMode(HelmTemplateMode mode) {
        settings.put(KEY_PREFIX + CONFIGURATION_KEYS[0], mode.name());
    }

    public HelmTemplateMode getTemplateMode(Repository repository) {
        return getTemplateMode(scope(repository));
    }

    private HelmTemplateMode getTemplateMode(Scope scope) {
        return getEnumValue(CONFIGURATION_KEYS[0], scope, HelmTemplateMode.BOTH);
    }

    void setHelmfileEnvironments(String environments) {
        settings.put(KEY_PREFIX + CONFIGURATION_KEYS[3], environments);
    }

    public String getHelmfileEnvironments(Repository repository) {
        return getHelmfileEnvironments(scope(repository));
    }

    private String getHelmfileEnvironments(Scope scope) {
        return getSettingsValue(CONFIGURATION_KEYS[3], scope, "");
    }

    public long getExecutionTimeout() {
        return 600_000;
    }

    void setConfiguration(Scope scope, Map<String, String[]> values) {
        if (scope.isProject()) {
            setConfiguration(PROJECT_KEY_PREFIX + scope.getProject().getId(), values);
        } else {
            setConfiguration(REPO_KEY_PREFIX + scope.getRepository().getId(), values);
        }
    }

    private void setConfiguration(String scopePrefix, Map<String, String[]> values) {
        if (values.containsKey("active")
                && ("true".equals(values.get("active")[0]) || "false".equals(values.get("active")[0]))) {
            settings.put(scopePrefix + ACTIVE_KEY, values.get("active")[0]);
        } else {
            settings.remove(scopePrefix + ACTIVE_KEY);
        }
        if (values.get("overwritten") == null) {
            for (String key : CONFIGURATION_KEYS) {
                settings.remove(scopePrefix + ":" + key);
            }
        } else {
            for (String key : CONFIGURATION_KEYS) {
                if (values.containsKey(key)) {
                    settings.put(scopePrefix + ":" + key, values.get(key)[0]);
                } else {
                    settings.remove(scopePrefix + ":" + key);
                }
            }
        }
    }

    private boolean isOverwritten(Scope scope) {
        if (scope.isProject()) {
            return settings.get(PROJECT_KEY_PREFIX + scope.getProject().getId() + ":" + CONFIGURATION_KEYS[0]) != null;
        }
        return settings.get(REPO_KEY_PREFIX + scope.getRepository().getId() + ":" + CONFIGURATION_KEYS[0]) != null;
    }

    private String getBinary(String name, BinaryType type) {
        if (type == BinaryType.EMBEDDED) {
            Path binary = MoreFiles.resolve(storageService.getHomeDir(), "binaries", name);
            if (needsToExtract(name, binary)) {
                MoreFiles.mkdir(binary.getParent());
                try (InputStream binaryStream = getClass().getResourceAsStream("/binaries/" + name)) {
                    Files.copy(binaryStream, binary, REPLACE_EXISTING);
                    MoreFiles.setPermissions(new SetFilePermissionRequest.Builder(binary).ownerPermission(EXECUTE)
                            .ownerPermission(READ).ownerPermission(WRITE).build());
                    Files.setLastModifiedTime(binary, getLastModifiedOfEmbedded(name));
                } catch (IOException e) {
                    LOGGER.warn("failed to copy embedded " + name + " binary", e);
                }
            }
            return binary.toString();
        }
        if (type == BinaryType.UPLOADED) {
            Path sharedBinary = MoreFiles.resolve(storageService.getSharedHomeDir(), "binaries", "uploaded", name);
            Path localBinary = MoreFiles.resolve(storageService.getHomeDir(), "binaries", "uploaded", name);
            try {
                if (Files.exists(sharedBinary) && (!Files.exists(localBinary) || Files.getLastModifiedTime(localBinary)
                        .compareTo(Files.getLastModifiedTime(sharedBinary)) < 0)) {
                    MoreFiles.mkdir(localBinary.getParent());
                    Files.copy(sharedBinary, localBinary, REPLACE_EXISTING);
                }
            } catch (IOException e) {
                LOGGER.warn("failed to copy uploaded " + name + " binary", e);
            }
            return localBinary.toString();
        }
        return name;
    }

    private boolean needsToExtract(String name, Path binary) {
        try {
            return !Files.exists(binary)
                    || Files.getLastModifiedTime(binary).compareTo(getLastModifiedOfEmbedded(name)) < 0;
        } catch (IOException e) {
            LOGGER.warn("failed to compare extracted binary", e);
            return true;
        }
    }

    private FileTime getLastModifiedOfEmbedded(String name) throws IOException {
        return FileTime.fromMillis(getClass().getResource("/binaries/" + name).openConnection().getLastModified());
    }

    private boolean getBooleanValue(String key, Scope scope, boolean defaultValue) {
        return Boolean.parseBoolean(getSettingsValue(key, scope, Boolean.toString(defaultValue)));
    }

    @SuppressWarnings("unchecked")
    private <E extends Enum<E>> E getEnumValue(String key, Scope scope, E defaultValue) {
        String value = getSettingsValue(key, scope, defaultValue.name());
        try {
            return (E) Enum.valueOf(defaultValue.getClass(), value);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("unknown enum value, will use default", e);
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T getSettingsValue(String key, Scope scope, T defaultValue) {
        Stream<Object> values = Stream.of(settings.get(KEY_PREFIX + key));
        if (scope != null) {
            values = concat(Stream.of(settings.get(PROJECT_KEY_PREFIX + scope.getProject().getId() + ":" + key)),
                    values);
            if (scope.isRepository()) {
                values = concat(Stream.of(settings.get(REPO_KEY_PREFIX + scope.getRepository().getId() + ":" + key)),
                        values);
            }
        }
        return (T) values.filter(Objects::nonNull).findFirst().orElse(defaultValue);
    }

    @EventListener
    public void onRepositoryDeleted(RepositoryDeletedEvent event) {
        setConfiguration(scope(event.getRepository()), emptyMap());
    }

    @EventListener
    public void onProjectDeleted(ProjectDeletedEvent event) {
        setConfiguration(scope(event.getProject()), emptyMap());
    }

}
