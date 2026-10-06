package org.wildfly.a2a.jakarta.common;

import java.util.Set;

class TestProviders {

    static A2AVersionProvider provider(String version, boolean isDefault, String internalPrefix, String restBasePath) {
        return provider(version, isDefault, internalPrefix, restBasePath, Set.of());
    }

    static A2AVersionProvider provider(String version, boolean isDefault, String internalPrefix, String restBasePath, Set<String> pathPrefixes) {
        return new A2AVersionProvider() {
            @Override public String getVersion() { return version; }
            @Override public boolean isDefaultVersion() { return isDefault; }
            @Override public String getInternalPathPrefix() { return internalPrefix; }
            @Override public String getRestBasePath() { return restBasePath; }
            @Override public Set<String> getRestPathPrefixes() { return pathPrefixes; }
        };
    }

    static A2AVersionProvider provider(String version, String internalPrefix, String restBasePath) {
        return provider(version, false, internalPrefix, restBasePath);
    }

    static A2AVersionProvider jsonRpcProvider(String version, boolean isDefault) {
        return provider(version, isDefault, "/a2a_jsonrpc_v" + version, null);
    }

    private TestProviders() {
    }
}
