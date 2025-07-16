package org.wildfly.extras.a2a.server.jakarta.test.utils;

import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.a2a.server.apps.common.TestUtilsBean;
import io.a2a.spec.Task;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.util.Utils;

@Path("/test")
@ApplicationScoped
public class A2ATestResource {
    @Inject
    TestUtilsBean testUtilsBean;

    private final AtomicInteger streamingSubscribedCount = new AtomicInteger(0);

    @PostConstruct
    public void init() {
        //A2AServerResource.setStreamingIsSubscribedRunnable(streamingSubscribedCount::incrementAndGet);
    }


    @POST
    @Path("/task")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveTask(String body) throws Exception {
        Task task = Utils.OBJECT_MAPPER.readValue(body, Task.class);
        testUtilsBean.saveTask(task);
        return Response.ok().build();
    }

    @GET
    @Path("/task/{taskId}")
    public Response getTask(@PathParam("taskId") String taskId) throws Exception {
        Task task = testUtilsBean.getTask(taskId);
        if (task == null) {
            return Response.status(404).build();
        }
        return Response.ok()
                .entity(Utils.OBJECT_MAPPER.writeValueAsString(task))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .build();
    }

    @DELETE
    @Path("/task/{taskId}")
    public Response deleteTask(@PathParam("taskId") String taskId) {
        Task task = testUtilsBean.getTask(taskId);
        if (task == null) {
            return Response.status(404).build();
        }
        testUtilsBean.deleteTask(taskId);
        return Response.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .build();
    }

    @POST
    @Path("/queue/ensure/{taskId}")
    public Response ensureQueue(@PathParam("taskId") String taskId) {
        testUtilsBean.ensureQueue(taskId);
        return Response.ok().build();
    }

    @POST
    @Path("/queue/enqueueTaskStatusUpdateEvent/{taskId}")
    public Response enqueueTaskStatusUpdateEvent(@PathParam("taskId") String taskId, String body) throws Exception {
        TaskStatusUpdateEvent event = Utils.OBJECT_MAPPER.readValue(body, TaskStatusUpdateEvent.class);
        testUtilsBean.enqueueEvent(taskId, event);
        return Response.ok().build();
    }

    @POST
    @Path("/queue/enqueueTaskArtifactUpdateEvent/{taskId}")
    public Response enqueueTaskArtifactUpdateEvent(@PathParam("taskId") String taskId, String body) throws Exception {
        TaskArtifactUpdateEvent event = Utils.OBJECT_MAPPER.readValue(body, TaskArtifactUpdateEvent.class);
        testUtilsBean.enqueueEvent(taskId, event);
        return Response.ok().build();
    }

    @GET
    @Path("/streamingSubscribedCount")
    @Produces(TEXT_PLAIN)
    public Response getStreamingSubscribedCount() {
        return Response.ok(String.valueOf(streamingSubscribedCount.get()), TEXT_PLAIN).build();
    }
}
