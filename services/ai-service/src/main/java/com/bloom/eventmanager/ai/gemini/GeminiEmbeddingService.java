package com.bloom.eventmanager.ai.gemini;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;
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
    private final int batchSize;

    public GeminiEmbeddingService(EmbeddingModel model, AiProperties properties) {
        this.model = model;
        this.batchSize = properties.embeddingBatchSize();
    }

    @Override
    public float[] embed(String text) {
        try {
            return model.embed(text);
        }
        catch (RuntimeException ex) {
            throw new ExternalServiceException("Gemini embedding failed", ex);
        }
    }

    /** Sequential slices of {@code batchSize}, as V1 did, to stay under rate limits. */
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> slice = texts.subList(from, Math.min(from + batchSize, texts.size()));
            try {
                out.addAll(model.embed(slice));
            }
            catch (RuntimeException ex) {
                throw new ExternalServiceException("Gemini batch embedding failed", ex);
            }
        }
        return out;
    }
}
