package org.wildfly.a2a.jakarta.common;

import java.util.Set;

public interface A2AVersionProvider {

    String getVersion();

    /**
     * Returns {@code true} if this is the default version for its transport.
     * <p>
     * Each {@link A2AVersionResolver} is scoped to a single transport (JSON-RPC or REST),
     * so a JSON-RPC provider and a REST provider may both return {@code true} without
     * conflicting — they are registered in separate resolvers.
     */
    boolean isDefaultVersion();

    String getInternalPathPrefix();

    /**
     * The client-facing REST base path for this version (e.g. {@code "/"} or {@code "/v1"}).
     * Return {@code null} for JSON-RPC-only providers.
     */
    String getRestBasePath();

    /**
     * Path prefixes that identify requests belonging to this REST version.
     * <p>
     * Required for providers whose {@link #getRestBasePath()} is {@code "/"}: the routing
     * filter uses this list to decide whether an incoming request is an A2A request before
     * rewriting its path. Only requests whose path starts with one of these prefixes are
     * routed; all others pass through unchanged. This prevents non-A2A paths (e.g. test
     * utility endpoints) from being rewritten.
     * <p>
     * Root-path providers that return an empty set will have <em>all</em> of their requests
     * silently bypassed by the filter, regardless of whether an {@code A2A-Version} header
     * is present. Root-path providers must therefore override this method and list every
     * A2A resource path prefix they serve (without leading slash, e.g. {@code "tasks"},
     * {@code "message"}).
     * <p>
     * Providers with a non-root base path do not need this — the base path itself is
     * sufficient to identify matching requests.
     *
     * @return path prefixes (without leading slash); must be non-empty for root-path providers
     */
    default Set<String> getRestPathPrefixes() {
        return Set.of();
    }
}
