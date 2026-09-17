package com.bloom.eventmanager.chat.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.chat.client.dto.GenerateRequest;
import com.bloom.eventmanager.chat.client.dto.GenerateResponse;

@FeignClient(name = "ai-service", fallback = AiClientFallback.class)
public interface AiClient {

    @PostMapping("/api/v1/ai/generate")
    GenerateResponse generate(@RequestBody GenerateRequest request);
}
