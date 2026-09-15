package com.bloom.eventmanager.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.import=",
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false"
        })
@AutoConfigureWebTestClient
class LegacyOAuthRedirectControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void redirectsLegacyGoogleCallbackPreservingQueryParameters() {
        webTestClient.get()
                .uri("/api/rag/auth/google/callback?code=abc123&scope=drive.readonly")
                .exchange()
                .expectStatus().isEqualTo(307)
                .expectHeader()
                .valueEquals("Location",
                        "/api/v1/rag/sync/auth/callback?code=abc123&scope=drive.readonly");
    }

    @Test
    void redirectsLegacyGoogleCallbackWithNoQueryString() {
        webTestClient.get()
                .uri("/api/rag/auth/google/callback")
                .exchange()
                .expectStatus().isEqualTo(307)
                .expectHeader().valueEquals("Location", "/api/v1/rag/sync/auth/callback");
    }
}
