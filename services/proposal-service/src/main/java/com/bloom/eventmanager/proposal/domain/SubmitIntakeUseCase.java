package com.bloom.eventmanager.proposal.domain;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.proposal.client.StorageClient;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileRequest;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileResponse;
import com.bloom.eventmanager.proposal.pdf.BriefPdfRenderer;

/** V1's SubmitProposalUseCase: render the brief, upload it into the company's Drive folder. */
@Service
public class SubmitIntakeUseCase {

    private static final Logger log = LoggerFactory.getLogger(SubmitIntakeUseCase.class);

    private final StorageClient storage;
    private final BriefPdfRenderer renderer;

    public SubmitIntakeUseCase(StorageClient storage, BriefPdfRenderer renderer) {
        this.storage = storage;
        this.renderer = renderer;
    }

    public IntakeSubmission execute(EventIntakeData data) {
        if (data.companyName() == null || data.companyName().isBlank()) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "Company name is required");
        }
        // V1: new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19) -> "2026-09-15T10-30-45"
        String stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replaceAll("[:.]", "-").substring(0, 19);
        String fileName = "Customer_Brief_" + stamp + ".pdf";
        byte[] pdf = renderer.render(data);

        CompanyFileResponse upload;
        try {
            upload = storage.uploadCompanyFile(new CompanyFileRequest(data.companyName(), fileName, "application/pdf",
                    Base64.getEncoder().encodeToString(pdf)));
        }
        catch (RuntimeException ex) {
            throw StorageErrors.translate(ex);
        }
        log.info("Customer brief uploaded for: {}", data.companyName());

        return new IntakeSubmission(true, "Brief submitted successfully and uploaded to Google Drive",
                data.companyName(), sanitizeFolderName(data.companyName()), fileName,
                upload.folderId(), upload.fileId(), upload.webViewLink());
    }

    /** Same rule storage-service applies (Phase 3b): strip path characters, keep spaces. */
    static String sanitizeFolderName(String name) {
        String cleaned = name.replaceAll("[<>:\"/\\\\|?*]", "").trim();
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }
}
