package org.wildfly.extras.a2a.server.apps.rest;


import jakarta.servlet.http.HttpServletRequest;

import io.a2a.server.ServerCallContext;

public interface CallContextFactory {
    ServerCallContext build(HttpServletRequest request);
}
