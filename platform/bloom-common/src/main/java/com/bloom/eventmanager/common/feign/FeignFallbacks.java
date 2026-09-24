package com.bloom.eventmanager.common.feign;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
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

    /** Thrown by an open (or saturated half-open) breaker instead of making the call. */
    private static final String CALL_NOT_PERMITTED = "io.github.resilience4j.circuitbreaker.CallNotPermittedException";

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

    /**
     * {@code "<peer> unavailable (<why>)"}, the message every fallback's unavailability
     * exception carries; {@code why} is {@link #describe}.
     */
    public static String unavailableMessage(String peer, Throwable cause) {
        return peer + " unavailable (" + describe(cause) + ")";
    }

    /**
     * A short phrase for why a call to a peer failed: {@code "circuit open"} only when the
     * breaker really rejected the call, otherwise what the transport saw --
     * {@code "timed out"}, {@code "unreachable"}, {@code "HTTP 503"}, {@code "I/O error"},
     * or failing all of those the exception's simple class name. Every wrapper in the
     * cause chain is looked through, because the breaker's executor and time limiter can
     * wrap what Feign threw.
     *
     * <p>Never the exception's own message: this text reaches the client in a
     * ProblemDetail, and driver messages routinely embed hosts and credentials.
     *
     * <p>The open breaker is recognised by class name, so bloom-common needs no
     * Resilience4j dependency; the services that route calls through a breaker have it.
     */
    public static String describe(Throwable cause) {
        if (cause == null) {
            return "cause unknown";
        }
        List<Throwable> chain = causeChain(cause);
        if (chain.stream().anyMatch(t -> CALL_NOT_PERMITTED.equals(t.getClass().getName()))) {
            return "circuit open";
        }
        if (chain.stream().anyMatch(t -> t instanceof TimeoutException
                || t instanceof SocketTimeoutException || t instanceof HttpTimeoutException)) {
            return "timed out";
        }
        if (chain.stream().anyMatch(t -> t instanceof ConnectException
                || t instanceof UnknownHostException || t instanceof NoRouteToHostException)) {
            return "unreachable";
        }
        for (Throwable t : chain) {
            if (t instanceof FeignException feign && feign.status() > 0) {
                return "HTTP " + feign.status();
            }
        }
        if (chain.stream().anyMatch(IOException.class::isInstance)) {
            return "I/O error";
        }
        String type = cause.getClass().getSimpleName();
        return type.isEmpty() ? cause.getClass().getName() : type;
    }

    /** The throwable and its causes, outermost first, stopping at a cycle. */
    private static List<Throwable> causeChain(Throwable cause) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = cause; current != null && seen.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    /** True for a well-formed HTTP client-error status; false for Feign's -1 "could not execute". */
    public static boolean isClientError(int status) {
        return status >= 400 && status < 500;
    }
}
