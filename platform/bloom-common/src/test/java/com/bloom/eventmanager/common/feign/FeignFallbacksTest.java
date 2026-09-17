package com.bloom.eventmanager.common.feign;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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

    @Test
    void theClientErrorRangeIsExactlyFourHundredToFourNinetyNine() {
        assertThat(FeignFallbacks.isClientError(399)).isFalse();
        assertThat(FeignFallbacks.isClientError(400)).isTrue();
        assertThat(FeignFallbacks.isClientError(499)).isTrue();
        assertThat(FeignFallbacks.isClientError(500)).isFalse();
        assertThat(FeignFallbacks.isClientError(-1)).isFalse();
    }
}
