package com.bloom.eventmanager.ingestion.api;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.ingestion.local.IngestJob;
import com.bloom.eventmanager.ingestion.local.IngestJobRegistry;
import com.bloom.eventmanager.ingestion.local.LocalDirectoryIngestService;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IngestController.class)
class IngestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocalDirectoryIngestService ingestService;

    @MockitoBean
    private IngestJobRegistry registry;

    @Test
    void startsAJobAndReturns202() throws Exception {
        when(ingestService.start(isNull())).thenReturn(IngestJob.running("job-1"));

        mockMvc.perform(post("/api/v1/ingest").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        verify(ingestService).run("job-1", null);
    }

    @Test
    void reportsAKnownJob() throws Exception {
        when(registry.get("job-1")).thenReturn(Optional.of(IngestJob.running("job-1").completed(List.of(), List.of())));

        mockMvc.perform(get("/api/v1/ingest/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void unknownJobIs404() throws Exception {
        when(registry.get("nope")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/ingest/nope")).andExpect(status().isNotFound());
    }
}
