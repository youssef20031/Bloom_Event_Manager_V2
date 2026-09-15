package com.bloom.eventmanager.knowledge.api;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.knowledge.config.KnowledgeProperties;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;
import com.bloom.eventmanager.knowledge.domain.DriveFileMetadata;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ DriveFileController.class, DocumentController.class, SourceController.class })
@EnableConfigurationProperties(KnowledgeProperties.class)
class DriveFileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChunkRepository repository;

    @Test
    void listsAllDriveFileMetadataAsAMap() throws Exception {
        when(repository.allDriveFileMetadata()).thenReturn(Map.of("drive-1", "2026-09-01T00:00:00Z"));

        mockMvc.perform(get("/api/v1/drive-files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['drive-1']").value("2026-09-01T00:00:00Z"));
    }

    @Test
    void returns404ForUnknownDriveFile() throws Exception {
        when(repository.driveFileMetadata("nope")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/drive-files/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void returnsMetadataForAKnownDriveFile() throws Exception {
        when(repository.driveFileMetadata("drive-1"))
                .thenReturn(Optional.of(new DriveFileMetadata("brief.pdf", "2026-09-01T00:00:00Z")));

        mockMvc.perform(get("/api/v1/drive-files/drive-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("brief.pdf"));
    }

    @Test
    void upsertsMetadataAndReturns204() throws Exception {
        mockMvc.perform(put("/api/v1/drive-files/drive-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\": \"brief.pdf\", \"modifiedTime\": \"2026-09-01T00:00:00Z\"}"))
                .andExpect(status().isNoContent());

        verify(repository).upsertDriveFileMetadata("brief.pdf", "drive-1", "2026-09-01T00:00:00Z");
    }

    @Test
    void upsertRejectsBlankSource() throws Exception {
        mockMvc.perform(put("/api/v1/drive-files/drive-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\": \"\", \"modifiedTime\": \"2026-09-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void deletesADocumentByQueryParameterSoSlashesInSourcesWork() throws Exception {
        when(repository.deleteDocument("Clients/Acme/brief.pdf")).thenReturn(7);

        mockMvc.perform(delete("/api/v1/documents").param("source", "Clients/Acme/brief.pdf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(7));
    }

    @Test
    void deleteRequiresASource() throws Exception {
        mockMvc.perform(delete("/api/v1/documents").param("source", "  "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteWithNoSourceParameterAtAllIsA400NotA500() throws Exception {
        // Distinct from the blank-source case: a missing required @RequestParam is
        // thrown during argument resolution, before the controller body runs, so
        // the in-method guard never sees it.
        mockMvc.perform(delete("/api/v1/documents"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void chunksBySourcesDefaultsLimitToFiftyWhenAbsent() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/sources/chunks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sources\": [\"a.pdf\"]}"))
                .andExpect(status().isOk());

        verify(repository).randomChunksFromSources(java.util.List.of("a.pdf"), 50);
    }

    @Test
    void listsSources() throws Exception {
        when(repository.allSources()).thenReturn(java.util.List.of("a.pdf", "b.pdf"));

        mockMvc.perform(get("/api/v1/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1]").value("b.pdf"));
    }
}
