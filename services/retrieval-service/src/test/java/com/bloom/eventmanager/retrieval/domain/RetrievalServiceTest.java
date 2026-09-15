package com.bloom.eventmanager.retrieval.domain;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.retrieval.client.AiClient;
import com.bloom.eventmanager.retrieval.client.KnowledgeClient;
import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchResponse;
import com.bloom.eventmanager.retrieval.config.RetrievalProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    private static final float[] SPECIFIC_VEC = { 1f, 0f };
    private static final float[] BROAD_VEC = { 0f, 1f };

    private final AiClient ai = mock(AiClient.class);
    private final KnowledgeClient knowledge = mock(KnowledgeClient.class);
    private final QueryVariationService variations = mock(QueryVariationService.class);
    private final ReRankService reRank = mock(ReRankService.class);
    private final RetrievalService service = new RetrievalService(
            ai, knowledge, variations, reRank, new RetrievalProperties(3));

    @Test
    void aiPathSearchesBothVariationsDedupesReRanksAndTruncates() {
        when(variations.variations("q")).thenReturn(new SearchVariations("q specific", "q broad"));
        when(ai.embedBatch(new EmbedBatchRequest(List.of("q specific", "q broad"))))
                .thenReturn(new EmbedBatchResponse(List.of(SPECIFIC_VEC, BROAD_VEC)));
        when(knowledge.search(argThat(r -> r != null && r.embedding() == SPECIFIC_VEC && r.topK() == 3)))
                .thenReturn(List.of(chunk("a"), chunk("b"), chunk("c")));
        when(knowledge.search(argThat(r -> r != null && r.embedding() == BROAD_VEC && r.topK() == 3)))
                .thenReturn(List.of(chunk("c"), chunk("d"), chunk("e")));
        when(reRank.reRank(eq("q"), any())).thenAnswer(inv -> {
            List<ChunkMatch> in = inv.getArgument(1);
            return in.reversed();
        });

        RetrievalResult result = service.retrieve("q", null, null, true);

        // 6 retrieved, 5 unique (c deduped), reversed by re-rank, cut to topK=3
        assertThat(result.totalRetrieved()).isEqualTo(6);
        assertThat(result.chunks()).extracting(ChunkMatch::id).containsExactly("e", "d", "c");
        assertThat(result.aiEnhanced()).isTrue();
        assertThat(result.variations()).isEqualTo(new SearchVariations("q specific", "q broad"));
        assertThat(result.originalQuery()).isEqualTo("q");
    }

    @Test
    void aiPathEmbedsOnceWhenBroadEqualsSpecific() {
        when(variations.variations("q")).thenReturn(SearchVariations.same("q"));
        when(ai.embedBatch(new EmbedBatchRequest(List.of("q"))))
                .thenReturn(new EmbedBatchResponse(List.of(SPECIFIC_VEC)));
        when(knowledge.search(any())).thenReturn(List.of(chunk("a")));
        when(reRank.reRank(eq("q"), any())).thenAnswer(inv -> inv.getArgument(1));

        service.retrieve("q", null, null, true);

        verify(ai).embedBatch(new EmbedBatchRequest(List.of("q")));
        verify(knowledge).search(any());
    }

    @Test
    void aiPathDegradesToTheOriginalQueryWhenVariationsFail() {
        when(variations.variations("q")).thenThrow(new RuntimeException("gemini down"));
        when(ai.embedBatch(new EmbedBatchRequest(List.of("q"))))
                .thenReturn(new EmbedBatchResponse(List.of(SPECIFIC_VEC)));
        when(knowledge.search(any())).thenReturn(List.of(chunk("a")));
        when(reRank.reRank(eq("q"), any())).thenAnswer(inv -> inv.getArgument(1));

        RetrievalResult result = service.retrieve("q", null, null, true);

        assertThat(result.variations()).isEqualTo(SearchVariations.same("q"));
        assertThat(result.chunks()).extracting(ChunkMatch::id).containsExactly("a");
    }

    @Test
    void plainPathEmbedsTheQueryAndSearchesDoubleTopK() {
        when(ai.embedBatch(new EmbedBatchRequest(List.of("q"))))
                .thenReturn(new EmbedBatchResponse(List.of(SPECIFIC_VEC)));
        when(knowledge.search(argThat(r -> r != null && r.topK() == 6)))
                .thenReturn(List.of(chunk("a"), chunk("b"), chunk("c"), chunk("d")));

        RetrievalResult result = service.retrieve("q", null, null, false);

        assertThat(result.chunks()).extracting(ChunkMatch::id).containsExactly("a", "b", "c");
        assertThat(result.totalRetrieved()).isEqualTo(4);
        assertThat(result.aiEnhanced()).isFalse();
        assertThat(result.variations()).isNull();
        verify(reRank, never()).reRank(any(), any());
        verify(variations, never()).variations(any());
    }

    @Test
    void plainPathUsesACallerSuppliedEmbeddingWithoutCallingAi() {
        float[] supplied = { 0.5f, 0.5f };
        when(knowledge.search(argThat(r -> r != null && r.embedding() == supplied))).thenReturn(List.of(chunk("a")));

        service.retrieve(null, supplied, 5, false);

        verify(ai, never()).embedBatch(any());
    }

    @Test
    void useAiWithoutAQueryFallsToThePlainPath() {
        float[] supplied = { 0.5f, 0.5f };
        when(knowledge.search(any())).thenReturn(List.of(chunk("a")));

        RetrievalResult result = service.retrieve(null, supplied, null, true);

        assertThat(result.aiEnhanced()).isFalse();
        verify(variations, never()).variations(any());
    }

    @Test
    void explicitTopKOverridesTheDefault() {
        when(ai.embedBatch(any())).thenReturn(new EmbedBatchResponse(List.of(SPECIFIC_VEC)));
        when(knowledge.search(argThat(r -> r != null && r.topK() == 2))).thenReturn(List.of(chunk("a")));

        service.retrieve("q", null, 1, false);

        verify(knowledge).search(argThat(r -> r != null && r.topK() == 2));
    }

    @Test
    void embeddingFailureIsA502() {
        when(ai.embedBatch(any())).thenThrow(new RuntimeException("ai-service unreachable"));

        assertThatThrownBy(() -> service.retrieve("q", null, null, false))
                .isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void knowledgeFailureIsA502() {
        when(ai.embedBatch(any())).thenReturn(new EmbedBatchResponse(List.of(SPECIFIC_VEC)));
        when(knowledge.search(any())).thenThrow(new RuntimeException("knowledge-service unreachable"));

        assertThatThrownBy(() -> service.retrieve("q", null, null, false))
                .isInstanceOf(ExternalServiceException.class);
    }

    private static ChunkMatch chunk(String id) {
        return new ChunkMatch(id, "text " + id, "source.pdf", 1, 0.8);
    }
}
