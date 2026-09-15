package com.bloom.eventmanager.knowledge.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.knowledge.config.KnowledgeProperties;
import com.bloom.eventmanager.knowledge.domain.ChunkMatch;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChunkController.class)
@EnableConfigurationProperties(KnowledgeProperties.class)
class ChunkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChunkRepository repository;

    @Test
    void searchReturnsMatchesFromTheRepository() throws Exception {
        when(repository.searchSimilar(any(), eq(2)))
                .thenReturn(List.of(new ChunkMatch("c-1", "text", "guide.pdf", 3, 0.91)));

        String body = "{\"embedding\": " + vector(3072) + ", \"topK\": 2}";

        mockMvc.perform(post("/api/v1/chunks/search").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("c-1"))
                .andExpect(jsonPath("$[0].source").value("guide.pdf"))
                .andExpect(jsonPath("$[0].page").value(3))
                .andExpect(jsonPath("$[0].score").value(0.91));
    }

    @Test
    void searchDefaultsTopKToFifteen() throws Exception {
        when(repository.searchSimilar(any(), eq(15))).thenReturn(List.of());

        mockMvc.perform(post("/api/v1/chunks/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"embedding\": " + vector(3072) + "}"))
                .andExpect(status().isOk());

        verify(repository).searchSimilar(any(), eq(15));
    }

    @Test
    void searchRejectsWrongDimensionsBeforeTouchingNeo4j() throws Exception {
        mockMvc.perform(post("/api/v1/chunks/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"embedding\": " + vector(4) + ", \"topK\": 2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("embedding must have 3072 dimensions, got 4"));

        verify(repository, never()).searchSimilar(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void searchRejectsMissingEmbedding() throws Exception {
        mockMvc.perform(post("/api/v1/chunks/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topK\": 2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void batchStoreAcceptsWellFormedChunks() throws Exception {
        String body = "[{\"id\": \"c-1\", \"text\": \"hello\", \"source\": \"guide.pdf\", \"page\": 1, \"embedding\": "
                + vector(3072) + "}]";

        mockMvc.perform(post("/api/v1/chunks/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        verify(repository).storeBatch(org.mockito.ArgumentMatchers.argThat(chunks ->
                chunks.size() == 1 && chunks.get(0).id().equals("c-1")));
    }

    @Test
    void batchStoreRejectsAChunkWithWrongDimensions() throws Exception {
        String body = "[{\"id\": \"c-1\", \"text\": \"hello\", \"source\": \"guide.pdf\", \"embedding\": " + vector(2) + "}]";

        mockMvc.perform(post("/api/v1/chunks/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("chunk c-1: embedding must have 3072 dimensions, got 2"));

        verify(repository, never()).storeBatch(any());
    }

    @Test
    void batchStoreRejectsAnEmptyList() throws Exception {
        mockMvc.perform(post("/api/v1/chunks/batch").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest());
    }

    private static String vector(int dimensions) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("0.1");
        }
        return sb.append(']').toString();
    }
}
