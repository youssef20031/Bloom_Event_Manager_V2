package com.bloom.eventmanager.chat.client;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.chat.client.dto.RetrieveRequest;
import com.bloom.eventmanager.chat.client.dto.RetrieveResponse;
import com.bloom.eventmanager.common.error.ServiceUnavailableException;
import com.bloom.eventmanager.common.feign.FeignFallbacks;

/**
 * Runs when retrieval-service is down or its breaker is open. chat-service is
 * called directly by the browser, so throwing (rather than returning empty
 * chunks) surfaces as a clean 503 ProblemDetail instead of a hung request or
 * a 502 — the Event Advisor fails fast and obviously.
 *
 * <p>A {@code FallbackFactory} rather than a plain fallback because the invocation
 * handler routes <em>every</em> throwable here, including the {@code FeignException}
 * raised for a well-formed 4xx. {@link FeignFallbacks#failure} hands a 4xx back to the
 * caller unchanged so the peer's own HTTP semantics survive, and translates only
 * genuine unavailability.
 */
@Component
public class RetrievalClientFallbackFactory implements FallbackFactory<RetrievalClient> {

    @Override
    public RetrievalClient create(Throwable cause) {
        return new RetrievalClient() {

            @Override
            public RetrieveResponse retrieve(RetrieveRequest request) {
                throw failure(cause);
            }
        };
    }

    private static RuntimeException failure(Throwable cause) {
        return FeignFallbacks.failure(cause,
                () -> new ServiceUnavailableException(FeignFallbacks.unavailableMessage("retrieval-service", cause), cause));
    }
}
