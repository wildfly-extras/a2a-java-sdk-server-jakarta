package org.wildfly.extras.a2a.server.apps.jakarta;

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
import java.util.concurrent.atomic.AtomicLong;

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
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.Providers;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.common.A2AHeaders;
import io.a2a.server.ExtendedAgentCard;
import io.a2a.server.ServerCallContext;
import io.a2a.server.auth.UnauthenticatedUser;
import io.a2a.server.auth.User;
import io.a2a.server.extensions.A2AExtensions;
import io.a2a.server.util.async.Internal;
import io.a2a.spec.AgentCard;
import io.a2a.spec.CancelTaskRequest;
import io.a2a.spec.DeleteTaskPushNotificationConfigRequest;
import io.a2a.spec.GetAuthenticatedExtendedCardRequest;
import io.a2a.spec.GetTaskPushNotificationConfigRequest;
import io.a2a.spec.GetTaskRequest;
import io.a2a.spec.IdJsonMappingException;
import io.a2a.spec.InvalidParamsError;
import io.a2a.spec.InvalidParamsJsonMappingException;
import io.a2a.spec.InvalidRequestError;
import io.a2a.spec.JSONParseError;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.JSONRPCErrorResponse;
import io.a2a.spec.JSONRPCRequest;
import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.ListTaskPushNotificationConfigRequest;
import io.a2a.spec.MethodNotFoundError;
import io.a2a.spec.MethodNotFoundJsonMappingException;
import io.a2a.spec.NonStreamingJSONRPCRequest;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendStreamingMessageRequest;
import io.a2a.spec.SetTaskPushNotificationConfigRequest;
import io.a2a.spec.StreamingJSONRPCRequest;
import io.a2a.spec.TaskResubscriptionRequest;
import io.a2a.spec.UnsupportedOperationError;
import io.a2a.transport.jsonrpc.handler.JSONRPCHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/")
    public class A2AServerResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(A2AServerResource.class);

    @Inject
    JSONRPCHandler jsonRpcHandler;

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
     * Handles incoming POST requests to the main A2A endpoint. Dispatches the
     * request to the appropriate JSON-RPC handler method and returns the response.
     *
     * @param request the JSON-RPC request
     * @return the JSON-RPC response which may be an error response
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public JSONRPCResponse<?> handleNonStreamingRequests(
            NonStreamingJSONRPCRequest<?> request, @Context HttpServletRequest httpRequest,
            @Context SecurityContext securityContext) {

        ServerCallContext context = createCallContext(httpRequest, securityContext);
        LOGGER.debug("Handling non-streaming request");
        try {
            return processNonStreamingRequest(request, context);
        } finally {
            LOGGER.debug("Completed non-streaming request");
        }
    }

    /**
     * Handles incoming POST requests to the main A2A endpoint that involve Server-Sent Events (SSE).
     * Uses custom SSE response handling to avoid JAX-RS SSE compatibility issues with async publishers.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void handleStreamingRequests(
            StreamingJSONRPCRequest<?> request, 
            @Context HttpServletResponse response,
            @Context HttpServletRequest httpRequest,
            @Context SecurityContext securityContext,
            @Context Providers providers) throws IOException {
        
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        LOGGER.debug("Handling streaming request with custom SSE response");
        
        // Set SSE headers manually for proper streaming
        response.setContentType(MediaType.SERVER_SENT_EVENTS);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");

        // Get the ObjectMapper from JAX-RS context
        ObjectMapper objectMapper = providers.getContextResolver(ObjectMapper.class, MediaType.APPLICATION_JSON_TYPE)
                .getContext(JSONRPCResponse.class);
        if (objectMapper == null) {
            // Fallback to properly configured ObjectMapper if context resolver doesn't provide one
            objectMapper = new ObjectMapper();
            objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }
        
        // Get the publisher synchronously to avoid connection closure issues
        Flow.Publisher<? extends JSONRPCResponse<?>> publisher = createStreamingPublisher(request, context);
        LOGGER.debug("Created streaming publisher: {}", publisher);
        
        if (publisher != null) {
            // Handle the streaming response with custom SSE formatting
            LOGGER.debug("Handling custom SSE response for publisher: {}", publisher);
            handleCustomSSEResponse(publisher, response, objectMapper);
        } else {
            // Handle unsupported request types
            LOGGER.debug("Unsupported streaming request type: {}", request.getClass().getSimpleName());
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unsupported streaming request type");
        }
        
        LOGGER.debug("Completed streaming request processing");
    }

    /**
     * Handles incoming GET requests to the agent card endpoint.
     * Returns the agent card in JSON format.
     *
     * @return the agent card
     */
    @GET
    @Path("/.well-known/agent-card.json")
    @Produces(MediaType.APPLICATION_JSON)
    public AgentCard getAgentCard() {
        return jsonRpcHandler.getAgentCard();
    }

    private JSONRPCResponse<?> processNonStreamingRequest(NonStreamingJSONRPCRequest<?> request,
                                                          ServerCallContext context) {
        if (request instanceof GetTaskRequest req) {
            return jsonRpcHandler.onGetTask(req, context);
        } else if (request instanceof CancelTaskRequest req) {
            return jsonRpcHandler.onCancelTask(req, context);
        } else if (request instanceof SetTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.setPushNotificationConfig(req, context);
        } else if (request instanceof GetTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.getPushNotificationConfig(req, context);
        } else if (request instanceof SendMessageRequest req) {
            return jsonRpcHandler.onMessageSend(req, context);
        } else if (request instanceof ListTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.listPushNotificationConfig(req, context);
        } else if (request instanceof DeleteTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.deletePushNotificationConfig(req, context);
        } else if (request instanceof GetAuthenticatedExtendedCardRequest req) {
            return jsonRpcHandler.onGetAuthenticatedExtendedCardRequest(req, context);
        } else {
            return generateErrorResponse(request, new UnsupportedOperationError());
        }
    }

    /**
     * Creates a streaming publisher for the given request.
     * This method runs synchronously to avoid connection closure issues.
     */
    private Flow.Publisher<? extends JSONRPCResponse<?>> createStreamingPublisher(StreamingJSONRPCRequest<?> request, 
                                                                                 ServerCallContext context) {
        if (request instanceof SendStreamingMessageRequest req) {
            return jsonRpcHandler.onMessageSendStream(req, context);
        } else if (request instanceof TaskResubscriptionRequest req) {
            return jsonRpcHandler.onResubscribeToTask(req, context);
        } else {
            return null; // Unsupported request type
        }
    }

    /**
     * Handles the streaming response using custom SSE formatting.
     * This approach avoids JAX-RS SSE compatibility issues with async publishers.
     */
    private void handleCustomSSEResponse(Flow.Publisher<? extends JSONRPCResponse<?>> publisher, 
                                       HttpServletResponse response, ObjectMapper objectMapper) throws IOException {
        
        PrintWriter writer = response.getWriter();
        AtomicLong eventId = new AtomicLong(0);
        CompletableFuture<Void> streamingComplete = new CompletableFuture<>();
        
        publisher.subscribe(new Flow.Subscriber<JSONRPCResponse<?>>() {
            @SuppressWarnings("unused") // Stored for potential future use (e.g., cancellation)
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                LOGGER.debug("Custom SSE subscriber onSubscribe called");
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
                
                // Notify tests that we are subscribed
                Runnable runnable = streamingIsSubscribedRunnable;
                if (runnable != null) {
                    runnable.run();
                }
            }

            @Override
            public void onNext(JSONRPCResponse<?> item) {
                LOGGER.debug("Custom SSE subscriber onNext called with item: {}", item);
                try {
                    // Format as proper SSE event (matching Quarkus format)
                    String jsonData = objectMapper.writeValueAsString(item);
                    long id = eventId.getAndIncrement();
                    
                    writer.write("data: " + jsonData + "\n");
                    writer.write("id: " + id + "\n");
                    writer.write("\n"); // Empty line to complete the event
                    writer.flush();
                    
                    LOGGER.debug("Custom SSE event sent successfully with id: {}", id);
                } catch (Exception e) {
                    LOGGER.error("Error writing SSE event: {}", e.getMessage(), e);
                    onError(e);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                LOGGER.debug("Custom SSE subscriber onError called: {}", throwable.getMessage(), throwable);
                try {
                    writer.close();
                } catch (Exception e) {
                    LOGGER.error("Error closing writer: {}", e.getMessage(), e);
                }
                streamingComplete.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                LOGGER.debug("Custom SSE subscriber onComplete called");
                try {
                    writer.close();
                } catch (Exception e) {
                    LOGGER.error("Error closing writer: {}", e.getMessage(), e);
                }
                streamingComplete.complete(null);
            }
        });
        
        try {
            // Wait for streaming to complete before method returns
            streamingComplete.get();
        } catch (Exception e) {
            LOGGER.error("Error waiting for streaming completion: {}", e.getMessage(), e);
            throw new IOException("Streaming failed", e);
        }
    }


    private JSONRPCResponse<?> generateErrorResponse(JSONRPCRequest<?> request, JSONRPCError error) {
        return new JSONRPCErrorResponse(request.getId(), error);
    }

    public static void setStreamingIsSubscribedRunnable(Runnable streamingIsSubscribedRunnable) {
        A2AServerResource.streamingIsSubscribedRunnable = streamingIsSubscribedRunnable;
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
            for (Enumeration<String> headerNames = request.getHeaderNames(); headerNames.hasMoreElements() ; ) {
                String name = headerNames.nextElement();
                headers.put(name, headers.get(name));
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

    @Provider
    public static class JsonParseExceptionMapper implements ExceptionMapper<JsonParseException> {

        public JsonParseExceptionMapper() {
        }

        @Override
        public Response toResponse(JsonParseException exception) {
            // parse error, not possible to determine the request id
            return Response.ok(new JSONRPCErrorResponse(new JSONParseError())).type(MediaType.APPLICATION_JSON).build();
        }

    }

    @Provider
    public static class JsonMappingExceptionMapper implements ExceptionMapper<JsonMappingException> {

        public JsonMappingExceptionMapper(){
        }

        @Override
        public Response toResponse(JsonMappingException exception) {
            if (exception.getCause() instanceof JsonParseException) {
                return Response.ok(new JSONRPCErrorResponse(new JSONParseError())).type(MediaType.APPLICATION_JSON).build();
            } else if (exception instanceof MethodNotFoundJsonMappingException) {
                Object id = ((MethodNotFoundJsonMappingException) exception).getId();
                return Response.ok(new JSONRPCErrorResponse(id, new MethodNotFoundError()))
                        .type(MediaType.APPLICATION_JSON).build();
            } else if (exception instanceof InvalidParamsJsonMappingException) {
                Object id = ((InvalidParamsJsonMappingException) exception).getId();
                return Response.ok(new JSONRPCErrorResponse(id, new InvalidParamsError()))
                        .type(MediaType.APPLICATION_JSON).build();
            } else if (exception instanceof IdJsonMappingException) {
                Object id = ((IdJsonMappingException) exception).getId();
                return Response.ok(new JSONRPCErrorResponse(id, new InvalidRequestError()))
                        .type(MediaType.APPLICATION_JSON).build();
            }
            // not possible to determine the request id
            return Response.ok(new JSONRPCErrorResponse(new InvalidRequestError())).type(MediaType.APPLICATION_JSON).build();
        }

    }
}