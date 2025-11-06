package org.wildfly.extras.a2a.server.apps.grpc;

import java.util.concurrent.Executor;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.a2a.server.PublicAgentCard;
import io.a2a.server.requesthandlers.RequestHandler;
import io.a2a.server.util.async.Internal;
import io.a2a.spec.AgentCard;
import io.a2a.transport.grpc.handler.CallContextFactory;

/**
 * Bean initializer that observes application startup events.
 *
 * Since CDI is not available on gRPC threads, we capture the CDI beans
 * during application startup and store them statically for use by
 * the WildFly gRPC subsystem.
 */
@ApplicationScoped
public class GrpcBeanInitializer {

    @Inject
    @PublicAgentCard
    AgentCard agentCard;

    @Inject
    RequestHandler requestHandler;

    @Inject
    Instance<CallContextFactory> callContextFactory;

    @Inject
    @Internal
    Executor executor;

    /**
     * Observes the application startup event to eagerly initialize the gRPC cache.
     */
    public void onStartup(@Observes @Initialized(ApplicationScoped.class) Object init) {
        try {
            // Cache CDI beans for gRPC threads to use since CDI is not available on those threads
            CallContextFactory ccf = callContextFactory.isUnsatisfied() ? null : callContextFactory.get();
            WildFlyGrpcHandler.setStaticBeans(agentCard, requestHandler, ccf, executor);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @PreDestroy
    public void cleanup() {
        WildFlyGrpcHandler.setStaticBeans(null, null, null, null);
    }
}