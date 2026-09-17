package com.bloom.eventmanager.proposal.client;

import java.util.List;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ServiceUnavailableException;
import com.bloom.eventmanager.proposal.client.dto.ChatSession;
import com.bloom.eventmanager.proposal.client.dto.CountResponse;

/**
 * Runs when chat-service is down or its breaker is open. proposal-service is
 * called directly by the browser, so throwing surfaces a clean 503
 * ProblemDetail instead of leaving the request to time out or return a
 * misleading 502.
 */
@Component
public class ChatClientFallback implements ChatClient {

    @Override
    public List<ChatSession> sessionsMentioning(String company) {
        throw new ServiceUnavailableException("chat-service unavailable (circuit open)", null);
    }

    @Override
    public CountResponse countMentioning(String company) {
        throw new ServiceUnavailableException("chat-service unavailable (circuit open)", null);
    }
}
