package org.wildfly.extras.a2a.server.apps.jakarta;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import io.a2a.server.ExtendedAgentCard;
import io.a2a.server.ServerCallContext;
import io.a2a.server.auth.UnauthenticatedUser;
import io.a2a.server.auth.User;
import io.a2a.server.requesthandlers.JSONRPCHandler;
import io.a2a.server.util.async.Internal;
import io.a2a.spec.AgentCard;
import io.a2a.spec.CancelTaskRequest;
import io.a2a.spec.DeleteTaskPushNotificationConfigRequest;
import io.a2a.spec.GetTaskPushNotificationConfigRequest;
import io.a2a.spec.GetTaskRequest;
import io.a2a.spec.IdJsonMappingException;
import io.a2a.spec.InvalidParamsError;
import io.a2a.spec.InvalidParamsJsonMappingException;
import io.a2a.spec.InvalidRequestError;
import io.a2a.spec.JSONErrorResponse;
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
     * Dispatches the request to the appropriate JSON-RPC handler method and returns the response.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void handleStreamingRequests(
            StreamingJSONRPCRequest<?> request, @Context SseEventSink sseEventSink,
            @Context Sse sse, @Context HttpServletRequest httpRequest,
            @Context SecurityContext securityContext) {
        ServerCallContext context = createCallContext(httpRequest, securityContext);
        LOGGER.debug("Handling streaming request");
        executor.execute(() -> processStreamingRequest(request, sseEventSink, sse, context));
        LOGGER.debug("Submitted streaming request for async processing");
    }

    /**
     * Handles incoming GET requests to the agent card endpoint.
     * Returns the agent card in JSON format.
     *
     * @return the agent card
     */
    @GET
    @Path("/.well-known/agent.json")
    @Produces(MediaType.APPLICATION_JSON)
    public AgentCard getAgentCard() {
        return jsonRpcHandler.getAgentCard();
    }

    /**
     * Handles incoming GET requests to the authenticated extended agent card endpoint.
     * Returns the agent card in JSON format.
     *
     * @return the authenticated extended agent card
     */
    @GET
    @Path("/agent/authenticatedExtendedCard")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAuthenticatedExtendedAgentCard() {
        // TODO need to add authentication for this endpoint
        // https://github.com/a2aproject/a2a-java/issues/77
        if (! jsonRpcHandler.getAgentCard().supportsAuthenticatedExtendedCard()) {
            JSONErrorResponse errorResponse = new JSONErrorResponse("Extended agent card not supported or not enabled.");
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(errorResponse).build();
        }
        if (! extendedAgentCard.isResolvable()) {
            JSONErrorResponse errorResponse = new JSONErrorResponse("Authenticated extended agent card is supported but not configured on the server.");
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(errorResponse).build();
        }
        return Response.ok(extendedAgentCard.get())
                .type(MediaType.APPLICATION_JSON)
                .build();
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
        } else {
            return generateErrorResponse(request, new UnsupportedOperationError());
        }
    }

    private void processStreamingRequest(StreamingJSONRPCRequest<?> request, SseEventSink sseEventSink, Sse sse,
                                         ServerCallContext context) {
        Flow.Publisher<? extends JSONRPCResponse<?>> publisher;
        if (request instanceof SendStreamingMessageRequest req) {
            publisher = jsonRpcHandler.onMessageSendStream(req, context);
            handleStreamingResponse(publisher, sseEventSink, sse);
        } else if (request instanceof TaskResubscriptionRequest req) {
            publisher = jsonRpcHandler.onResubscribeToTask(req, context);
            handleStreamingResponse(publisher, sseEventSink, sse);
        }
    }

    private void handleStreamingResponse(Flow.Publisher<? extends JSONRPCResponse<?>> publisher, SseEventSink sseEventSink, Sse sse) {
        publisher.subscribe(new Flow.Subscriber<JSONRPCResponse<?>>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
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

                sseEventSink.send(sse.newEventBuilder()
                        .mediaType(MediaType.APPLICATION_JSON_TYPE)
                        .data(item)
                        .build());
            }

            @Override
            public void onError(Throwable throwable) {
                // TODO
                sseEventSink.close();
            }

            @Override
            public void onComplete() {
                sseEventSink.close();
            }
        });
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

            return new ServerCallContext(user, state);
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