/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extras.a2a.server.apps.rest;

import java.io.PrintWriter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SSESubscriber implements Flow.Subscriber<String> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SSESubscriber.class);
    // Hook so testing can wait until the async Subscription is subscribed.
    private static volatile Runnable streamingIsSubscribedRunnable;

    @SuppressWarnings("unused") // Stored for potential future use (e.g., cancellation)
    private Flow.Subscription subscription;

    private final AtomicLong eventId = new AtomicLong(0);
    private final CompletableFuture<Void> streamingComplete;
    private final PrintWriter writer;

    public SSESubscriber(CompletableFuture<Void> streamingComplete, PrintWriter writer) {
        this.streamingComplete = streamingComplete;
        this.writer = writer;
    }

    public static void setStreamingIsSubscribedRunnable(Runnable streamingIsSubscribedRunnable) {
        SSESubscriber.streamingIsSubscribedRunnable = streamingIsSubscribedRunnable;
    }

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
    public void onNext(String item) {
        LOGGER.debug("Custom SSE subscriber onNext called with item: {}", item);
        try {
            // Format as proper SSE event (matching Quarkus format)
            long id = eventId.getAndIncrement();
            writer.write("data: " + item + "\n");
            writer.write("id: " + id + "\n");
            writer.write("\n"); // Empty line to complete the event
            writer.flush();
            LOGGER.info("Custom SSE event sent successfully with id: {}", id);
        } catch (Exception e) {
            LOGGER.error("Error writing SSE event: {}", e.getMessage(), e);
            onError(e);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        LOGGER.debug("Custom SSE subscriber onError called: {}", throwable.getMessage(), throwable);
        streamingComplete.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        LOGGER.debug("Custom SSE subscriber onComplete called");
        streamingComplete.complete(null);
    }
}
