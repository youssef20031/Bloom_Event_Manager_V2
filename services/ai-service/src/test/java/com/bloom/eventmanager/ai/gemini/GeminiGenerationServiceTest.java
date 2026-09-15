package com.bloom.eventmanager.ai.gemini;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

import com.bloom.eventmanager.ai.config.AiProperties;
import com.bloom.eventmanager.ai.domain.GenerationOptions;
import com.bloom.eventmanager.ai.domain.GenerationResult;
import com.bloom.eventmanager.common.error.ExternalServiceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiGenerationServiceTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final AiProperties properties = new AiProperties("key", "gemini-primary",
            List.of("gemini-fallback-1", "gemini-fallback-2"), "gemini-embedding-001", 3072, 20, "bloom:");
    private final GeminiGenerationService service = new GeminiGenerationService(chatModel, properties);

    @Test
    void separatesThoughtPartsFromAnswerParts() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                part("Let me think about venues…", true),
                part("Here is the plan.", false),
                part(" More detail.", false)));

        GenerationResult result = service.generate("plan an event", GenerationOptions.defaults());

        assertThat(result.text()).isEqualTo("Here is the plan. More detail.");
        assertThat(result.thoughts()).isEqualTo("Let me think about venues…");
    }

    @Test
    void thoughtsAreNullWhenThereAreNone() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response(part("answer", false)));

        assertThat(service.generate("q", GenerationOptions.defaults()).thoughts()).isNull();
    }

    @Test
    void passesOptionsThroughToGemini() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response(part("ok", false)));

        service.generate("q", new GenerationOptions(true, 1.2, 8192));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("gemini-primary");
        assertThat(options.getTemperature()).isEqualTo(1.2);
        assertThat(options.getMaxOutputTokens()).isEqualTo(8192);
        assertThat(options.getIncludeThoughts()).isTrue();
    }

    @Test
    void fallsBackToTheNextModelOnQuotaExhaustion() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("429 RESOURCE_EXHAUSTED"))
                .thenReturn(response(part("from fallback", false)));

        GenerationResult result = service.generate("q", GenerationOptions.defaults());

        assertThat(result.text()).isEqualTo("from fallback");
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        assertThat(((GoogleGenAiChatOptions) prompts.getAllValues().get(1).getOptions()).getModel())
                .isEqualTo("gemini-fallback-1");
    }

    @Test
    void doesNotSwitchModelsForNonQuotaFailures() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("500 INTERNAL"));

        assertThatThrownBy(() -> service.generate("q", GenerationOptions.defaults()))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("gemini-primary");
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void reportsFailureWhenEveryModelInTheChainIsExhausted() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("429 RESOURCE_EXHAUSTED"));

        assertThatThrownBy(() -> service.generate("q", GenerationOptions.defaults()))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("all models");
        verify(chatModel, times(3)).call(any(Prompt.class));
    }

    private static Generation part(String text, boolean thought) {
        AssistantMessage message = AssistantMessage.builder()
                .content(text)
                .properties(Map.of("isThought", thought))
                .build();
        return new Generation(message);
    }

    private static ChatResponse response(Generation... generations) {
        return new ChatResponse(List.of(generations));
    }
}
