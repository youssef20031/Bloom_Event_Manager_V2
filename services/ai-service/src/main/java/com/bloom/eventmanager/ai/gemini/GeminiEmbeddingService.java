package com.bloom.eventmanager.ai.gemini;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.ai.config.AiProperties;
import com.bloom.eventmanager.ai.domain.EmbeddingPort;
import com.bloom.eventmanager.common.error.ExternalServiceException;

/**
 * Uncached Gemini embeddings. Not the primary EmbeddingPort — inject this only
 * from {@link com.bloom.eventmanager.ai.cache.CachingEmbeddingService}.
 */
@Service
public class GeminiEmbeddingService implements EmbeddingPort {

    private final EmbeddingModel model;
    private final CircuitBreakerFactory<?, ?> breakers;
    private final int batchSize;

    public GeminiEmbeddingService(EmbeddingModel model, CircuitBreakerFactory<?, ?> breakers, AiProperties properties) {
        this.model = model;
        this.breakers = breakers;
        this.batchSize = properties.embeddingBatchSize();
    }

    @Override
    public float[] embed(String text) {
        return breakers.create("gemini").run(
                () -> model.embed(text),
                failure -> { throw new ExternalServiceException("Gemini embedding failed", failure); });
    }

    /** Sequential slices of {@code batchSize}, as V1 did, to stay under rate limits. */
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> slice = texts.subList(from, Math.min(from + batchSize, texts.size()));
            List<float[]> embedded = breakers.create("gemini").run(
                    () -> model.embed(slice),
                    failure -> { throw new ExternalServiceException("Gemini batch embedding failed", failure); });
            // Caught per slice rather than after concatenation: one short slice would
            // otherwise shift the positional alignment of every text after it.
            if (embedded.size() != slice.size()) {
                throw new ExternalServiceException("Gemini returned " + embedded.size()
                        + " embeddings for a slice of " + slice.size() + " texts", null);
            }
            out.addAll(embedded);
        }
        return out;
    }
}
