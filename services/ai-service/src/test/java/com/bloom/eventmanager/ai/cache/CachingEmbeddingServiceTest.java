package com.bloom.eventmanager.ai.cache;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.ai.gemini.GeminiEmbeddingService;
import com.bloom.eventmanager.common.error.ExternalServiceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachingEmbeddingServiceTest {

    private final GeminiEmbeddingService upstream = mock(GeminiEmbeddingService.class);
    private final EmbeddingCache cache = mock(EmbeddingCache.class);
    private final CachingEmbeddingService service = new CachingEmbeddingService(upstream, cache);

    @Test
    void singleEmbedServesAHitWithoutCallingGemini() {
        when(cache.get("hello")).thenReturn(Optional.of(new float[] { 1f }));

        assertThat(service.embed("hello")).containsExactly(1f);
        verify(upstream, never()).embed(anyString());
    }

    @Test
    void singleEmbedFillsTheCacheOnAMiss() {
        when(cache.get("hello")).thenReturn(Optional.empty());
        when(upstream.embed("hello")).thenReturn(new float[] { 2f });

        assertThat(service.embed("hello")).containsExactly(2f);
        verify(cache).put("hello", new float[] { 2f });
    }

    @Test
    void batchSendsOnlyMissesUpstreamAndKeepsPositions() {
        when(cache.get("a")).thenReturn(Optional.of(new float[] { 1f }));
        when(cache.get("b")).thenReturn(Optional.empty());
        when(cache.get("c")).thenReturn(Optional.empty());
        when(upstream.embedBatch(List.of("b", "c"))).thenReturn(List.of(new float[] { 2f }, new float[] { 3f }));

        List<float[]> result = service.embedBatch(List.of("a", "b", "c"));

        assertThat(result.get(0)).containsExactly(1f);
        assertThat(result.get(1)).containsExactly(2f);
        assertThat(result.get(2)).containsExactly(3f);
        verify(cache).put("b", new float[] { 2f });
        verify(cache).put("c", new float[] { 3f });
    }

    @Test
    void batchDedupesRepeatedTextsSoBoilerplateIsBilledOnce() {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(upstream.embedBatch(List.of("same"))).thenReturn(List.of(new float[] { 9f }));

        List<float[]> result = service.embedBatch(List.of("same", "same", "same"));

        assertThat(result).hasSize(3).allSatisfy(v -> assertThat(v).containsExactly(9f));
        verify(upstream).embedBatch(List.of("same"));
    }

    @Test
    void batchWithAllHitsNeverTouchesGemini() {
        when(cache.get(anyString())).thenReturn(Optional.of(new float[] { 1f }));

        service.embedBatch(List.of("a", "b"));

        verify(upstream, never()).embedBatch(any());
    }

    @Test
    void refusesTheWholeBatchIfUpstreamReturnsTheWrongNumberOfEmbeddings() {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        // Two texts requested, one embedding back: pairing by position would map
        // "b"'s vector onto "a" and permanently poison V1's shared cache.
        when(upstream.embedBatch(List.of("a", "b"))).thenReturn(List.of(new float[] { 1f }));

        assertThatThrownBy(() -> service.embedBatch(List.of("a", "b")))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("1 embeddings for 2 texts");

        verify(cache, never()).put(anyString(), any());
    }

    @Test
    void emptyBatchIsEmpty() {
        assertThat(service.embedBatch(List.of())).isEmpty();
        verify(upstream, never()).embedBatch(any());
    }
}
