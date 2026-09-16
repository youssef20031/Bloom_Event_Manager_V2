package com.bloom.eventmanager.proposal.api;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.proposal.api.dto.GenerateProposalRequest;
import com.bloom.eventmanager.proposal.api.dto.SaveProposalRequest;
import com.bloom.eventmanager.proposal.client.ChatClient;
import com.bloom.eventmanager.proposal.client.StorageClient;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileRequest;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileResponse;
import com.bloom.eventmanager.proposal.client.dto.CompanyFolder;
import com.bloom.eventmanager.proposal.domain.GenerateProposalUseCase;
import com.bloom.eventmanager.proposal.domain.GeneratedProposal;
import com.bloom.eventmanager.proposal.domain.StorageErrors;
import com.bloom.eventmanager.proposal.pdf.ProposalPdfRenderer;

/** V1's proposalController. */
@RestController
@RequestMapping("/api/v1/proposals")
public class ProposalController {

    private final GenerateProposalUseCase generate;
    private final ProposalPdfRenderer renderer;
    private final StorageClient storage;
    private final ChatClient chat;

    public ProposalController(GenerateProposalUseCase generate, ProposalPdfRenderer renderer, StorageClient storage, ChatClient chat) {
        this.generate = generate;
        this.renderer = renderer;
        this.storage = storage;
        this.chat = chat;
    }

    @GetMapping("/companies")
    public Map<String, Object> companies() {
        List<CompanyFolder> folders;
        try {
            folders = storage.companies();
        }
        catch (RuntimeException ex) {
            throw StorageErrors.translate(ex);
        }
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (CompanyFolder folder : folders) {
            long chatCount;
            try {
                chatCount = chat.countMentioning(folder.name()).count();
            }
            catch (RuntimeException ex) {
                chatCount = 0; // chat history is an enrichment, not a dependency
            }
            Map<String, Object> company = new LinkedHashMap<>();
            company.put("id", folder.id());
            company.put("name", folder.name());
            company.put("briefCount", folder.briefCount());
            company.put("chatCount", chatCount);
            enriched.add(company);
        }
        return Map.of("companies", enriched);
    }

    @PostMapping("/generate")
    public GeneratedProposal generate(@RequestBody GenerateProposalRequest request) {
        return generate.execute(request.companyName());
    }

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody SaveProposalRequest request) {
        requireComplete(request);
        byte[] pdf = renderer.render(request.slides(), request.companyName());
        String fileName = request.companyName() + "_Proposal_" + LocalDate.now(ZoneId.systemDefault()) + ".pdf";
        CompanyFileResponse upload;
        try {
            upload = storage.uploadCompanyFile(new CompanyFileRequest(request.companyName(), fileName, "application/pdf",
                    Base64.getEncoder().encodeToString(pdf)));
        }
        catch (RuntimeException ex) {
            throw StorageErrors.translate(ex);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("fileName", fileName);
        body.put("fileId", upload.fileId());
        body.put("viewLink", upload.webViewLink());
        return body;
    }

    @PostMapping(value = "/download", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> download(@RequestBody SaveProposalRequest request) {
        requireComplete(request);
        byte[] pdf = renderer.render(request.slides(), request.companyName());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"" + request.companyName() + "_Proposal.pdf\"")
                .contentLength(pdf.length)
                .body(pdf);
    }

    private static void requireComplete(SaveProposalRequest request) {
        if (request == null || !request.isComplete()) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "slides and companyName are required");
        }
    }
}
