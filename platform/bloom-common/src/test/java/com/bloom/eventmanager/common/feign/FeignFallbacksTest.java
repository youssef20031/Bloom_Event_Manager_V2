package com.bloom.eventmanager.common.feign;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import feign.FeignException;
import feign.Request;
import feign.RetryableException;

import com.bloom.eventmanager.common.error.ServiceUnavailableException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The passthrough is the whole point: without it, registering a Feign fallback makes
 * every {@code catch (FeignException.NotFound …)} / {@code catch (FeignException.Unauthorized …)}
 * in the callers unreachable. These tests pin both directions, so swapping the branch
 * or widening the status range fails here rather than in production.
 */
class FeignFallbacksTest {

    private static final Request REQUEST = Request.create(
            "GET", "/api/v1/drive-files/f1", Map.of(), new byte[0], StandardCharsets.UTF_8);

    private static final java.util.function.Supplier<RuntimeException> UNAVAILABLE =
            () -> new ServiceUnavailableException("peer unavailable (circuit open)", null);

    @Test
    void a404IsHandedBackUnchangedSoTheCallersNotFoundCatchStillFires() {
        FeignException.NotFound notFound = new FeignException.NotFound("Not Found", REQUEST, new byte[0], Map.of());

        assertThat(FeignFallbacks.failure(notFound, UNAVAILABLE)).isSameAs(notFound);
    }

    @Test
    void a401IsHandedBackUnchangedSoDriveNotAuthenticatedSurvives() {
        FeignException.Unauthorized unauthorized =
                new FeignException.Unauthorized("Unauthorized", REQUEST, new byte[0], Map.of());

        assertThat(FeignFallbacks.failure(unauthorized, UNAVAILABLE)).isSameAs(unauthorized);
    }

    @Test
    void a500IsTranslatedBecauseThePeerIsGenuinelyFailing() {
        FeignException.InternalServerError error =
                new FeignException.InternalServerError("Boom", REQUEST, new byte[0], Map.of());

        assertThat(FeignFallbacks.failure(error, UNAVAILABLE))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void aConnectFailureIsTranslatedEvenThoughRetryableExceptionExtendsFeignException() {
        // Feign's errorExecuting() builds this with status -1, which must not read as 4xx.
        Long nonRetryable = null; // disambiguates the Date/Long overloads, as Feign's own code does
        RetryableException connectFailure = new RetryableException(
                -1, "Connection refused", Request.HttpMethod.GET, new IOException("refused"), nonRetryable, REQUEST);

        assertThat(connectFailure).isInstanceOf(FeignException.class);
        assertThat(FeignFallbacks.failure(connectFailure, UNAVAILABLE))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void anOpenBreakerOrAnyNonFeignThrowableIsTranslated() {
        assertThat(FeignFallbacks.failure(new IllegalStateException("CircuitBreaker 'x' is OPEN"), UNAVAILABLE))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(FeignFallbacks.failure(null, UNAVAILABLE))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    // ── describe: what the fallback's message says went wrong ──────────────────
    // Every fallback used to say "unavailable (circuit open)" whatever the cause, which
    // sent anyone reading a 503 after a refused connection or a timeout to look at the
    // breaker. "circuit open" now means the breaker really rejected the call.

    @Test
    void onlyARealCallNotPermittedExceptionIsDescribedAsCircuitOpen() {
        assertThat(FeignFallbacks.describe(openBreakerRejection())).isEqualTo("circuit open");
        // the executor / time-limiter decorations can wrap it
        assertThat(FeignFallbacks.describe(new ExecutionException(openBreakerRejection())))
                .isEqualTo("circuit open");
        // judged by type, never by message text
        assertThat(FeignFallbacks.describe(new IllegalStateException("CircuitBreaker 'x' is OPEN")))
                .isNotEqualTo("circuit open");
    }

    @Test
    void aRefusedConnectionOrUnknownHostIsUnreachable() {
        assertThat(FeignFallbacks.describe(retryable(new ConnectException("Connection refused"))))
                .isEqualTo("unreachable");
        assertThat(FeignFallbacks.describe(retryable(new UnknownHostException("knowledge-service"))))
                .isEqualTo("unreachable");
    }

    @Test
    void aReadTimeoutOrTheTimeLimitersTimeoutIsTimedOut() {
        assertThat(FeignFallbacks.describe(retryable(new SocketTimeoutException("Read timed out"))))
                .isEqualTo("timed out");
        assertThat(FeignFallbacks.describe(new TimeoutException("TimeLimiter 'x' recorded a timeout exception")))
                .isEqualTo("timed out");
    }

    @Test
    void aServerErrorResponseIsDescribedByItsStatus() {
        assertThat(FeignFallbacks.describe(
                new FeignException.ServiceUnavailable("Unavailable", REQUEST, new byte[0], Map.of())))
                .isEqualTo("HTTP 503");
        assertThat(FeignFallbacks.describe(
                new FeignException.InternalServerError("Boom", REQUEST, new byte[0], Map.of())))
                .isEqualTo("HTTP 500");
    }

    @Test
    void anythingElseIsNamedByTypeAndItsMessageNeverLeaks() {
        // The description ends up in a ProblemDetail; driver messages can carry credentials.
        String description = FeignFallbacks.describe(
                new IllegalStateException("mongodb://admin:hunter2@mongo:27017 refused"));

        assertThat(description).isEqualTo("IllegalStateException").doesNotContain("hunter2");
        assertThat(FeignFallbacks.describe(retryable(new IOException("Connection reset"))))
                .isEqualTo("I/O error");
        assertThat(FeignFallbacks.describe(null)).isEqualTo("cause unknown");
    }

    @Test
    void theUnavailableMessageNamesThePeerAndTheCause() {
        assertThat(FeignFallbacks.unavailableMessage("ai-service", openBreakerRejection()))
                .isEqualTo("ai-service unavailable (circuit open)");
        assertThat(FeignFallbacks.unavailableMessage("ai-service", retryable(new ConnectException("refused"))))
                .isEqualTo("ai-service unavailable (unreachable)");
    }

    private static CallNotPermittedException openBreakerRejection() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("peer");
        breaker.transitionToOpenState();
        return CallNotPermittedException.createCallNotPermittedException(breaker);
    }

    /** The shape Feign's errorExecuting() gives an I/O failure: status -1, the IOException as cause. */
    private static RetryableException retryable(IOException cause) {
        Long nonRetryable = null;
        return new RetryableException(-1, cause.getMessage(), Request.HttpMethod.POST, cause, nonRetryable, REQUEST);
    }

    @Test
    void theClientErrorRangeIsExactlyFourHundredToFourNinetyNine() {
        assertThat(FeignFallbacks.isClientError(399)).isFalse();
        assertThat(FeignFallbacks.isClientError(400)).isTrue();
        assertThat(FeignFallbacks.isClientError(499)).isTrue();
        assertThat(FeignFallbacks.isClientError(500)).isFalse();
        assertThat(FeignFallbacks.isClientError(-1)).isFalse();
    }
}
