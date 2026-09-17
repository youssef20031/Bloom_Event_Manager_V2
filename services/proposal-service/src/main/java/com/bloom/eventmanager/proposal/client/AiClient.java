package com.bloom.eventmanager.proposal.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.proposal.client.dto.EmbedRequest;
import com.bloom.eventmanager.proposal.client.dto.EmbedResponse;
import com.bloom.eventmanager.proposal.client.dto.GenerateRequest;
import com.bloom.eventmanager.proposal.client.dto.GenerateResponse;

@FeignClient(name = "ai-service", fallback = AiClientFallback.class)
public interface AiClient {

    @PostMapping("/api/v1/ai/embed")
    EmbedResponse embed(@RequestBody EmbedRequest request);

    @PostMapping("/api/v1/ai/generate")
    GenerateResponse generate(@RequestBody GenerateRequest request);
}
