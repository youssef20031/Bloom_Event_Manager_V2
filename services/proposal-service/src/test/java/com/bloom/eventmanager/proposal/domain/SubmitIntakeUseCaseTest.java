package com.bloom.eventmanager.proposal.domain;

import java.util.List;

import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.proposal.client.StorageClient;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileRequest;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileResponse;
import com.bloom.eventmanager.proposal.pdf.BriefPdfRenderer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmitIntakeUseCaseTest {

    private final StorageClient storage = mock(StorageClient.class);
    private final BriefPdfRenderer renderer = mock(BriefPdfRenderer.class);
    private final SubmitIntakeUseCase useCase = new SubmitIntakeUseCase(storage, renderer);

    @Test
    void rendersTheBriefAndUploadsItIntoTheCompanyFolder() {
        EventIntakeData data = minimal("Acme/Corp: Ltd");
        when(renderer.render(data)).thenReturn(new byte[] { 1, 2, 3 });
        when(storage.uploadCompanyFile(any())).thenReturn(new CompanyFileResponse("folder", "file", "https://drive/view"));

        IntakeSubmission result = useCase.execute(data);

        ArgumentCaptor<CompanyFileRequest> request = ArgumentCaptor.forClass(CompanyFileRequest.class);
        verify(storage).uploadCompanyFile(request.capture());
        assertThat(request.getValue().companyName()).isEqualTo("Acme/Corp: Ltd");
        assertThat(request.getValue().fileName()).matches("Customer_Brief_\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}\\.pdf");
        assertThat(request.getValue().mimeType()).isEqualTo("application/pdf");
        assertThat(request.getValue().contentBase64()).isEqualTo("AQID");
        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("Brief submitted successfully and uploaded to Google Drive");
        assertThat(result.folderName()).isEqualTo("AcmeCorp Ltd");
        assertThat(result.folderId()).isEqualTo("folder");
        assertThat(result.fileId()).isEqualTo("file");
        assertThat(result.viewLink()).isEqualTo("https://drive/view");
    }

    @Test
    void requiresACompanyName() {
        assertThatThrownBy(() -> useCase.execute(minimal("  ")))
                .isInstanceOf(BloomException.class)
                .hasMessage("Company name is required");
    }

    @Test
    void translatesStorage401IntoDriveNotAuthenticated() {
        when(renderer.render(any())).thenReturn(new byte[] { 1 });
        when(storage.uploadCompanyFile(any())).thenThrow(new FeignException.Unauthorized("401",
                Request.create(Request.HttpMethod.POST, "/x", java.util.Map.of(), null, null, null), null, java.util.Map.of()));

        assertThatThrownBy(() -> useCase.execute(minimal("Acme"))).isInstanceOf(DriveNotAuthenticatedException.class);
    }

    static EventIntakeData minimal(String company) {
        return new EventIntakeData(company, null, null, null, null, List.of(), null, null, null, List.of(), null, null,
                null, null, null, null, List.of(), null, null, null, null, null, List.of(), null, List.of(), null, null,
                List.of(), List.of(), List.of(), null, null, List.of(), null, List.of(), null, null, List.of(), null, null, null);
    }
}
