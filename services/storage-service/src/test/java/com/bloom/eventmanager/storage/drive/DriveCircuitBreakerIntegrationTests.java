package com.bloom.eventmanager.storage.drive;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.storage.mongo.DriveTokenRepository;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;
import com.bloom.eventmanager.storage.oauth.DriveNotAuthenticatedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drive is the one external dependency storage-service cannot degrade around, so
 * the breaker's job is to stop hammering it -- and to stop the sync loop blocking
 * on connect timeouts -- once it is clearly down.
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
        "resilience4j.circuitbreaker.configs.default.waitDurationInOpenState=60s"
})
class DriveCircuitBreakerIntegrationTests {

    @MockitoBean
    private DriveAuthManager auth;

    @MockitoBean
    private DriveTokenRepository tokens;

    @Autowired
    private GoogleDriveClient client;

    @Autowired
    private CircuitBreakerRegistry registry;

    /** The breaker is a context singleton; each test starts from CLOSED whatever ran before. */
    @BeforeEach
    void closeTheBreaker() {
        registry.circuitBreaker("drive").reset();
    }

    @Test
    void opensAfterRepeatedDriveFailuresAndThenFailsFast() {
        when(auth.credential()).thenThrow(new RuntimeException("connection reset"));

        assertThatThrownBy(() -> client.listFolders("root")).isInstanceOf(ExternalServiceException.class);
        assertThatThrownBy(() -> client.listFolders("root")).isInstanceOf(ExternalServiceException.class);

        assertThat(registry.circuitBreaker("drive").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> client.listFolders("root")).isInstanceOf(ExternalServiceException.class);
        verify(auth, times(2)).credential(); // the third call never reached Drive
    }

    /** Every failure used to read "unavailable (circuit open)", including the two that opened it. */
    @Test
    void theMessageSaysCircuitOpenOnlyOnceTheBreakerHasActuallyOpened() {
        when(auth.credential()).thenThrow(new IllegalStateException("connection reset"));

        assertThatThrownBy(() -> client.listFolders("root"))
                .hasMessage("Google Drive list folders unavailable (IllegalStateException)");
        assertThatThrownBy(() -> client.listFolders("root"))
                .hasMessage("Google Drive list folders unavailable (IllegalStateException)");

        assertThatThrownBy(() -> client.listFolders("root"))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessage("Google Drive list folders unavailable (circuit open)");
    }

    /**
     * Our own diagnosis passes through, not only ExternalServiceException: a Drive session
     * that is not (or no longer) authenticated is the 401 with an authUrl that sends the
     * SPA to re-auth, and relabelling it as Drive being unavailable hid that.
     */
    @Test
    void aDriveNotAuthenticatedFailureReachesTheCallerUnwrapped() {
        when(auth.credential()).thenThrow(new DriveNotAuthenticatedException());

        assertThatThrownBy(() -> client.listFolders("root")).isInstanceOf(DriveNotAuthenticatedException.class);
    }
}
