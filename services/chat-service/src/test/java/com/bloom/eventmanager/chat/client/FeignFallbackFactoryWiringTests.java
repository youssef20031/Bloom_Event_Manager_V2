package com.bloom.eventmanager.chat.client;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.bloom.eventmanager.chat.client.dto.ChatBackupRequest;
import com.bloom.eventmanager.chat.client.dto.GenerateRequest;
import com.bloom.eventmanager.chat.client.dto.RetrieveRequest;
import com.bloom.eventmanager.common.error.ServiceUnavailableException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wiring the per-factory unit tests cannot see.
 *
 * <p>{@code spring.cloud.openfeign.circuitbreaker.enabled} lives only in
 * {@code config-repo/application.yml}, which the suite never reads, so every other test
 * in this module builds its Feign proxies <em>without</em> a circuit breaker and never
 * resolves a {@code fallbackFactory} bean at all. A {@code fallbackFactory} naming a
 * class that is not a {@code @Component}, or whose {@code FallbackFactory<T>} type
 * argument does not match the client, fails at proxy-construction time — which in
 * production means the service does not start.
 *
 * <p>Turning the flag on here builds all three of chat-service's proxies the way
 * production does and drives a real call: with discovery off there is no instance to
 * load-balance to, so the invocation fails, the breaker routes it to the factory, and the
 * browser-facing 503 contract is what comes back.
 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "bloom.security.internal-token=test-token",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
        "spring.cloud.openfeign.circuitbreaker.enabled=true"
})
class FeignFallbackFactoryWiringTests {

    @Autowired
    private AiClient ai;

    @Autowired
    private RetrievalClient retrieval;

    @Autowired
    private StorageClient storage;

    @Test
    void everyClientResolvesItsFallbackFactoryAndFailsAsA503() {
        assertThatThrownBy(() -> ai.generate(new GenerateRequest("x", null)))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("ai-service");

        assertThatThrownBy(() -> retrieval.retrieve(new RetrieveRequest("x", 15, false)))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("retrieval-service");

        assertThatThrownBy(() -> storage.uploadChatBackup(new ChatBackupRequest("s1", "YQ==")))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("storage-service");
    }
}
