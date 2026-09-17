package com.bloom.eventmanager.proposal.client;

import java.util.List;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ServiceUnavailableException;
import com.bloom.eventmanager.proposal.client.dto.ChunkMatch;
import com.bloom.eventmanager.proposal.client.dto.ChunkSearchRequest;
import com.bloom.eventmanager.proposal.client.dto.ChunksBySourcesRequest;

/**
 * Runs when knowledge-service is down or its breaker is open. proposal-service
 * is called directly by the browser, so throwing surfaces a clean 503
 * ProblemDetail instead of leaving the request to time out or return a
 * misleading 502.
 */
@Component
public class KnowledgeClientFallback implements KnowledgeClient {

    @Override
    public List<String> allSources() {
        throw new ServiceUnavailableException("knowledge-service unavailable (circuit open)", null);
    }

    @Override
    public List<ChunkMatch> chunksBySources(ChunksBySourcesRequest request) {
        throw new ServiceUnavailableException("knowledge-service unavailable (circuit open)", null);
    }

    @Override
    public List<ChunkMatch> search(ChunkSearchRequest request) {
        throw new ServiceUnavailableException("knowledge-service unavailable (circuit open)", null);
    }
}
