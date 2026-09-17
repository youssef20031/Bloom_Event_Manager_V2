package com.bloom.eventmanager.ingestion.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.bloom.eventmanager.ingestion.client.dto.DeleteDocumentResponse;

@FeignClient(name = "knowledge-service", fallback = KnowledgeClientFallback.class)
public interface KnowledgeClient {

    @DeleteMapping("/api/v1/documents")
    DeleteDocumentResponse deleteDocument(@RequestParam("source") String source);
}
