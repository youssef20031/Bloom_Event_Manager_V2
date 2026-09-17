package com.bloom.eventmanager.proposal.api;

import java.util.List;
import java.util.Map;

import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.proposal.client.ChatClient;
import com.bloom.eventmanager.proposal.client.StorageClient;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileResponse;
import com.bloom.eventmanager.proposal.client.dto.CompanyFolder;
import com.bloom.eventmanager.proposal.client.dto.CountResponse;
import com.bloom.eventmanager.proposal.domain.GenerateProposalUseCase;
import com.bloom.eventmanager.proposal.domain.GeneratedProposal;
import com.bloom.eventmanager.proposal.domain.ProposalSlide;
import com.bloom.eventmanager.proposal.pdf.ProposalPdfRenderer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProposalController.class)
class ProposalControllerTest {

    private static final String SLIDES_BODY = "{\"companyName\": \"Acme\", \"slides\": [{\"slideNumber\": 1, \"type\": \"cover\", \"title\": \"Acme\", \"content\": {\"companyName\": \"Acme\"}}]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GenerateProposalUseCase generate;

    @MockitoBean
    private ProposalPdfRenderer renderer;

    @MockitoBean
    private StorageClient storage;

    @MockitoBean
    private ChatClient chat;

    @Test
    void listsCompaniesEnrichedWithChatCounts() throws Exception {
        when(storage.companies()).thenReturn(List.of(new CompanyFolder("f1", "Acme", 2)));
        when(chat.countMentioning("Acme")).thenReturn(new CountResponse(3));

        mockMvc.perform(get("/api/v1/proposals/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[0].id").value("f1"))
                .andExpect(jsonPath("$.companies[0].briefCount").value(2))
                .andExpect(jsonPath("$.companies[0].chatCount").value(3));
    }

    @Test
    void companiesWithoutDriveAuthIs401WithReauthUrl() throws Exception {
        when(storage.companies()).thenThrow(new FeignException.Unauthorized("401",
                Request.create(Request.HttpMethod.GET, "/x", Map.of(), null, null, null), null, Map.of()));

        mockMvc.perform(get("/api/v1/proposals/companies"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("DRIVE_NOT_AUTHENTICATED"))
                .andExpect(jsonPath("$.authUrl").value("/api/v1/rag/sync/auth?force=true"));
    }

    @Test
    void generatesAProposal() throws Exception {
        when(generate.execute("Acme")).thenReturn(new GeneratedProposal(
                List.of(new ProposalSlide(1, "cover", "Acme", "Event Proposal", Map.of("companyName", "Acme"))), "Acme", "2026-09-15T10:00:00Z"));

        mockMvc.perform(post("/api/v1/proposals/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\": \"Acme\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("Acme"))
                .andExpect(jsonPath("$.slides[0].type").value("cover"))
                .andExpect(jsonPath("$.generatedAt").value("2026-09-15T10:00:00Z"));
    }

    @Test
    void savesToDriveWithV1sFileName() throws Exception {
        when(renderer.render(any(), eq("Acme"))).thenReturn(new byte[] { 5 });
        when(storage.uploadCompanyFile(argThat(r -> r != null && r.companyName().equals("Acme")
                && r.fileName().matches("Acme_Proposal_\\d{4}-\\d{2}-\\d{2}\\.pdf") && r.contentBase64().equals("BQ=="))))
                .thenReturn(new CompanyFileResponse("folder", "file", "link"));

        mockMvc.perform(post("/api/v1/proposals/save").contentType(MediaType.APPLICATION_JSON).content(SLIDES_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.fileName").value(org.hamcrest.Matchers.matchesPattern("Acme_Proposal_\\d{4}-\\d{2}-\\d{2}\\.pdf")))
                .andExpect(jsonPath("$.fileId").value("file"))
                .andExpect(jsonPath("$.viewLink").value("link"));
    }

    @Test
    void saveAndDownloadRequireSlidesAndCompany() throws Exception {
        mockMvc.perform(post("/api/v1/proposals/save").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\": \"Acme\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("slides and companyName are required"));
        mockMvc.perform(post("/api/v1/proposals/download").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slides\": []}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadsThePdfAsAnAttachment() throws Exception {
        when(renderer.render(any(), eq("Acme"))).thenReturn("%PDF".getBytes());

        mockMvc.perform(post("/api/v1/proposals/download").contentType(MediaType.APPLICATION_JSON).content(SLIDES_BODY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"Acme_Proposal.pdf\""))
                .andExpect(content().bytes("%PDF".getBytes()));
    }
}
