package com.bloom.eventmanager.ai.cache;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.ai.config.AiProperties;

/**
 * Embedding cache in front of Gemini.
 *
 * <p>Two rules, both inherited from V1:
 * <ol>
 * <li>A cache problem is never a request problem. Every Redis failure is
 * caught, logged at DEBUG, and treated as a miss.</li>
 * <li>No TTL. An embedding is a pure function of (text, model), so entries
 * are immutable and Redis's allkeys-lru policy reclaims the cold ones.</li>
 * </ol>
 *
 * <p>The model is part of the key: vectors from different models have
 * different dimensionality and must never be served for each other.
 */
@Component
public class EmbeddingCache {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingCache.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final String embeddingModel;

    public EmbeddingCache(StringRedisTemplate redis, AiProperties properties) {
        this.redis = redis;
        this.keyPrefix = properties.cacheKeyPrefix();
        this.embeddingModel = properties.embeddingModel();
    }

    public String key(String text) {
        return keyPrefix + "embed:" + embeddingModel + ":" + EmbeddingCodec.hashText(text);
    }

    public Optional<float[]> get(String text) {
        String key = key(text);
        try {
            String encoded = redis.opsForValue().get(key);
            return encoded == null ? Optional.empty() : Optional.of(EmbeddingCodec.decode(encoded));
        }
        catch (RuntimeException ex) {
            log.debug("Embedding cache read failed for {} — falling through to source", key, ex);
            return Optional.empty();
        }
    }

    public void put(String text, float[] embedding) {
        String key = key(text);
        try {
            redis.opsForValue().set(key, EmbeddingCodec.encode(embedding));
        }
        catch (RuntimeException ex) {
            log.debug("Embedding cache write failed for {} — value not cached", key, ex);
        }
    }
}
