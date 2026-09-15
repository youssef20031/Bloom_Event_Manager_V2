package com.bloom.eventmanager.knowledge.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.knowledge.api.dto.ChunkSearchRequest;
import com.bloom.eventmanager.knowledge.config.KnowledgeProperties;
import com.bloom.eventmanager.knowledge.domain.ChunkMatch;
import com.bloom.eventmanager.knowledge.domain.ChunkRecord;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

@RestController
@RequestMapping("/api/v1/chunks")
public class ChunkController {

    private final ChunkRepository repository;
    private final KnowledgeProperties properties;

    public ChunkController(ChunkRepository repository, KnowledgeProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @PostMapping("/search")
    public List<ChunkMatch> search(@Valid @RequestBody ChunkSearchRequest request) {
        requireDimensions(request.embedding(), "");
        return repository.searchSimilar(request.embedding(), request.topK());
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void storeBatch(@RequestBody List<ChunkRecord> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "chunks must not be empty");
        }
        for (ChunkRecord chunk : chunks) {
            if (chunk.id() == null || chunk.id().isBlank()) {
                throw new BloomException(ErrorCode.VALIDATION_FAILED, "every chunk needs an id");
            }
            if (chunk.source() == null || chunk.source().isBlank()) {
                throw new BloomException(ErrorCode.VALIDATION_FAILED, "chunk " + chunk.id() + ": source is required");
            }
            if (chunk.text() == null) {
                throw new BloomException(ErrorCode.VALIDATION_FAILED, "chunk " + chunk.id() + ": text is required");
            }
            requireDimensions(chunk.embedding(), "chunk " + chunk.id() + ": ");
        }
        repository.storeBatch(chunks);
    }

    private void requireDimensions(float[] embedding, String context) {
        int expected = properties.embeddingDimensions();
        if (embedding == null) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, context + "embedding is required");
        }
        if (embedding.length != expected) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED,
                    context + "embedding must have " + expected + " dimensions, got " + embedding.length);
        }
    }
}
