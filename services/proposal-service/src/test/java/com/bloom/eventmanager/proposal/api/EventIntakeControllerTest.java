package com.bloom.eventmanager.proposal.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.proposal.domain.DriveNotAuthenticatedException;
import com.bloom.eventmanager.proposal.domain.IntakeSubmission;
import com.bloom.eventmanager.proposal.domain.SubmitIntakeUseCase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventIntakeController.class)
class EventIntakeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubmitIntakeUseCase useCase;

    @Test
    void submitsTheBriefAndReturnsV1sShape() throws Exception {
        when(useCase.execute(argThat(d -> d != null && "Acme".equals(d.companyName()) && d.primaryGoals().contains("Bonding"))))
                .thenReturn(new IntakeSubmission(true, "Brief submitted successfully and uploaded to Google Drive",
                        "Acme", "Acme", "Customer_Brief_2026-09-15T10-00-00.pdf", "folder", "file", "link"));

        mockMvc.perform(post("/api/v1/event-intake").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\": \"Acme\", \"primaryGoals\": [\"Bonding\"], \"unknownField\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.fileName").value("Customer_Brief_2026-09-15T10-00-00.pdf"))
                .andExpect(jsonPath("$.viewLink").value("link"));
    }

    @Test
    void driveNotAuthenticatedIs401WithAuthUrl() throws Exception {
        when(useCase.execute(any())).thenThrow(new DriveNotAuthenticatedException());

        mockMvc.perform(post("/api/v1/event-intake").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\": \"Acme\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Google Drive not authenticated."))
                .andExpect(jsonPath("$.authUrl").value("/api/v1/rag/sync/auth?force=true"));
    }
}
