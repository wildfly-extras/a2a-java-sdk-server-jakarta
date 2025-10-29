package org.wildfly.extras.a2a.server.apps.rest;

import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import io.a2a.common.A2AHeaders;
import io.a2a.server.ExtendedAgentCard;
import io.a2a.server.ServerCallContext;
import io.a2a.server.auth.UnauthenticatedUser;
import io.a2a.server.auth.User;
import io.a2a.server.extensions.A2AExtensions;
import io.a2a.server.util.async.Internal;
import io.a2a.spec.AgentCard;
import io.a2a.spec.InvalidParamsError;
import io.a2a.transport.rest.handler.RestHandler;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/")
public class A2ARestServerResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(A2ARestServerResource.class);

    @Inject
    RestHandler jsonRestHandler;

    @Inject
    @ExtendedAgentCard
    Instance<AgentCard> extendedAgentCard;

    // Hook so testing can wait until the async Subscription is subscribed.
    private static volatile Runnable streamingIsSubscribedRunnable;

    @Inject
    @Internal
    Executor executor;

    @Inject
    Instance<CallContextFactory> callContextFactory;

    /**
     * Handles incoming POST requests to the main A2A endpoint.Dispatches the
     * request to the appropriate JSON-RPC handler method and returns the response.
     *
     * @param body
     * @param httpRequest the HTTP request
     * @return the JSON-RPC response which may be an error response
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("v1/message:send")
    public Response sendMessage(String body, @Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = null;
        try {
            response = jsonRestHandler.sendMessage(body, context);
        } catch (Throwable t) {
            response = jsonRestHandler.createErrorResponse(new io.a2a.spec.InternalError(t.getMessage()));
        } finally {
            return Response.status(response.getStatusCode())
                    .header(CONTENT_TYPE, response.getContentType())
                    .entity(response.getBody())
                    .build();
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Path("v1/message:stream")
    public void sendMessageStreaming(String body, @Context HttpServletRequest httpRequest, @Context HttpServletResponse httpResponse, @Context SecurityContext securityContext) throws IOException {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestStreamingResponse streamingResponse = null;
        RestHandler.HTTPRestResponse error = null;
        try {
            RestHandler.HTTPRestResponse response = jsonRestHandler.sendStreamingMessage(body, context);
            if (response instanceof RestHandler.HTTPRestStreamingResponse hTTPRestStreamingResponse) {
                streamingResponse = hTTPRestStreamingResponse;
            } else {
                error = response;
            }
        } finally {
            if (error != null) {
                httpResponse.setHeader(CONTENT_TYPE, APPLICATION_JSON);
                httpResponse.sendError(error.getStatusCode(), error.getBody());
            } else {
                handleCustomSSEResponse(streamingResponse.getPublisher(), httpResponse);
            }
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Path("v1/tasks/{taskId}:subscribe")
    public void resubscribeTask(@PathParam("taskId") String taskId, @Context HttpServletRequest httpRequest, @Context HttpServletResponse httpResponse, @Context SecurityContext securityContext) throws IOException {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestStreamingResponse streamingResponse = null;
        RestHandler.HTTPRestResponse error = null;
        try {
            RestHandler.HTTPRestResponse response = jsonRestHandler.resubscribeTask(taskId, context);
            if (response instanceof RestHandler.HTTPRestStreamingResponse hTTPRestStreamingResponse) {
                streamingResponse = hTTPRestStreamingResponse;
            } else {
                error = response;
            }
        } finally {
            if (error != null) {
                httpResponse.setHeader(CONTENT_TYPE, APPLICATION_JSON);
                httpResponse.sendError(error.getStatusCode(), error.getBody());
            } else {
                handleCustomSSEResponse(streamingResponse.getPublisher(), httpResponse);
            }
        }
    }

    /**
     * Handles incoming GET requests to the agent card endpoint.
     * Returns the agent card in JSON format.
     *
     * @return the agent card
     */
    @GET
    @Path(".well-known/agent-card.json")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAgentCard() {
        RestHandler.HTTPRestResponse response = jsonRestHandler.getAgentCard();
        return Response.status(response.getStatusCode())
                .header(CONTENT_TYPE, response.getContentType())
                .entity(response.getBody())
                .build();
    }

    @GET
    @Path("v1/card")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAuthenticatedExtendedCard() {
        RestHandler.HTTPRestResponse response = jsonRestHandler.getAuthenticatedExtendedCard();
        return Response.status(response.getStatusCode())
                .header(CONTENT_TYPE, response.getContentType())
                .entity(response.getBody())
                .build();
    }

    @GET
    @Path("v1/tasks/{taskId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTask(@PathParam("taskId") String taskId, @QueryParam("history_length") String history_length,
            @Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = null;
        try {
            int historyLength = 0;
            if (history_length != null) {
                historyLength = Integer.valueOf(history_length);
            }
            response = jsonRestHandler.getTask(taskId, historyLength, context);
        } catch (NumberFormatException e) {
            response = jsonRestHandler.createErrorResponse(new InvalidParamsError("bad history_length"));
        } catch (Throwable t) {
            response = jsonRestHandler.createErrorResponse(new io.a2a.spec.InternalError(t.getMessage()));
        } finally {
            return Response.status(response.getStatusCode())
                    .header(CONTENT_TYPE, response.getContentType())
                    .entity(response.getBody())
                    .build();
        }
    }

    @POST
    @Path("v1/tasks/{taskId}:cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelTask(@PathParam("taskId") String taskId, String body, @Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = null;
        try {
            response = jsonRestHandler.cancelTask(taskId, context);
        } catch (Throwable t) {
            response = jsonRestHandler.createErrorResponse(new io.a2a.spec.InternalError(t.getMessage()));
        } finally {
            return Response.status(response.getStatusCode())
                    .header(CONTENT_TYPE, response.getContentType())
                    .entity(response.getBody())
                    .build();
        }
    }

    @POST
    @Path("v1/tasks/{taskId}/pushNotificationConfigs")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setTaskPushNotificationConfiguration(@PathParam("taskId") String taskId, String body, @Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = null;
        try {
            response = jsonRestHandler.setTaskPushNotificationConfiguration(taskId, body, context);
        } catch (Throwable t) {
            response = jsonRestHandler.createErrorResponse(new io.a2a.spec.InternalError(t.getMessage()));
        } finally {
            return Response.status(response.getStatusCode())
                    .header(CONTENT_TYPE, response.getContentType())
                    .entity(response.getBody())
                    .build();
        }
    }

    @GET
    @Path("v1/tasks/{taskId}/pushNotificationConfigs/{configId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTaskPushNotificationConfiguration(@PathParam("taskId") String taskId, @PathParam("configId") String configId, @Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = null;
        try {
            response = jsonRestHandler.getTaskPushNotificationConfiguration(taskId, configId, context);
        } catch (Throwable t) {
            response = jsonRestHandler.createErrorResponse(new io.a2a.spec.InternalError(t.getMessage()));
        } finally {
            return Response.status(response.getStatusCode())
                    .header(CONTENT_TYPE, response.getContentType())
                    .entity(response.getBody())
                    .build();
        }
    }

    @GET
    @Path("v1/tasks/{taskId}/pushNotificationConfigs")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response listTaskPushNotificationConfigurations(@PathParam("taskId") String taskId, @Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = null;
        try {
            response = jsonRestHandler.listTaskPushNotificationConfigurations(taskId, context);
        } catch (Throwable t) {
            response = jsonRestHandler.createErrorResponse(new io.a2a.spec.InternalError(t.getMessage()));
        } finally {
            return Response.status(response.getStatusCode())
                    .header(CONTENT_TYPE, response.getContentType())
                    .entity(response.getBody())
                    .build();
        }
    }

    @DELETE
    @Path("v1/tasks/{taskId}/pushNotificationConfigs/{configId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteTaskPushNotificationConfiguration(@PathParam("taskId") String taskId, @PathParam("configId") String configId, @Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = null;
        try {
            response = jsonRestHandler.deleteTaskPushNotificationConfiguration(taskId, configId, context);
        } catch (Throwable t) {
            response = jsonRestHandler.createErrorResponse(new io.a2a.spec.InternalError(t.getMessage()));
        } finally {
            return Response.status(response.getStatusCode())
                    .header(CONTENT_TYPE, response.getContentType())
                    .entity(response.getBody())
                    .build();
        }
    }

    /**
     * Handles the streaming response using custom SSE formatting.
     * This approach avoids JAX-RS SSE compatibility issues with async publishers.
     */
    private void handleCustomSSEResponse(Flow.Publisher<String> publisher,
            HttpServletResponse response) throws IOException {
        CompletableFuture<Void> streamingComplete = new CompletableFuture<>();
        try (PrintWriter writer = response.getWriter()) {
            publisher.subscribe(new SSESubscriber(streamingComplete, writer));
            // Wait for streaming to complete before method returns
            streamingComplete.get();
        } catch (Exception e) {
            LOGGER.error("Error waiting for streaming completion: {}", e.getMessage(), e);
            throw new IOException("Streaming failed", e);
        }
    }

    public static void setStreamingIsSubscribedRunnable(Runnable streamingIsSubscribedRunnable) {
        A2ARestServerResource.streamingIsSubscribedRunnable = streamingIsSubscribedRunnable;
        SSESubscriber.setStreamingIsSubscribedRunnable(streamingIsSubscribedRunnable);
    }

    private ServerCallContext createCallContext(HttpServletRequest request, SecurityContext securityContext) {

        if (callContextFactory.isUnsatisfied()) {
            User user;

            if (securityContext.getUserPrincipal() == null) {
                user = UnauthenticatedUser.INSTANCE;
            } else {
                user = new User() {
                    @Override
                    public boolean isAuthenticated() {
                        return true;
                    }

                    @Override
                    public String getUsername() {
                        return securityContext.getUserPrincipal().getName();
                    }
                };
            }
            Map<String, Object> state = new HashMap<>();
            // TODO Python's impl has
            //    state['auth'] = request.auth
            //  in jsonrpc_app.py. Figure out what this maps to in what we have here

            Map<String, String> headers = new HashMap<>();
            for (Enumeration<String> headerNames = request.getHeaderNames(); headerNames.hasMoreElements();) {
                String name = headerNames.nextElement();
                headers.put(name, request.getHeader(name));
            }

            state.put("headers", headers);

            Enumeration<String> en = request.getHeaders(A2AHeaders.X_A2A_EXTENSIONS);
            List<String> extensionHeaderValues = new ArrayList<>();
            while (en.hasMoreElements()) {
                extensionHeaderValues.add(en.nextElement());
            }
            Set<String> requestedExtensions = A2AExtensions.getRequestedExtensions(extensionHeaderValues);
            return new ServerCallContext(user, state, requestedExtensions);
        } else {
            CallContextFactory builder = callContextFactory.get();
            return builder.build(request);
        }
    }
}
