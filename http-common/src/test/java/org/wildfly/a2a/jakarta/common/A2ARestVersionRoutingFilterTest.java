package org.wildfly.a2a.jakarta.common;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.a2aproject.sdk.common.A2AHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, RuntimeDelegateExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class A2ARestVersionRoutingFilterTest {

    @Mock
    Instance<A2AVersionProvider> allVersionProviders;

    @Mock
    TenantHolder tenantHolder;

    @InjectMocks
    A2ARestVersionRoutingFilter filter;

    private void setupProvider(A2AVersionProvider... providers) {
        when(allVersionProviders.iterator()).thenAnswer(inv -> List.of(providers).iterator());
    }

    @Test
    void wellKnownPath_isSkipped() throws IOException {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/.well-known/agent-card.json");

        filter.filter(ctx);

        verify(ctx, never()).setRequestUri(any(), any());
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void internalA2aPath_isSkipped() throws IOException {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/a2a_rest_v1.0/tasks");

        filter.filter(ctx);

        verify(ctx, never()).setRequestUri(any(), any());
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void noVersionHeader_onlyRootBasePath_isSkipped() throws IOException {
        setupProvider(TestProviders.provider("1.0", true, "/a2a_rest_v1.0", "/"));

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/tasks/123");
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn(null);

        filter.filter(ctx);

        verify(ctx, never()).setRequestUri(any(), any());
    }

    @Test
    void multipleProviders_noDefault_nullVersionHeader_isSkipped() throws IOException {
        setupProvider(
                TestProviders.provider("1.0", false, "/a2a_rest_v1.0", "/"),
                TestProviders.provider("0.3", false, "/a2a_rest_v0.3", "/v1"));

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/unknown-path");
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn(null);

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
        verify(ctx, never()).setRequestUri(any(), any());
    }

    @Test
    void unknownVersionHeader_abortsWithBadRequest() throws IOException {
        setupProvider(TestProviders.provider("1.0", true, "/a2a_rest_v1.0", "/", Set.of("tasks")));

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/tasks/123");
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn("99.0");

        filter.filter(ctx);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(responseCaptor.capture());
        assertEquals(400, responseCaptor.getValue().getStatus());
    }

    @Test
    void errorResponse_containsEscapedVersionHeader() throws IOException {
        setupProvider(TestProviders.provider("1.0", true, "/a2a_rest_v1.0", "/", Set.of("tasks")));

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/tasks/123");
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn("bad\"version");

        filter.filter(ctx);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(responseCaptor.capture());
        String body = responseCaptor.getValue().getEntity().toString();
        assertFalse(body.contains("\"version\""), "Unescaped quote must not appear in JSON body");
        assertTrue(body.contains("\\\""), "Quote must be JSON-escaped in error body");
    }

    @Test
    void versionHeader_nonA2aPath_isSkipped() throws IOException {
        // Regression test: a2a-version header on non-A2A path (e.g. test utility endpoint)
        // must not be rerouted to the versioned internal endpoint.
        setupProvider(TestProviders.provider("0.3", false, "/a2a_rest_v0.3", "/v1"));

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/test/task/task-123");
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn("0.3");

        filter.filter(ctx);

        verify(ctx, never()).setRequestUri(any(), any());
        verify(ctx, never()).abortWith(any());
    }

    @Test
    void multipleProviders_noDefault_unknownVersion_abortsWithBadRequest() throws IOException {
        setupProvider(
                TestProviders.provider("1.0", false, "/a2a_rest_v1.0", "/", Set.of("tasks")),
                TestProviders.provider("0.3", false, "/a2a_rest_v0.3", "/v1"));

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/tasks/123");
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn("99.0");

        filter.filter(ctx);

        verify(ctx).abortWith(any(Response.class));
    }

    @Test
    void versionHeader_a2aPath_pathCheckPasses() throws IOException {
        // Regression test: A2A-Version header on a path matching a root provider's prefix
        // must NOT skip routing. Using an unknown version here so the filter aborts with 400
        // (proving the path check passed; if it had returned early, abortWith would not be called).
        setupProvider(TestProviders.provider("1.0", false, "/a2a_rest_v1.0", "/", Set.of("tasks", "message")));

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/message:send");
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn("99.0");

        filter.filter(ctx);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(responseCaptor.capture());
        assertEquals(400, responseCaptor.getValue().getStatus());
    }

    @Test
    void knownRestBasePath_isNotTreatedAsTenant() throws IOException {
        // Regression test for commit 48aa443: A2ARestVersionRoutingFilter extracted the tenant before
        // checking known versioned REST base paths, so /v1/... (v0.3 REST base) had its /v1 segment
        // mistakenly stripped as a tenant, breaking all compat-0.3 REST routing.
        setupProvider(
                TestProviders.provider("0.3", false, "/a2a_rest_v0.3", "/v1"),
                TestProviders.provider("1.0", false, "/a2a_rest_v1.0", "/"));

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/v1/tasks/abc");
        when(ctx.getHeaderString(A2AHeaders.A2A_VERSION)).thenReturn(null);

        filter.filter(ctx);

        // /v1 is a version base path, not a tenant — tenantHolder must not be called with it
        verify(tenantHolder, never()).setTenant(any());
        // Routing proceeded (matched known base path /v1); aborts 400 because no default in this setup
        verify(ctx).abortWith(any(Response.class));
    }
}
