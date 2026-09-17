package com.bloom.eventmanager.storage.client;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import feign.FeignException;
import feign.Request;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.common.feign.FeignFallbacks;
import com.bloom.eventmanager.storage.mongo.DriveTokenRepository;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;

import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The half of the I2 fix that a unit test cannot reach.
 *
 * <p>{@code Resilience4JCircuitBreaker.getAndApplyFallback} catches <em>outside</em> the
 * decorated supplier, so the breaker has already recorded the error by the time the
 * fallback runs: the fallback factory's 4xx passthrough alone does not stop a run of
 * legitimate 404s from opening the breaker. That is what
 * {@code ignoreExceptions: feign.FeignException$FeignClientException} in
 * {@code config-repo/storage-service.yml} is for, and it is only worth anything if the
 * class name really resolves during property binding and the predicate really sees the
 * unwrapped FeignException through the executor and time-limiter decorations.
 *
 * <p>This drives the real {@code CircuitBreakerFactory} from the context over exactly the
 * call shape {@code FeignCircuitBreakerInvocationHandler} uses — a supplier that throws
 * and a fallback function built from {@link FeignFallbacks} — and asserts the resulting
 * breaker state. The window is narrowed to two calls so four failures are unambiguous.
 *
 * <p>The ignoreExceptions property below mirrors {@code config-repo/storage-service.yml};
 * the suite is hermetic and never reads the Config Server.
 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "bloom.security.internal-token=test-token",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
        "spring.kafka.listener.auto-startup=false",
        "spring.data.mongodb.auto-index-creation=false",
        "resilience4j.circuitbreaker.configs.default.slidingWindowSize=2",
        "resilience4j.circuitbreaker.configs.default.minimumNumberOfCalls=2",
        "resilience4j.circuitbreaker.configs.default.failureRateThreshold=50",
        "resilience4j.circuitbreaker.configs.default.waitDurationInOpenState=60s",
        "resilience4j.circuitbreaker.configs.default.ignoreExceptions=feign.FeignException$FeignClientException"
})
class FeignClientErrorBreakerIntegrationTests {

    private static final Request REQUEST = Request.create(
            "GET", "/api/v1/drive-files/f1", Map.of(), new byte[0], StandardCharsets.UTF_8);

    @MockitoBean
    private DriveAuthManager auth;

    @MockitoBean
    private DriveTokenRepository tokens;

    @Autowired
    private CircuitBreakerFactory<?, ?> breakers;

    @Autowired
    private CircuitBreakerRegistry registry;

    @Test
    void aRunOf404sNeitherOpensTheBreakerNorLosesTheNotFound() {
        FeignException.NotFound notFound = new FeignException.NotFound("Not Found", REQUEST, new byte[0], Map.of());

        for (int i = 0; i < 4; i++) {
            // The caller's catch (FeignException.NotFound) has to keep working...
            assertThatThrownBy(() -> invoke("ignore-4xx", notFound)).isSameAs(notFound);
        }

        // ...and four of them must not take knowledge-service out of rotation for 60s.
        assertThat(registry.circuitBreaker("ignore-4xx").getState()).isEqualTo(State.CLOSED);
    }

    @Test
    void a5xxStillCountsAndStillOpensTheBreaker() {
        FeignException.InternalServerError boom =
                new FeignException.InternalServerError("Boom", REQUEST, new byte[0], Map.of());

        assertThatThrownBy(() -> invoke("record-5xx", boom)).isInstanceOf(ExternalServiceException.class);
        assertThatThrownBy(() -> invoke("record-5xx", boom)).isInstanceOf(ExternalServiceException.class);

        assertThat(registry.circuitBreaker("record-5xx").getState()).isEqualTo(State.OPEN);
    }

    /** The call shape FeignCircuitBreakerInvocationHandler uses: supplier + fallback function. */
    private Object invoke(String breakerName, RuntimeException thrown) {
        Supplier<Object> supplier = () -> {
            throw thrown;
        };
        return breakers.create(breakerName).run(supplier, cause -> {
            throw FeignFallbacks.failure(cause,
                    () -> new ExternalServiceException("knowledge-service unavailable (circuit open)", cause));
        });
    }
}
