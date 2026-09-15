package com.bloom.eventmanager.ai.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.ai.domain.EmbeddingPort;
import com.bloom.eventmanager.ai.domain.GenerationOptions;
import com.bloom.eventmanager.ai.domain.GenerationPort;
import com.bloom.eventmanager.ai.domain.GenerationResult;
import com.bloom.eventmanager.common.error.ExternalServiceException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// No @EnableConfigurationProperties(AiProperties.class): AiController depends
// only on EmbeddingPort and GenerationPort. Binding AiProperties here would
// eagerly validate its @NotBlank apiKey, which this slice supplies no source
// for, and the context would fail to start.
@WebMvcTest(AiController.class)
class AiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmbeddingPort embeddings;

    @MockitoBean
    private GenerationPort generation;

    @Test
    void embedsASingleText() throws Exception {
        when(embeddings.embed("hello")).thenReturn(new float[] { 0.1f, 0.2f });

        mockMvc.perform(post("/api/v1/ai/embed").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.length()").value(2))
                .andExpect(jsonPath("$.embedding[1]").value(0.2));
    }

    @Test
    void rejectsBlankText() throws Exception {
        mockMvc.perform(post("/api/v1/ai/embed").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        verify(embeddings, never()).embed(any());
    }

    @Test
    void embedsABatchInOrder() throws Exception {
        when(embeddings.embedBatch(List.of("a", "b")))
                .thenReturn(List.of(new float[] { 1f }, new float[] { 2f }));

        mockMvc.perform(post("/api/v1/ai/embed/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"texts\": [\"a\", \"b\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embeddings[0][0]").value(1.0))
                .andExpect(jsonPath("$.embeddings[1][0]").value(2.0));
    }

    @Test
    void rejectsAnEmptyBatch() throws Exception {
        mockMvc.perform(post("/api/v1/ai/embed/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"texts\": []}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generatesWithDefaultsWhenNoConfigIsGiven() throws Exception {
        when(generation.generate(eq("plan"), eq(GenerationOptions.defaults())))
                .thenReturn(new GenerationResult("the plan", null));

        mockMvc.perform(post("/api/v1/ai/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"plan\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("the plan"))
                .andExpect(jsonPath("$.thoughts").doesNotExist());
    }

    @Test
    void generatesWithExplicitConfigAndReturnsThoughts() throws Exception {
        when(generation.generate("plan", new GenerationOptions(true, 1.2, 4096)))
                .thenReturn(new GenerationResult("the plan", "thinking…"));

        mockMvc.perform(post("/api/v1/ai/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"plan\", \"config\": {\"includeThoughts\": true, \"temperature\": 1.2, \"maxOutputTokens\": 4096}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thoughts").value("thinking…"));
    }

    @Test
    void surfacesGeminiFailureAs502ProblemDetail() throws Exception {
        when(generation.generate(any(), any()))
                .thenThrow(new ExternalServiceException("Gemini generation failed", new RuntimeException("boom")));

        mockMvc.perform(post("/api/v1/ai/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"plan\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("EXTERNAL_SERVICE_ERROR"));
    }
}
