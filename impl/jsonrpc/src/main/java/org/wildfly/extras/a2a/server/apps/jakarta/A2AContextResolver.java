package org.wildfly.extras.a2a.server.apps.jakarta;

import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import io.a2a.spec.DeleteTaskPushNotificationConfigResponse;
import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.JSONRPCVoidResponseSerializer;

@Provider
public class A2AContextResolver implements ContextResolver<ObjectMapper> {

    private final ObjectMapper mapper;

    public A2AContextResolver() {
        mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(new JSONRPCVoidResponseSerializer());
        mapper.registerModule(module);
    }

    @Override
    public ObjectMapper getContext(Class<?> type) {
        if (JSONRPCResponse.class.isAssignableFrom(type) && type.equals(DeleteTaskPushNotificationConfigResponse.class)) {
            return mapper;
        }
        return null;
    }
}