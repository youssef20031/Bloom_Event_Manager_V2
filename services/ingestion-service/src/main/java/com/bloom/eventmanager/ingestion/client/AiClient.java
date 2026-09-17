package com.bloom.eventmanager.ingestion.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.ingestion.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.ingestion.client.dto.EmbedBatchResponse;

@FeignClient(name = "ai-service", fallback = AiClientFallback.class)
public interface AiClient {

    @PostMapping("/api/v1/ai/embed/batch")
    EmbedBatchResponse embedBatch(@RequestBody EmbedBatchRequest request);
}
