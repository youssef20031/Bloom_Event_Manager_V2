package com.bloom.eventmanager.proposal.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.proposal.client.dto.ChunkMatch;
import com.bloom.eventmanager.proposal.client.dto.ChunkSearchRequest;
import com.bloom.eventmanager.proposal.client.dto.ChunksBySourcesRequest;

@FeignClient(name = "knowledge-service", fallbackFactory = KnowledgeClientFallbackFactory.class)
public interface KnowledgeClient {

    @GetMapping("/api/v1/sources")
    List<String> allSources();

    @PostMapping("/api/v1/sources/chunks")
    List<ChunkMatch> chunksBySources(@RequestBody ChunksBySourcesRequest request);

    @PostMapping("/api/v1/chunks/search")
    List<ChunkMatch> search(@RequestBody ChunkSearchRequest request);
}
