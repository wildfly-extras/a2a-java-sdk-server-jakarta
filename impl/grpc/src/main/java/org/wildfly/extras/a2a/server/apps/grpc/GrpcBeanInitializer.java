package org.wildfly.extras.a2a.server.apps.grpc;

import java.util.concurrent.Executor;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.a2a.server.ExtendedAgentCard;
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
    @ExtendedAgentCard
    Instance<AgentCard> extendedAgentCard;

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
            AgentCard extCard = extendedAgentCard.isUnsatisfied() ? null : extendedAgentCard.get();
            // Capture the deployment classloader for use on gRPC threads
            // This is needed because gRPC threads have the grpc extension module classloader as TCCL,
            // which cannot see deployment WEB-INF/lib jars needed by ServiceLoader
            ClassLoader deploymentClassLoader = Thread.currentThread().getContextClassLoader();

            // Force ClientBuilder class to load now with the correct deployment classloader as TCCL
            // This ensures its static initializer runs with access to WEB-INF/lib transport providers
            // Without this, if ClientBuilder loads earlier with the wrong TCCL, the static registry
            // won't contain the gRPC transport provider
            try {
                Class.forName("io.a2a.client.ClientBuilder", true, deploymentClassLoader);
            } catch (ClassNotFoundException e) {
                // ClientBuilder not in deployment, ignore
            }

            WildFlyGrpcHandler.setStaticBeans(agentCard, extCard, requestHandler, ccf, executor, deploymentClassLoader);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @PreDestroy
    public void cleanup() {
        WildFlyGrpcHandler.setStaticBeans(null, null, null, null, null, null);
    }
}