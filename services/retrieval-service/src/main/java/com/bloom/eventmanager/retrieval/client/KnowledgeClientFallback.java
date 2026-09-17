package com.bloom.eventmanager.retrieval.client;

import java.util.List;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.client.dto.ChunkSearchRequest;

/**
 * Runs when knowledge-service is down or its breaker is open. A retrieval with
 * no chunk store has nothing to answer from, so this is a 502 rather than an
 * empty result set that would read as "no matches".
 */
@Component
public class KnowledgeClientFallback implements KnowledgeClient {

    @Override
    public List<ChunkMatch> search(ChunkSearchRequest request) {
        throw new ExternalServiceException("knowledge-service unavailable (circuit open)", null);
    }
}
