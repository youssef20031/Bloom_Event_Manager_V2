package com.bloom.eventmanager.knowledge.api;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.knowledge.api.dto.DeleteDocumentResponse;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final ChunkRepository repository;

    public DocumentController(ChunkRepository repository) {
        this.repository = repository;
    }

    /**
     * Query parameter rather than path variable: sources are file names, and
     * Drive files in subfolders carry slashes ("Clients/Acme/brief.pdf").
     *
     * <p>Declared {@code required = false} so an absent parameter arrives here as
     * null and is rejected as a 400 by the guard below. Left required, Spring
     * throws during argument resolution — before this method runs — and that
     * exception reaches the shared advice's catch-all, reporting a forgotten
     * parameter as a 500.
     */
    @DeleteMapping
    public DeleteDocumentResponse delete(@RequestParam(name = "source", required = false) String source) {
        if (source == null || source.isBlank()) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "source is required");
        }
        return new DeleteDocumentResponse(repository.deleteDocument(source));
    }
}
