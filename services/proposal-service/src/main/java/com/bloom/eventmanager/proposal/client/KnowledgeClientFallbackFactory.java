package com.bloom.eventmanager.proposal.client;

import java.util.List;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ServiceUnavailableException;
import com.bloom.eventmanager.common.feign.FeignFallbacks;
import com.bloom.eventmanager.proposal.client.dto.ChunkMatch;
import com.bloom.eventmanager.proposal.client.dto.ChunkSearchRequest;
import com.bloom.eventmanager.proposal.client.dto.ChunksBySourcesRequest;

/**
 * Runs when knowledge-service is down or its breaker is open. proposal-service
 * is called directly by the browser, so throwing surfaces a clean 503
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
public class KnowledgeClientFallbackFactory implements FallbackFactory<KnowledgeClient> {

    @Override
    public KnowledgeClient create(Throwable cause) {
        return new KnowledgeClient() {

            @Override
            public List<String> allSources() {
                throw failure(cause);
            }

            @Override
            public List<ChunkMatch> chunksBySources(ChunksBySourcesRequest request) {
                throw failure(cause);
            }

            @Override
            public List<ChunkMatch> search(ChunkSearchRequest request) {
                throw failure(cause);
            }
        };
    }

    private static RuntimeException failure(Throwable cause) {
        return FeignFallbacks.failure(cause,
                () -> new ServiceUnavailableException(FeignFallbacks.unavailableMessage("knowledge-service", cause), cause));
    }
}
