package com.bloom.eventmanager.retrieval.domain;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.retrieval.client.AiClient;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryVariationServiceTest {

    private final AiClient ai = mock(AiClient.class);
    private final QueryVariationService service = new QueryVariationService(ai, JsonMapper.builder().build());

    @Test
    void parsesSpecificAndBroadFromTheModelsJson() {
        when(ai.generate(any())).thenReturn(new GenerateResponse(
                "```json\n{\"specific\": \"Awlady team building brief\", \"broad\": \"non-profit team building activities\"}\n```",
                null));

        SearchVariations variations = service.variations("Awlady team building");

        assertThat(variations.specific()).isEqualTo("Awlady team building brief");
        assertThat(variations.broad()).isEqualTo("non-profit team building activities");
    }

    @Test
    void sendsV1sPromptWithTheQueryEmbedded() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("{\"specific\": \"a\", \"broad\": \"b\"}", null));

        service.variations("icebreakers for 40 people");

        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(ai).generate(request.capture());
        assertThat(request.getValue().input())
                .contains("Analyze this search query: \"icebreakers for 40 people\"")
                .contains("\"Specific\"")
                .contains("\"Broad\"")
                .contains("Return ONLY a JSON object");
    }

    @Test
    void fallsBackFieldWiseToTheOriginalQueryWhenAFieldIsMissing() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("{\"specific\": \"only this\"}", null));

        SearchVariations variations = service.variations("original");

        assertThat(variations.specific()).isEqualTo("only this");
        assertThat(variations.broad()).isEqualTo("original");
    }

    @Test
    void throwsWhenTheModelReturnsGarbageSoTheCallerCanDegradeOutsideTheCache() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("I cannot help with that.", null));

        assertThatThrownBy(() -> service.variations("q")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void propagatesTransportFailures() {
        when(ai.generate(any())).thenThrow(new RuntimeException("ai-service unavailable"));

        assertThatThrownBy(() -> service.variations("q")).hasMessageContaining("ai-service unavailable");
    }
}
