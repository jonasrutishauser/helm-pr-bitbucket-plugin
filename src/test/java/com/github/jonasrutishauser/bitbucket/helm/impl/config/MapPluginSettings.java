package com.github.jonasrutishauser.bitbucket.helm.impl.config;

import java.util.HashMap;

import com.atlassian.sal.api.pluginsettings.PluginSettings;

class MapPluginSettings extends HashMap<String, Object> implements PluginSettings {
    @Override
    public Object get(String key) {
        return get((Object) key);
    }

    @Override
    public Object remove(String key) {
        return remove((Object) key);
    }
}
