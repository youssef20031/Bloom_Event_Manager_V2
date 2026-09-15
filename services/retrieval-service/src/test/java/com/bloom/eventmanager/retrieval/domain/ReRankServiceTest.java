package com.bloom.eventmanager.retrieval.domain;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.retrieval.client.AiClient;
import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReRankServiceTest {

    private final AiClient ai = mock(AiClient.class);
    private final ReRankService service = new ReRankService(ai, JsonMapper.builder().build());

    private final List<ChunkMatch> chunks = List.of(
            chunk("a", "guide.pdf"), chunk("b", "brief.pdf"), chunk("c", "chat.pdf"));

    @Test
    void reordersByTheModelsOneBasedRanking() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("{\"ranking\": [3, 1, 2]}", null));

        List<ChunkMatch> ranked = service.reRank("q", chunks);

        assertThat(ranked).extracting(ChunkMatch::id).containsExactly("c", "a", "b");
    }

    @Test
    void dropsIndicesOutOfRangeAndDuplicates() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("{\"ranking\": [2, 9, 2, 0, 1]}", null));

        List<ChunkMatch> ranked = service.reRank("q", chunks);

        assertThat(ranked).extracting(ChunkMatch::id).containsExactly("b", "a");
    }

    @Test
    void keepsOriginalOrderWhenTheModelReturnsGarbage() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("no json here", null));

        assertThat(service.reRank("q", chunks)).extracting(ChunkMatch::id).containsExactly("a", "b", "c");
    }

    @Test
    void keepsOriginalOrderWhenTheCallFails() {
        when(ai.generate(any())).thenThrow(new RuntimeException("down"));

        assertThat(service.reRank("q", chunks)).extracting(ChunkMatch::id).containsExactly("a", "b", "c");
    }

    @Test
    void skipsTheModelEntirelyForAnEmptyList() {
        assertThat(service.reRank("q", List.of())).isEmpty();
        verify(ai, never()).generate(any());
    }

    @Test
    void dedupesByIdBeforeAskingTheModel() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("{\"ranking\": [1, 2]}", null));

        service.reRank("q", List.of(chunk("a", "x"), chunk("a", "x"), chunk("b", "y")));

        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(ai).generate(request.capture());
        assertThat(request.getValue().input()).contains("[1]").contains("[2]").doesNotContain("[3]");
    }

    @Test
    void truncatesChunkTextInThePromptTo300Chars() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("{\"ranking\": [1]}", null));
        String longText = "x".repeat(1000);

        service.reRank("q", List.of(new ChunkMatch("a", longText, "s", 1, 0.5)));

        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(ai).generate(request.capture());
        assertThat(request.getValue().input()).contains("x".repeat(300) + "...").doesNotContain("x".repeat(301));
    }

    private static ChunkMatch chunk(String id, String source) {
        return new ChunkMatch(id, "text of " + id, source, 1, 0.5);
    }
}
