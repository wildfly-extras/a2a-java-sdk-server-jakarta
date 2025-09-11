package org.wildfly.extras.a2a.examples.simple;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.Artifact;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Part;
import io.a2a.spec.TaskNotCancelableError;
import io.a2a.spec.TextPart;

@ApplicationScoped
public class SimpleExampleAgentExecutorProducer {

    @Produces
    public AgentExecutor mockExecutor() {
        return new SimpleExampleAgentExecutor();
    }

    private static class SimpleExampleAgentExecutor implements AgentExecutor {
        @Override
        public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
            TaskUpdater updater = new TaskUpdater(context, eventQueue);

            // Signal we've started working
            updater.startWork();

            // Get the name sent in the user's message
            List<Part<?>> partsList = context.getMessage().getParts();
            List<TextPart> textParts = partsList.stream()
                    .filter(p -> p.getKind() == Part.Kind.TEXT)
                    .map(p -> (TextPart) p)
                    .toList();
            String name = textParts.get(textParts.size() - 1).getText();

            // Simulate doing work with the LLM, and adding that as an artifact.
            // In this case we just add "Hello <name>" to the list of aritfacts
            String response = "Hello " + name;
            updater.addArtifact(Collections.singletonList(new TextPart(response)), null, "response", null);

            // We have completed our simple example Task
            updater.complete();
        }

        @Override
        public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
            throw new TaskNotCancelableError();
        }
    }
}
