package com.bloom.eventmanager.gateway;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

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
}
