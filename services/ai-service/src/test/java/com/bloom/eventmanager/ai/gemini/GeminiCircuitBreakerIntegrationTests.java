package com.bloom.eventmanager.ai.gemini;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.bloom.eventmanager.common.error.ExternalServiceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "bloom.ai.api-key=test-key",
        "bloom.security.internal-token=test-token",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
        "resilience4j.circuitbreaker.configs.default.slidingWindowSize=2",
        "resilience4j.circuitbreaker.configs.default.minimumNumberOfCalls=2",
        "resilience4j.circuitbreaker.configs.default.failureRateThreshold=50",
        "resilience4j.circuitbreaker.configs.default.waitDurationInOpenState=60s"
})
class GeminiCircuitBreakerIntegrationTests {

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @Autowired
    private GeminiEmbeddingService service;

    @Autowired
    private CircuitBreakerRegistry registry;

    @Test
    void opensAfterRepeatedGeminiFailuresAndThenFailsFast() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("503 UNAVAILABLE"));

        assertThatThrownBy(() -> service.embed("a")).isInstanceOf(ExternalServiceException.class);
        assertThatThrownBy(() -> service.embed("b")).isInstanceOf(ExternalServiceException.class);

        assertThat(registry.circuitBreaker("gemini").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> service.embed("c")).isInstanceOf(ExternalServiceException.class);
        verify(embeddingModel, times(2)).embed(anyString()); // third call never reached Gemini
    }
}
