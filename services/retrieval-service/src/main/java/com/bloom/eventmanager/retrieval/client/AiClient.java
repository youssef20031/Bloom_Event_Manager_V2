package com.bloom.eventmanager.retrieval.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchResponse;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateResponse;

@FeignClient(name = "ai-service", fallback = AiClientFallback.class)
public interface AiClient {

    @PostMapping("/api/v1/ai/embed/batch")
    EmbedBatchResponse embedBatch(@RequestBody EmbedBatchRequest request);

    @PostMapping("/api/v1/ai/generate")
    GenerateResponse generate(@RequestBody GenerateRequest request);
}
