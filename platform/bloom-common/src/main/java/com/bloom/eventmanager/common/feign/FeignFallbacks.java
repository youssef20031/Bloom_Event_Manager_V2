package com.bloom.eventmanager.common.feign;

import java.util.function.Supplier;

import feign.FeignException;

/**
 * The single rule every Bloom Feign fallback factory applies.
 *
 * <p>Spring Cloud OpenFeign's {@code FeignCircuitBreakerInvocationHandler} routes
 * <em>any</em> throwable to the fallback, including the {@link FeignException} the
 * default {@code ErrorDecoder} raises for a well-formed non-2xx response. A blanket
 * fallback therefore makes every {@code catch (FeignException.NotFound …)} and
 * {@code catch (FeignException.Unauthorized …)} in the callers unreachable, and turns
 * a peer's deliberate 401 or 404 into "the peer is down".
 *
 * <p>So: a client error (HTTP 400-499) is the peer answering correctly and is handed
 * straight back to the caller unchanged, preserving both the exception type the caller
 * catches and the response's HTTP semantics. Everything else — connection refused,
 * read timeout, a 5xx, {@code CallNotPermittedException} when the breaker is open — is
 * genuine unavailability and is translated to the error type that service already uses.
 *
 * <p>Connect failures arrive as {@code RetryableException}, which extends
 * {@code FeignException} with {@code status() == -1}, so the range check keeps them on
 * the unavailability side.
 *
 * <p>Lives in bloom-common but references Feign, which is an <em>optional</em>
 * dependency here: only the five services that declare {@code @FeignClient}s load this
 * class, and they all carry {@code spring-cloud-starter-openfeign}. Nothing
 * auto-configured refers to it, so knowledge-service and ai-service — which have no
 * Feign on the classpath — never resolve it.
 */
public final class FeignFallbacks {

    private FeignFallbacks() {
    }

    /**
     * Returns the exception a fallback should throw for {@code cause}: the original
     * {@link FeignException} when it carries a 4xx status, otherwise the supplied
     * unavailability exception.
     *
     * @param cause       the throwable the invocation handler passed to
     *                    {@code FallbackFactory.create}; may be {@code null}
     * @param unavailable produces the service's own unavailability exception
     *                    ({@code ServiceUnavailableException} for browser-facing
     *                    services, {@code ExternalServiceException} elsewhere)
     */
    public static RuntimeException failure(Throwable cause, Supplier<RuntimeException> unavailable) {
        if (cause instanceof FeignException feign && isClientError(feign.status())) {
            return feign;
        }
        return unavailable.get();
    }

    /** True for a well-formed HTTP client-error status; false for Feign's -1 "could not execute". */
    public static boolean isClientError(int status) {
        return status >= 400 && status < 500;
    }
}
