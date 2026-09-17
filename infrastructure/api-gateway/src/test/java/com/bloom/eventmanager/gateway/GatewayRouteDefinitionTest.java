package com.bloom.eventmanager.gateway;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class GatewayRouteDefinitionTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void definesTheKnowledgePingRoute() {
        List<String> routeIds = routeLocator.getRoutes().map(Route::getId).collectList().block();

        assertThat(routeIds).contains("knowledge-ping");
    }

    @Test
    void routesKnowledgePingThroughLoadBalancedDiscovery() {
        Route route = routeLocator.getRoutes()
                .filter(r -> "knowledge-ping".equals(r.getId()))
                .blockFirst();

        assertThat(route).isNotNull();
        assertThat(route.getUri()).hasToString("lb://knowledge-service");
    }

    @Test
    void routesRetrieveToTheRetrievalService() {
        Route route = routeLocator.getRoutes()
                .filter(r -> "retrieve".equals(r.getId()))
                .blockFirst();

        assertThat(route).isNotNull();
        assertThat(route.getUri()).hasToString("lb://retrieval-service");
    }

    /**
     * Guards the RewritePath escaping as much as the route: an unescaped
     * {@code ${rest}} is swallowed as a property placeholder, and the route
     * then fails to build rather than misbehaving at runtime.
     */
    @Test
    void routesIngestToTheIngestionService() {
        Route route = routeLocator.getRoutes()
                .filter(r -> "ingest".equals(r.getId()))
                .blockFirst();

        assertThat(route).isNotNull();
        assertThat(route.getUri()).hasToString("lb://ingestion-service");
    }

    @Test
    void routesSyncAuthAndSyncToStorageInThatOrder() {
        List<String> ids = routeLocator.getRoutes().map(Route::getId).collectList().block();

        assertThat(ids).containsSubsequence("sync-auth", "sync");
        assertThat(routeLocator.getRoutes().filter(r -> "sync".equals(r.getId())).blockFirst().getUri())
                .hasToString("lb://storage-service");
    }

    @Test
    void routesChatAndProposalSurfaces() {
        List<String> ids = routeLocator.getRoutes().map(Route::getId).collectList().block();

        assertThat(ids).contains("chat", "proposals", "generate-proposal", "event-intake");
        assertThat(routeLocator.getRoutes().filter(r -> "chat".equals(r.getId())).blockFirst().getUri())
                .hasToString("lb://chat-service");
        assertThat(routeLocator.getRoutes().filter(r -> "proposals".equals(r.getId())).blockFirst().getUri())
                .hasToString("lb://proposal-service");
        assertThat(routeLocator.getRoutes().filter(r -> "generate-proposal".equals(r.getId())).blockFirst().getUri())
                .hasToString("lb://proposal-service");
        assertThat(routeLocator.getRoutes().filter(r -> "event-intake".equals(r.getId())).blockFirst().getUri())
                .hasToString("lb://proposal-service");
    }

    /**
     * The id/uri checks above would still pass if a RewritePath regexp or
     * replacement were wrong — they only prove a route with that id exists and
     * points at the right service, not that it rewrites the incoming path
     * correctly. This drives each route's actual filter with a mock request,
     * the same way the gateway runtime would, and asserts the rewritten path.
     * generate-proposal is asserted explicitly because it is the one route
     * here where the gateway path and the service path differ.
     */
    @Test
    void rewritesRagPathsToTheirServicePaths() {
        assertThat(rewrittenPath("chat", "/api/v1/rag/chat/sessions")).isEqualTo("/api/v1/chat/sessions");
        assertThat(rewrittenPath("proposals", "/api/v1/rag/proposals/companies")).isEqualTo("/api/v1/proposals/companies");
        assertThat(rewrittenPath("generate-proposal", "/api/v1/rag/generate-proposal")).isEqualTo("/api/v1/proposals/generate");
        assertThat(rewrittenPath("event-intake", "/api/v1/rag/event-intake")).isEqualTo("/api/v1/event-intake");
    }

    /** Runs {@code routeId}'s (sole) RewritePath filter against {@code incomingPath} and returns the rewritten path. */
    private String rewrittenPath(String routeId, String incomingPath) {
        Route route = routeLocator.getRoutes().filter(r -> routeId.equals(r.getId())).blockFirst();
        assertThat(route).as("route '%s'", routeId).isNotNull();

        GatewayFilter rewrite = route.getFilters().get(0);
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(incomingPath));
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstream.set(ex);
            return Mono.empty();
        };

        rewrite.filter(exchange, chain).block();
        return downstream.get().getRequest().getURI().getPath();
    }
}
