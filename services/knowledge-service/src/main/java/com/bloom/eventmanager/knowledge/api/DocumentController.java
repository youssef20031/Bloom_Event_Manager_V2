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
     */
    @DeleteMapping
    public DeleteDocumentResponse delete(@RequestParam("source") String source) {
        if (source == null || source.isBlank()) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "source is required");
        }
        return new DeleteDocumentResponse(repository.deleteDocument(source));
    }
}
