package org.wildfly.extras.a2a.examples.simple.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import io.a2a.A2A;
import io.a2a.client.Client;
import io.a2a.client.ClientBuilder;
import io.a2a.client.ClientEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.http.A2ACardResolver;
import io.a2a.client.transport.grpc.GrpcTransport;
import io.a2a.client.transport.grpc.GrpcTransportConfigBuilder;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import io.a2a.client.transport.rest.RestTransport;
import io.a2a.client.transport.rest.RestTransportConfigBuilder;
import io.a2a.spec.A2AClientError;
import io.a2a.spec.A2AClientException;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TextPart;
import io.a2a.spec.TransportProtocol;
import io.grpc.ManagedChannelBuilder;

public class SimpleExampleClient implements AutoCloseable {
    private final Client client;

    public SimpleExampleClient(String protocol) throws A2AClientError, A2AClientException {
        AgentCard agentCard = new A2ACardResolver("http://localhost:8080").getAgentCard();

        ClientConfig config = new ClientConfig.Builder()
                .setAcceptedOutputModes(List.of("text"))
                .setUseClientPreference(true)
                .build();

        ClientBuilder clientBuilder = Client.builder(agentCard)
                .clientConfig(config);

        TransportProtocol prot = TransportProtocol.fromString(protocol);
        switch (prot) {
            case JSONRPC -> clientBuilder.withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder());
            case HTTP_JSON -> clientBuilder.withTransport(RestTransport.class, new RestTransportConfigBuilder());
            case GRPC -> clientBuilder.withTransport(
                    GrpcTransport.class,
                    new GrpcTransportConfigBuilder().channelFactory(
                            target -> {
                                // Remove http:// or https:// prefix for gRPC
                                return ManagedChannelBuilder.forTarget(target).usePlaintext().build();
                            }));
        }
        client = clientBuilder.build();
    }

    public String sayHello(String name) throws Exception {
        Message message = A2A.toUserMessage(name);

        final CompletableFuture<String> response = new CompletableFuture<>();

        //CompletableFuture
        BiConsumer<ClientEvent, AgentCard> consumer = (event, agentCard) -> {
            if (event instanceof TaskEvent taskEvent) {
                Task task = taskEvent.getTask();
                StringBuilder sb = new StringBuilder();
                if (task.getArtifacts() != null) {
                    for (Artifact a : task.getArtifacts()) {
                        for (Part<?> part : a.parts()) {
                            if (part instanceof TextPart textPart) {
                                sb.append(textPart.getText());
                            }
                        }
                    }
                }
                response.complete(sb.toString());
            } else {
                response.completeExceptionally(new IllegalStateException("Expected a TaskEvent"));
            }
        };
        List<BiConsumer<ClientEvent, AgentCard>> consumers = new ArrayList<>();


        client.sendMessage(message, Collections.singletonList(consumer), null, null);
        return response.get(10, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception {
        client.close();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalStateException("Usage: SimpleExampleClient <protocol> <name>");
        }
        try (SimpleExampleClient client = new SimpleExampleClient(args[0])) {
            String response = client.sayHello(args[1]);
            System.out.println("Agent responds: " + response);
        }
        System.exit(0);
    }
}
