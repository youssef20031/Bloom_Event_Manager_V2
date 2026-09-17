package com.bloom.eventmanager.proposal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.common.error.NotFoundException;
import com.bloom.eventmanager.proposal.client.AiClient;
import com.bloom.eventmanager.proposal.client.ChatClient;
import com.bloom.eventmanager.proposal.client.KnowledgeClient;
import com.bloom.eventmanager.proposal.client.dto.ChatMessage;
import com.bloom.eventmanager.proposal.client.dto.ChatSession;
import com.bloom.eventmanager.proposal.client.dto.ChunkMatch;
import com.bloom.eventmanager.proposal.client.dto.ChunksBySourcesRequest;
import com.bloom.eventmanager.proposal.client.dto.EmbedResponse;
import com.bloom.eventmanager.proposal.client.dto.GenerateRequest;
import com.bloom.eventmanager.proposal.client.dto.GenerateResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateProposalUseCaseTest {

    private final KnowledgeClient knowledge = mock(KnowledgeClient.class);
    private final AiClient ai = mock(AiClient.class);
    private final ChatClient chat = mock(ChatClient.class);
    private final GenerateProposalUseCase useCase = new GenerateProposalUseCase(knowledge, ai, chat, JsonMapper.builder().build());

    private static final String NINE_SLIDES = """
            {"slides": [
              {"slideNumber": 9, "type": "cover", "title": "Acme", "subtitle": "Event Proposal",
               "content": {"companyName": "wrong", "eventDate": "TBD", "tagline": "Go", "preparedBy": "someone"}},
              {"slideNumber": 1, "type": "summary", "title": "Executive Summary", "content": {"overview": "o", "highlights": ["h"]}}
            ]}""";

    @Test
    void assemblesBriefChunksChatHistoryAndTeambuildingKnowledgeIntoV1sPrompt() {
        // "acme corp/notes.pdf" is deliberately lowercase to pin V1's
        // case-insensitive match against the mixed-case company name, while
        // still genuinely containing "Acme Corp" (unlike an unrelated
        // "guide.pdf") and demonstrating that multiple matches are all used.
        when(knowledge.allSources()).thenReturn(List.of("Acme Corp/brief.pdf", "guide.pdf", "acme corp/notes.pdf"));
        when(knowledge.chunksBySources(new ChunksBySourcesRequest(List.of("Acme Corp/brief.pdf", "acme corp/notes.pdf"), 50)))
                .thenReturn(List.of(new ChunkMatch("c1", "40 people, June", "Acme Corp/brief.pdf", 1, 1.0),
                        new ChunkMatch("c2", "loves puzzles", "acme corp/notes.pdf", 1, 1.0)));
        when(chat.sessionsMentioning("Acme Corp")).thenReturn(List.of(new ChatSession("s1",
                List.of(new ChatMessage("user", "Acme Corp wants mystery", Instant.now(), List.of(), null),
                        new ChatMessage("model", "Try a heist theme", Instant.now(), List.of(), null)),
                Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T10:05:00Z"))));
        when(ai.embed(argThat(r -> r != null && r.text().equals("Acme Corp event teambuilding activities agenda proposal"))))
                .thenReturn(new EmbedResponse(new float[] { 1f }));
        when(knowledge.search(any())).thenReturn(List.of(new ChunkMatch("t1", "Candy Love rules", "tb.pdf", 1, 0.9)));
        when(ai.generate(any())).thenReturn(new GenerateResponse(NINE_SLIDES, null));

        GeneratedProposal proposal = useCase.execute("Acme Corp");

        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(ai).generate(request.capture());
        String prompt = request.getValue().input();
        assertThat(prompt).startsWith("You are an expert Event Proposal Writer for Bloom")
                .contains("CLIENT BRIEF & EVENT DATA (from \"Acme Corp\")")
                .contains("40 people, June\n\n---\n\nloves puzzles")
                .contains("EVENT ADVISOR CONVERSATIONS (discussions about Acme Corp)")
                .contains("=== Chat Session (")
                .contains("CLIENT: Acme Corp wants mystery\nADVISOR: Try a heist theme")
                .contains("[tb.pdf] Candy Love rules")
                .contains("Generate EXACTLY 9 slides")
                .contains("Output ONLY valid JSON. No markdown, no explanation, no code fences.");
        assertThat(request.getValue().config().temperature()).isEqualTo(0.7);
        assertThat(request.getValue().config().maxOutputTokens()).isEqualTo(8192);
        assertThat(proposal.companyName()).isEqualTo("Acme Corp");
        assertThat(proposal.generatedAt()).isNotBlank();
    }

    @Test
    void renumbersSlidesAndForcesCoverBranding() {
        stubHappyPath();

        GeneratedProposal proposal = useCase.execute("Acme Corp");

        assertThat(proposal.slides()).extracting(ProposalSlide::slideNumber).containsExactly(1, 2);
        Map<String, Object> cover = proposal.slides().get(0).content();
        assertThat(cover).containsEntry("companyName", "Acme Corp").containsEntry("preparedBy", "Bloom Events");
    }

    @Test
    void acceptsABareArrayOfSlides() {
        stubHappyPath();
        when(ai.generate(any())).thenReturn(new GenerateResponse(
                "```json\n[{\"type\": \"summary\", \"title\": \"S\", \"content\": {}}]\n```", null));

        assertThat(useCase.execute("Acme Corp").slides()).singleElement()
                .satisfies(slide -> assertThat(slide.slideNumber()).isEqualTo(1));
    }

    @Test
    void omitsTheChatSectionWhenNoSessionMentionsTheCompany() {
        stubHappyPath();
        when(chat.sessionsMentioning("Acme Corp")).thenReturn(List.of());

        useCase.execute("Acme Corp");

        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(ai).generate(request.capture());
        assertThat(request.getValue().input()).doesNotContain("EVENT ADVISOR CONVERSATIONS");
    }

    @Test
    void chatServiceFailureIsToleratedLikeV1() {
        stubHappyPath();
        when(chat.sessionsMentioning("Acme Corp")).thenThrow(new RuntimeException("chat down"));

        GeneratedProposal proposal = useCase.execute("Acme Corp");

        assertThat(proposal.slides()).isNotEmpty();
        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(ai).generate(request.capture());
        assertThat(request.getValue().input()).doesNotContain("EVENT ADVISOR CONVERSATIONS");
    }

    @Test
    void knowledgeServiceFailureBecomesExternalServiceException() {
        when(knowledge.allSources()).thenThrow(new RuntimeException("neo4j down"));

        assertThatThrownBy(() -> useCase.execute("Acme Corp"))
                .isInstanceOf(ExternalServiceException.class);
        verify(ai, never()).generate(any());
    }

    @Test
    void aiGenerateFailureBecomesExternalServiceException() {
        stubHappyPath();
        when(ai.generate(any())).thenThrow(new RuntimeException("gemini down"));

        assertThatThrownBy(() -> useCase.execute("Acme Corp"))
                .isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void unknownCompanyIs404WithV1sMessage() {
        when(knowledge.allSources()).thenReturn(List.of("guide.pdf"));

        assertThatThrownBy(() -> useCase.execute("Nobody"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No data found for \"Nobody\". Make sure a brief has been submitted and synced.");
        verify(ai, never()).generate(any());
    }

    @Test
    void blankCompanyIs400() {
        assertThatThrownBy(() -> useCase.execute("  "))
                .isInstanceOf(BloomException.class)
                .hasMessage("Company name is required");
    }

    @Test
    void unparseableModelOutputIs500WithV1sMessage() {
        stubHappyPath();
        when(ai.generate(any())).thenReturn(new GenerateResponse("Sorry, I cannot do that.", null));

        assertThatThrownBy(() -> useCase.execute("Acme Corp"))
                .isInstanceOf(BloomException.class)
                .hasMessage("Failed to generate proposal. AI response was not valid JSON.");
    }

    private void stubHappyPath() {
        when(knowledge.allSources()).thenReturn(List.of("Acme Corp/brief.pdf"));
        when(knowledge.chunksBySources(any())).thenReturn(List.of(new ChunkMatch("c1", "brief text", "Acme Corp/brief.pdf", 1, 1.0)));
        when(chat.sessionsMentioning(any())).thenReturn(List.of());
        when(ai.embed(any())).thenReturn(new EmbedResponse(new float[] { 1f }));
        when(knowledge.search(any())).thenReturn(List.of());
        when(ai.generate(any())).thenReturn(new GenerateResponse(NINE_SLIDES, null));
    }
}
