package com.bloom.eventmanager.retrieval.client;

import java.util.List;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.common.feign.FeignFallbacks;
import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.client.dto.ChunkSearchRequest;

/**
 * Runs when knowledge-service is down or its breaker is open. A retrieval with
 * no chunk store has nothing to answer from, so this is a 502 rather than an
 * empty result set that would read as "no matches".
 *
 * <p>A {@code FallbackFactory} rather than a plain fallback because the invocation
 * handler routes <em>every</em> throwable here, including the {@code FeignException}
 * raised for a well-formed 4xx. {@link FeignFallbacks#failure} hands a 4xx back to the
 * caller unchanged so the peer's own HTTP semantics survive.
 */
@Component
public class KnowledgeClientFallbackFactory implements FallbackFactory<KnowledgeClient> {

    @Override
    public KnowledgeClient create(Throwable cause) {
        return new KnowledgeClient() {

            @Override
            public List<ChunkMatch> search(ChunkSearchRequest request) {
                throw failure(cause);
            }
        };
    }

    private static RuntimeException failure(Throwable cause) {
        return FeignFallbacks.failure(cause,
                () -> new ExternalServiceException("knowledge-service unavailable (circuit open)", cause));
    }
}
