package com.bloom.eventmanager.proposal.client;

import java.util.List;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ServiceUnavailableException;
import com.bloom.eventmanager.common.feign.FeignFallbacks;
import com.bloom.eventmanager.proposal.client.dto.ChatSession;
import com.bloom.eventmanager.proposal.client.dto.CountResponse;

/**
 * Runs when chat-service is down or its breaker is open. proposal-service is
 * called directly by the browser, so throwing surfaces a clean 503
 * ProblemDetail instead of leaving the request to time out or return a
 * misleading 502.
 *
 * <p>A {@code FallbackFactory} rather than a plain fallback because the invocation
 * handler routes <em>every</em> throwable here, including the {@code FeignException}
 * raised for a well-formed 4xx. {@link FeignFallbacks#failure} hands a 4xx back to the
 * caller unchanged so the peer's own HTTP semantics survive, and translates only
 * genuine unavailability.
 */
@Component
public class ChatClientFallbackFactory implements FallbackFactory<ChatClient> {

    @Override
    public ChatClient create(Throwable cause) {
        return new ChatClient() {

            @Override
            public List<ChatSession> sessionsMentioning(String company) {
                throw failure(cause);
            }

            @Override
            public CountResponse countMentioning(String company) {
                throw failure(cause);
            }
        };
    }

    private static RuntimeException failure(Throwable cause) {
        return FeignFallbacks.failure(cause,
                () -> new ServiceUnavailableException(FeignFallbacks.unavailableMessage("chat-service", cause), cause));
    }
}
