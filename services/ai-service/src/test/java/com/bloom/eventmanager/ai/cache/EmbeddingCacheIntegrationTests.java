package com.bloom.eventmanager.ai.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "bloom.security.internal-token=test-token",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
        "bloom.ai.api-key=test-key",
        "bloom.ai.embedding-model=gemini-embedding-001",
        "bloom.ai.cache-key-prefix=bloom:"
})
class EmbeddingCacheIntegrationTests {

    // Boot infers the Redis connection from the "redis" name; no Redis-specific module needed.
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private EmbeddingCache cache;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void missesOnUnknownText() {
        assertThat(cache.get("never seen")).isEmpty();
    }

    @Test
    void roundTripsThroughRedis() {
        cache.put("team building", new float[] { 1f, 0.5f, -0.25f });

        assertThat(cache.get("team building")).hasValueSatisfying(v -> assertThat(v).containsExactly(1f, 0.5f, -0.25f));
    }

    @Test
    void usesV1sKeyLayoutSoExistingEntriesAreReused() {
        // What V1 would have written for the same text and model.
        redisTemplate.opsForValue().set("bloom:embed:gemini-embedding-001:" + EmbeddingCodec.hashText("legacy text"),
                "AACAPwAAAD8=");

        assertThat(cache.get("legacy text")).hasValueSatisfying(v -> assertThat(v).containsExactly(1f, 0.5f));
        assertThat(cache.key("legacy text")).startsWith("bloom:embed:gemini-embedding-001:").hasSize(
                "bloom:embed:gemini-embedding-001:".length() + 32);
    }

    @Test
    void storesWithoutExpiry() {
        cache.put("immutable", new float[] { 0.1f });

        assertThat(redisTemplate.getExpire(cache.key("immutable"))).isEqualTo(-1L);
    }

    @Test
    void treatsCorruptEntriesAsAMiss() {
        redisTemplate.opsForValue().set(cache.key("corrupt"), "not base64 !!!");

        assertThat(cache.get("corrupt")).isEmpty();
    }
}
