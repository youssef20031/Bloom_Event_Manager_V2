package com.bloom.eventmanager.knowledge.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.knowledge.api.dto.ChunksBySourcesRequest;
import com.bloom.eventmanager.knowledge.domain.ChunkMatch;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

@RestController
@RequestMapping("/api/v1/sources")
public class SourceController {

    private final ChunkRepository repository;

    public SourceController(ChunkRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<String> all() {
        return repository.allSources();
    }

    /** Random sample of chunks from the given sources — proposal-service's raw material. */
    @PostMapping("/chunks")
    public List<ChunkMatch> chunksBySources(@Valid @RequestBody ChunksBySourcesRequest request) {
        return repository.randomChunksFromSources(request.sources(), request.limit());
    }
}
