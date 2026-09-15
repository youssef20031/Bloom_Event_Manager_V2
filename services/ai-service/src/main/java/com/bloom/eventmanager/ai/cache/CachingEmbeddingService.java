package com.bloom.eventmanager.ai.cache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.ai.domain.EmbeddingPort;
import com.bloom.eventmanager.ai.gemini.GeminiEmbeddingService;
import com.bloom.eventmanager.common.error.ExternalServiceException;

/**
 * The EmbeddingPort everyone gets. Serves hits from Redis and forwards only
 * genuine misses — deduplicated — to Gemini.
 *
 * <p>The batch path must not delegate to the upstream batch method with the
 * original list: that would bypass the cache for every batch caller while every
 * test kept passing. It resolves each text here first.
 */
@Primary
@Service
public class CachingEmbeddingService implements EmbeddingPort {

    private final GeminiEmbeddingService upstream;
    private final EmbeddingCache cache;

    public CachingEmbeddingService(GeminiEmbeddingService upstream, EmbeddingCache cache) {
        this.upstream = upstream;
        this.cache = cache;
    }

    @Override
    public float[] embed(String text) {
        Optional<float[]> hit = cache.get(text);
        if (hit.isPresent()) {
            return hit.get();
        }
        float[] embedding = upstream.embed(text);
        cache.put(text, embedding);
        return embedding;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        float[][] results = new float[texts.size()][];
        Map<String, List<Integer>> missIndicesByText = new LinkedHashMap<>();

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            Optional<float[]> hit = cache.get(text);
            if (hit.isPresent()) {
                results[i] = hit.get();
            }
            else {
                missIndicesByText.computeIfAbsent(text, key -> new ArrayList<>()).add(i);
            }
        }

        if (!missIndicesByText.isEmpty()) {
            List<String> missTexts = new ArrayList<>(missIndicesByText.keySet());
            List<float[]> fresh = upstream.embedBatch(missTexts);
            // Texts and embeddings are paired by position below. Cache writes have no
            // TTL and use V1's key layout, so a misaligned pair would permanently
            // poison V1's cache too — refuse the whole batch before writing anything.
            if (fresh.size() != missTexts.size()) {
                throw new ExternalServiceException(
                        "Gemini returned " + fresh.size() + " embeddings for " + missTexts.size() + " texts", null);
            }
            for (int j = 0; j < missTexts.size(); j++) {
                String text = missTexts.get(j);
                float[] embedding = fresh.get(j);
                for (int i : missIndicesByText.get(text)) {
                    results[i] = embedding;
                }
                cache.put(text, embedding);
            }
        }

        return Arrays.asList(results);
    }
}
