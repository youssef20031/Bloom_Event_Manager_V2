package com.bloom.eventmanager.chat.domain;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.bloom.eventmanager.chat.client.AiClient;
import com.bloom.eventmanager.chat.client.RetrievalClient;
import com.bloom.eventmanager.chat.client.dto.ChunkMatch;
import com.bloom.eventmanager.chat.client.dto.GenerateRequest;
import com.bloom.eventmanager.chat.client.dto.GenerateResponse;
import com.bloom.eventmanager.chat.client.dto.RetrieveRequest;
import com.bloom.eventmanager.chat.client.dto.RetrieveResponse;
import com.bloom.eventmanager.chat.config.ChatProperties;
import com.bloom.eventmanager.common.error.ExternalServiceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatWithDocumentsUseCaseTest {

    private final RetrievalClient retrieval = mock(RetrievalClient.class);
    private final AiClient ai = mock(AiClient.class);
    private final ChatSessionStore store = mock(ChatSessionStore.class);
    private final ChatWithDocumentsUseCase useCase = new ChatWithDocumentsUseCase(retrieval, ai, store, new ChatProperties(15, 6));

    @Test
    void buildsV1sPromptWithNumberedSourcesHistoryAndTheMessage() {
        when(store.recentMessages("s1", 6)).thenReturn(List.of(
                new ChatMessage("user", "earlier question", Instant.now(), List.of(), null),
                new ChatMessage("model", "earlier answer", Instant.now(), List.of(), null)));
        when(retrieval.retrieve(any())).thenReturn(new RetrieveResponse("success", List.of(
                new ChunkMatch("c1", "puzzle ideas", "guide.pdf", 1, 0.9),
                new ChunkMatch("c2", "venue notes", "brief.pdf", 1, 0.8),
                new ChunkMatch("c3", "more puzzles", "guide.pdf", 2, 0.7)), null));
        when(ai.generate(any())).thenReturn(new GenerateResponse("the plan", null));

        ChatResult result = useCase.execute("plan a team day", "s1");

        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(ai).generate(request.capture());
        String prompt = request.getValue().input();
        assertThat(prompt).startsWith("You are an event planner for Bloom.")
                .contains("[Source 1: guide.pdf]\npuzzle ideas")
                .contains("[Source 2: brief.pdf]\nvenue notes")
                .contains("[Source 1: guide.pdf]\nmore puzzles")
                .contains("USER: earlier question\nMODEL: earlier answer")
                .contains("USER: plan a team day")
                .contains("**Step 1 — Escapism**")
                .doesNotContain("CRITICAL — NO REPETITION");
        assertThat(request.getValue().config().includeThoughts()).isTrue();
        assertThat(request.getValue().config().temperature()).isEqualTo(1.2);
        assertThat(result.sources()).containsExactly("guide.pdf", "brief.pdf");
        assertThat(result.sessionId()).isEqualTo("s1");
    }

    @Test
    void addsTheAntiRepetitionBlockOnceThePriorSuggestionsAreLongEnough() {
        when(store.recentMessages("s1", 6)).thenReturn(List.of(
                new ChatMessage("model", "x".repeat(150), Instant.now(), List.of(), null)));
        when(retrieval.retrieve(any())).thenReturn(new RetrieveResponse("success", List.of(), null));
        when(ai.generate(any())).thenReturn(new GenerateResponse("new plan", null));

        useCase.execute("another idea", "s1");

        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(ai).generate(request.capture());
        assertThat(request.getValue().input()).contains("CRITICAL — NO REPETITION");
    }

    @Test
    void retrievesPlainlyAtTopKAndAddsTheTeambuildingSearchWhenMentioned() {
        when(retrieval.retrieve(argThat(r -> r != null && "plan team building".equals(r.query()))))
                .thenReturn(new RetrieveResponse("success", List.of(new ChunkMatch("c1", "a", "a.pdf", 1, 0.9)), null));
        when(retrieval.retrieve(argThat(r -> r != null && r.query().startsWith("Teambuilding activities Candy Love"))))
                .thenReturn(new RetrieveResponse("success", List.of(
                        new ChunkMatch("c1", "a", "a.pdf", 1, 0.9), new ChunkMatch("c9", "candy", "tb.pdf", 1, 0.5)), null));
        when(ai.generate(any())).thenReturn(new GenerateResponse("ok", null));

        ChatResult result = useCase.execute("plan team building", null);

        ArgumentCaptor<RetrieveRequest> requests = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieval, times(2)).retrieve(requests.capture());
        assertThat(requests.getAllValues().get(0)).isEqualTo(new RetrieveRequest("plan team building", 15, false));
        assertThat(requests.getAllValues().get(1)).isEqualTo(new RetrieveRequest(
                "Teambuilding activities Candy Love Caption This Spot the Movies Big Puzzle Age Lineup", 10, false));
        assertThat(result.sources()).containsExactly("a.pdf", "tb.pdf"); // c1 deduped
        assertThat(result.sessionId()).matches("session_\\d+_[a-z0-9]{7}");
    }

    @Test
    void extractsPlanTagsAsTheAnswerAndTheRestAsThoughts() {
        when(retrieval.retrieve(any())).thenReturn(new RetrieveResponse("success", List.of(), null));
        when(ai.generate(any())).thenReturn(new GenerateResponse("Let me think.\n<plan>The actual plan</plan>", "native thought"));

        ChatResult result = useCase.execute("q", "s1");

        assertThat(result.answer()).isEqualTo("The actual plan");
        assertThat(result.thoughts()).isEqualTo("native thought\n\n---\n\nLet me think.");
    }

    @Test
    void stripsThinkTagsIntoThoughts() {
        when(retrieval.retrieve(any())).thenReturn(new RetrieveResponse("success", List.of(), null));
        when(ai.generate(any())).thenReturn(new GenerateResponse("<think>hmm</think>Final answer", null));

        ChatResult result = useCase.execute("q", "s1");

        assertThat(result.answer()).isEqualTo("Final answer");
        assertThat(result.thoughts()).isEqualTo("hmm");
    }

    @Test
    void movesShortOpeningFluffIntoThoughts() {
        when(retrieval.retrieve(any())).thenReturn(new RetrieveResponse("success", List.of(), null));
        when(ai.generate(any())).thenReturn(new GenerateResponse("Sure, here is a plan.\n\n**Theme Concept**: Space", null));

        ChatResult result = useCase.execute("q", "s1");

        assertThat(result.answer()).isEqualTo("**Theme Concept**: Space");
        assertThat(result.thoughts()).isEqualTo("Sure, here is a plan.");
    }

    @Test
    void persistsUserAndModelMessagesWithSourcesAndThoughts() {
        when(retrieval.retrieve(any())).thenReturn(new RetrieveResponse("success",
                List.of(new ChunkMatch("c1", "a", "a.pdf", 1, 0.9)), null));
        when(ai.generate(any())).thenReturn(new GenerateResponse("answer", "why"));

        useCase.execute("hello", "s1");

        ArgumentCaptor<List<ChatMessage>> saved = ArgumentCaptor.captor();
        verify(store).append(eq("s1"), saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        assertThat(saved.getValue().get(0).role()).isEqualTo("user");
        assertThat(saved.getValue().get(0).content()).isEqualTo("hello");
        assertThat(saved.getValue().get(1).role()).isEqualTo("model");
        assertThat(saved.getValue().get(1).sources()).containsExactly("a.pdf");
        assertThat(saved.getValue().get(1).thoughts()).isEqualTo("why");
    }

    @Test
    void wrapsRetrievalFailuresAsExternalServiceException() {
        when(retrieval.retrieve(any())).thenThrow(new RuntimeException("retrieval-service unreachable"));

        assertThatThrownBy(() -> useCase.execute("q", "s1"))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessage("Context retrieval failed");
    }

    @Test
    void wrapsGenerationFailuresAsExternalServiceExceptionWithADistinctMessage() {
        when(retrieval.retrieve(any())).thenReturn(new RetrieveResponse("success", List.of(), null));
        when(ai.generate(any())).thenThrow(new RuntimeException("ai-service unreachable"));

        assertThatThrownBy(() -> useCase.execute("q", "s1"))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessage("Event Advisor generation failed")
                .hasMessageNotContaining("Context retrieval failed");
    }
}
