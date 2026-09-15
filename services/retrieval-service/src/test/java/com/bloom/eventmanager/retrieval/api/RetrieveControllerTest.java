package com.bloom.eventmanager.retrieval.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.domain.RetrievalResult;
import com.bloom.eventmanager.retrieval.domain.RetrievalService;
import com.bloom.eventmanager.retrieval.domain.SearchVariations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RetrieveController.class)
class RetrieveControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RetrievalService retrievalService;

    @Test
    void returnsV1sResponseShapeForAnAiEnhancedQuery() throws Exception {
        when(retrievalService.retrieve(eq("team building"), isNull(), eq(5), eq(true)))
                .thenReturn(new RetrievalResult(
                        List.of(new ChunkMatch("c-1", "text", "guide.pdf", 2, 0.87)),
                        "team building", new SearchVariations("specific", "broad"), 9, true));

        mockMvc.perform(post("/api/v1/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"team building\", \"topK\": 5, \"useAI\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.chunks[0].id").value("c-1"))
                .andExpect(jsonPath("$.chunks[0].score").value(0.87))
                .andExpect(jsonPath("$.metadata.originalQuery").value("team building"))
                .andExpect(jsonPath("$.metadata.variations.specific").value("specific"))
                .andExpect(jsonPath("$.metadata.variations.broad").value("broad"))
                .andExpect(jsonPath("$.metadata.totalRetrieved").value(9))
                .andExpect(jsonPath("$.metadata.returned").value(1))
                .andExpect(jsonPath("$.metadata.aiEnhanced").value(true));
    }

    @Test
    void omitsVariationsOnThePlainPath() throws Exception {
        when(retrievalService.retrieve(eq("q"), isNull(), isNull(), eq(false)))
                .thenReturn(new RetrievalResult(List.of(), "q", null, 0, false));

        mockMvc.perform(post("/api/v1/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"q\", \"useAI\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.variations").doesNotExist())
                .andExpect(jsonPath("$.metadata.aiEnhanced").value(false));
    }

    @Test
    void useAiDefaultsToTrueLikeV1() throws Exception {
        when(retrievalService.retrieve(any(), any(), any(), any()))
                .thenReturn(new RetrievalResult(List.of(), "q", null, 0, true));

        mockMvc.perform(post("/api/v1/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"q\"}"))
                .andExpect(status().isOk());

        verify(retrievalService).retrieve(eq("q"), isNull(), isNull(), eq(true));
    }

    @Test
    void acceptsACallerSuppliedEmbeddingWithoutAQuery() throws Exception {
        when(retrievalService.retrieve(any(), any(), any(), any()))
                .thenReturn(new RetrievalResult(List.of(), null, null, 0, false));

        mockMvc.perform(post("/api/v1/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queryEmbedding\": [0.1, 0.2], \"useAI\": false}"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsARequestWithNeitherQueryNorEmbedding() throws Exception {
        mockMvc.perform(post("/api/v1/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topK\": 5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("queryEmbedding must be a non-empty array"));

        verify(retrievalService, never()).retrieve(any(), any(), any(), any());
    }

    @Test
    void rejectsATopKOutOfRange() throws Exception {
        mockMvc.perform(post("/api/v1/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"q\", \"topK\": 0}"))
                .andExpect(status().isBadRequest());
    }
}
