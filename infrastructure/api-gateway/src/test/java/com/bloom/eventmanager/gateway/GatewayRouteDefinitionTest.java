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
}
