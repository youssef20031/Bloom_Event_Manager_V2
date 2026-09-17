package com.bloom.eventmanager.retrieval.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.client.dto.ChunkSearchRequest;

@FeignClient(name = "knowledge-service", fallback = KnowledgeClientFallback.class)
public interface KnowledgeClient {

    @PostMapping("/api/v1/chunks/search")
    List<ChunkMatch> search(@RequestBody ChunkSearchRequest request);
}
