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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/")
public class A2ARestServerResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(A2ARestServerResource.class);
    private static final String PAGE_SIZE_PARAM = "pageSize";
    private static final String PAGE_TOKEN_PARAM = "pageToken";

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
    @Path("message:send")
    public Response sendMessage(String body, @Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = null;
        try {
            response = jsonRestHandler.sendMessage(context, getTenant(httpRequest), body);
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
    @Path("message:stream")
    public void sendMessageStreaming(String body, @Context HttpServletRequest httpRequest, @Context HttpServletResponse httpResponse, @Context SecurityContext securityContext) throws IOException {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestStreamingResponse streamingResponse = null;
        RestHandler.HTTPRestResponse error = null;
        try {
            RestHandler.HTTPRestResponse response = jsonRestHandler.sendStreamingMessage(context, getTenant(httpRequest), body);
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
                handleCustomSSEResponse(streamingResponse.getPublisher(), httpResponse, context);
            }
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Path("tasks/{taskId}:subscribe")
    public void resubscribeTask(@PathParam("taskId") String taskId, @Context HttpServletRequest httpRequest, @Context HttpServletResponse httpResponse, @Context SecurityContext securityContext) throws IOException {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestStreamingResponse streamingResponse = null;
        RestHandler.HTTPRestResponse error = null;
        try {
            RestHandler.HTTPRestResponse response = jsonRestHandler.subscribeToTask(context, getTenant(httpRequest), taskId);
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
                handleCustomSSEResponse(streamingResponse.getPublisher(), httpResponse, context);
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
    @Path("card")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAuthenticatedExtendedCard(@Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = jsonRestHandler.getExtendedAgentCard(context, getTenant(httpRequest));
        return Response.status(response.getStatusCode())
                .header(CONTENT_TYPE, response.getContentType())
                .entity(response.getBody())
                .build();
    }

    @GET
    @Path("extendedAgentCard")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getExtendedAgentCard(@Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = jsonRestHandler.getExtendedAgentCard(context, getTenant(httpRequest));
        return Response.status(response.getStatusCode())
                .header(CONTENT_TYPE, response.getContentType())
                .entity(response.getBody())
                .build();
    }

    @GET
    @Path("tasks")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response listTasks(@Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = null;
        try {
            // Extract query parameters
            String contextId = httpRequest.getParameter("contextId");
            String statusStr = httpRequest.getParameter("status");
            if (statusStr != null && !statusStr.isEmpty()) {
                statusStr = statusStr.toUpperCase();
            }
            String pageSizeStr = httpRequest.getParameter(PAGE_SIZE_PARAM);
            String pageToken = httpRequest.getParameter(PAGE_TOKEN_PARAM);
            String historyLengthStr = httpRequest.getParameter("historyLength");
            String lastUpdatedAfter = httpRequest.getParameter("lastUpdatedAfter");
            String includeArtifactsStr = httpRequest.getParameter("includeArtifacts");

            // Parse optional parameters
            Integer pageSize = null;
            if (pageSizeStr != null && !pageSizeStr.isEmpty()) {
                pageSize = Integer.valueOf(pageSizeStr);
            }

            Integer historyLength = null;
            if (historyLengthStr != null && !historyLengthStr.isEmpty()) {
                historyLength = Integer.valueOf(historyLengthStr);
            }

            Boolean includeArtifacts = null;
            if (includeArtifactsStr != null && !includeArtifactsStr.isEmpty()) {
                includeArtifacts = Boolean.valueOf(includeArtifactsStr);
            }

            response = jsonRestHandler.listTasks(context, getTenant(httpRequest), contextId, statusStr, pageSize,
                    pageToken, historyLength, lastUpdatedAfter, includeArtifacts);
        } catch (NumberFormatException e) {
            response = jsonRestHandler.createErrorResponse(new InvalidParamsError("Invalid number format in parameters"));
        } catch (IllegalArgumentException e) {
            response = jsonRestHandler.createErrorResponse(new InvalidParamsError("Invalid parameter value: " + e.getMessage()));
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
    @Path("tasks/{taskId}")
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
            response = jsonRestHandler.getTask(context, getTenant(httpRequest), taskId, historyLength);
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
    @Path("tasks/{taskId}:cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelTask(@PathParam("taskId") String taskId, @Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = null;
        try {
            response = jsonRestHandler.cancelTask(context, getTenant(httpRequest), taskId);
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
    @Path("tasks/{taskId}/pushNotificationConfigs")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setTaskPushNotificationConfiguration(@PathParam("taskId") String taskId, String body, @Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = null;
        try {
            response = jsonRestHandler.createTaskPushNotificationConfiguration(context, getTenant(httpRequest), body, taskId);
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
    @Path("tasks/{taskId}/pushNotificationConfigs/{configId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTaskPushNotificationConfiguration(@PathParam("taskId") String taskId, @PathParam("configId") String configId, @Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = null;
        try {
            response = jsonRestHandler.getTaskPushNotificationConfiguration(context, getTenant(httpRequest), taskId, configId);
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
    @Path("tasks/{taskId}/pushNotificationConfigs")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getOrListTaskPushNotificationConfigurations(@PathParam("taskId") String taskId, @Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = null;
        try {
            if (taskId == null || taskId.isEmpty()) {
                response = jsonRestHandler.createErrorResponse(new InvalidParamsError("bad task id"));
            } else {
                // Check if request has trailing slash to distinguish GET (with /) from LIST (without /)
                String requestURI = httpRequest.getRequestURI();
                if (requestURI.endsWith("/")) {
                    // GET with null configId - trailing slash case
                    response = jsonRestHandler.getTaskPushNotificationConfiguration(context, getTenant(httpRequest),
                            taskId, null);
                } else {
                    // LIST - no trailing slash case
                    int pageSize = 0;
                    if (httpRequest.getParameter(PAGE_SIZE_PARAM) != null) {
                        pageSize = Integer.parseInt(httpRequest.getParameter(PAGE_SIZE_PARAM));
                    }
                    String pageToken = "";
                    if (httpRequest.getParameter(PAGE_TOKEN_PARAM) != null) {
                        pageToken = httpRequest.getParameter(PAGE_TOKEN_PARAM);
                    }
                    response = jsonRestHandler.listTaskPushNotificationConfigurations(context, getTenant(httpRequest),
                            taskId, pageSize, pageToken);
                }
            }
        } catch (NumberFormatException e) {
            response = jsonRestHandler.createErrorResponse(new InvalidParamsError("bad " + PAGE_SIZE_PARAM));
        } catch (Throwable t) {
            response = jsonRestHandler.createErrorResponse(new io.a2a.spec.InternalError(t.getMessage()));
        }
        return Response.status(response.getStatusCode())
                .header(CONTENT_TYPE, response.getContentType())
                .entity(response.getBody())
                .build();
    }

    @DELETE
    @Path("tasks/{taskId}/pushNotificationConfigs/{configId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteTaskPushNotificationConfiguration(@PathParam("taskId") String taskId, @PathParam("configId") String configId, @Context HttpServletRequest httpRequest, @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        RestHandler.HTTPRestResponse response = null;
        try {
            response = jsonRestHandler.deleteTaskPushNotificationConfiguration(context, getTenant(httpRequest), taskId, configId);
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
     * Implements proper client disconnect detection and EventConsumer cancellation.
     */
    private void handleCustomSSEResponse(Flow.Publisher<String> publisher,
            HttpServletResponse response,
            ServerCallContext context) throws IOException {
        CompletableFuture<Void> streamingComplete = new CompletableFuture<>();
        try (PrintWriter writer = response.getWriter()) {
            publisher.subscribe(new SSESubscriber(streamingComplete, writer, context));
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

    private String getTenant(HttpServletRequest request) {
        return request.getContextPath();
    }
}
