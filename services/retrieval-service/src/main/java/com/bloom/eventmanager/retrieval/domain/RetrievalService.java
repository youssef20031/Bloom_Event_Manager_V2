package com.bloom.eventmanager.retrieval.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.retrieval.client.AiClient;
import com.bloom.eventmanager.retrieval.client.KnowledgeClient;
import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.client.dto.ChunkSearchRequest;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.retrieval.config.RetrievalProperties;

/**
 * V1's dual-search strategy, decision for decision.
 *
 * <p>AI path: split the query into specific/broad, embed both, search both
 * concurrently at topK each, dedupe by id, re-rank, cut to topK. Plain path:
 * one embedding (caller-supplied or computed), one search at 2×topK, cut to
 * topK. Every AI refinement degrades to the plain behaviour on failure; only
 * failing to obtain an embedding or reach the index is an error.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final AiClient ai;
    private final KnowledgeClient knowledge;
    private final QueryVariationService variationService;
    private final ReRankService reRankService;
    private final RetrievalProperties properties;
    private final ExecutorService searchExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public RetrievalService(AiClient ai, KnowledgeClient knowledge, QueryVariationService variationService,
            ReRankService reRankService, RetrievalProperties properties) {
        this.ai = ai;
        this.knowledge = knowledge;
        this.variationService = variationService;
        this.reRankService = reRankService;
        this.properties = properties;
    }

    public RetrievalResult retrieve(String query, float[] queryEmbedding, Integer requestedTopK, Boolean useAi) {
        int topK = requestedTopK == null ? properties.topK() : requestedTopK;
        boolean aiPath = !Boolean.FALSE.equals(useAi) && query != null && !query.isBlank();

        List<ChunkMatch> retrieved;
        SearchVariations variations = null;

        if (aiPath) {
            variations = variationsOrOriginal(query);
            List<String> toEmbed = new ArrayList<>();
            toEmbed.add(variations.specific());
            if (variations.broad() != null && !variations.broad().equals(variations.specific())) {
                toEmbed.add(variations.broad());
            }
            List<float[]> embeddings = embed(toEmbed);
            retrieved = searchAll(embeddings, topK);
        }
        else {
            float[] embedding = queryEmbedding != null ? queryEmbedding : embed(List.of(query)).get(0);
            retrieved = search(embedding, topK * 2);
        }

        List<ChunkMatch> unique = dedupe(retrieved);
        List<ChunkMatch> ordered = aiPath && !unique.isEmpty()
                ? reRankService.reRank(query, unique)
                : unique;
        List<ChunkMatch> finalChunks = ordered.subList(0, Math.min(topK, ordered.size()));

        return new RetrievalResult(List.copyOf(finalChunks), query, variations, retrieved.size(), aiPath);
    }

    private SearchVariations variationsOrOriginal(String query) {
        try {
            return variationService.variations(query);
        }
        catch (RuntimeException ex) {
            log.warn("Query variation generation failed, using original: {}", ex.getMessage());
            return SearchVariations.same(query);
        }
    }

    private List<float[]> embed(List<String> texts) {
        try {
            return ai.embedBatch(new EmbedBatchRequest(texts)).embeddings();
        }
        catch (BloomException ex) {
            // Already classified -- by AiClientFallbackFactory, with the cause in its
            // message. Re-wrapping it here threw that diagnosis away. Same idiom as
            // proposal-service's GenerateProposalUseCase.safely().
            throw ex;
        }
        catch (RuntimeException ex) {
            throw new ExternalServiceException("Could not obtain query embedding from ai-service", ex);
        }
    }

    private List<ChunkMatch> search(float[] embedding, int topK) {
        try {
            return knowledge.search(new ChunkSearchRequest(embedding, topK));
        }
        catch (BloomException ex) {
            throw ex; // already classified, as in embed()
        }
        catch (RuntimeException ex) {
            throw new ExternalServiceException("Vector search failed in knowledge-service", ex);
        }
    }

    /** V1 ran its per-variation searches with Promise.all; virtual threads are the Java spelling. */
    private List<ChunkMatch> searchAll(List<float[]> embeddings, int topK) {
        List<Future<List<ChunkMatch>>> futures = new ArrayList<>();
        for (float[] embedding : embeddings) {
            futures.add(searchExecutor.submit(() -> search(embedding, topK)));
        }
        List<ChunkMatch> all = new ArrayList<>();
        for (Future<List<ChunkMatch>> future : futures) {
            try {
                all.addAll(future.get());
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ExternalServiceException("Interrupted while searching", ex);
            }
            catch (ExecutionException ex) {
                if (ex.getCause() instanceof BloomException classified) {
                    throw classified;
                }
                throw new ExternalServiceException("Vector search failed in knowledge-service", ex.getCause());
            }
        }
        return all;
    }

    private static List<ChunkMatch> dedupe(List<ChunkMatch> chunks) {
        Map<String, ChunkMatch> byId = new LinkedHashMap<>();
        for (ChunkMatch chunk : chunks) {
            byId.putIfAbsent(chunk.id(), chunk);
        }
        return new ArrayList<>(byId.values());
    }
}
