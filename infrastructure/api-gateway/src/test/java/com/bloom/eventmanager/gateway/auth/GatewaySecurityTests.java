package com.bloom.eventmanager.gateway.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.import=",
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "bloom.auth.user=bloom",
                "bloom.auth.password=correct horse battery staple",
                "bloom.auth.internal-token=internal-secret"
        })
@AutoConfigureWebTestClient
class GatewaySecurityTests {

    @Autowired
    private WebTestClient client;

    @Test
    void loginWithGoodCredentialsReturnsABearerToken() {
        client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\": \"bloom\", \"password\": \"correct horse battery staple\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tokenType").isEqualTo("Bearer")
                .jsonPath("$.expiresIn").isEqualTo(43200)
                .jsonPath("$.token").value(token -> assertThat(token.toString().split("\\.")).hasSize(3));
    }

    @Test
    void loginWithBadCredentialsIs401ProblemDetail() {
        client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\": \"bloom\", \"password\": \"nope\"}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.errorCode").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void jwksExposesOneRsaSigningKey() {
        client.get().uri("/.well-known/jwks.json").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.keys.length()").isEqualTo(1)
                .jsonPath("$.keys[0].kty").isEqualTo("RSA")
                .jsonPath("$.keys[0].use").isEqualTo("sig")
                .jsonPath("$.keys[0].kid").exists()
                .jsonPath("$.keys[0].d").doesNotExist();
    }

    @Test
    void protectedRoutesRejectAnonymousCallsWithAJsonProblem() {
        client.get().uri("/api/v1/rag/ping").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.errorCode").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void aFreshTokenGetsPastSecurity() {
        String token = client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\": \"bloom\", \"password\": \"correct horse battery staple\"}")
                .exchange().expectBody(LoginResponse.class).returnResult().getResponseBody().token();

        // No route matches this path, so the gateway answers 404 — proof the request cleared security.
        client.get().uri("/api/v1/rag/no-such-route").header("Authorization", "Bearer " + token).exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void googleCallbackPathsNeedNoToken() {
        client.get().uri("/api/rag/auth/google/callback?code=x").exchange()
                .expectStatus().isEqualTo(307);
    }

    @Test
    void healthNeedsNoToken() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    record LoginResponse(String token, String tokenType, long expiresIn) {
    }
}
