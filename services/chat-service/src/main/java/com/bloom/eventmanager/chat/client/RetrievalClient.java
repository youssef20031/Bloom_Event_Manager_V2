package com.bloom.eventmanager.chat.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.chat.client.dto.RetrieveRequest;
import com.bloom.eventmanager.chat.client.dto.RetrieveResponse;

@FeignClient(name = "retrieval-service", fallback = RetrievalClientFallback.class)
public interface RetrievalClient {

    @PostMapping("/api/v1/retrieve")
    RetrieveResponse retrieve(@RequestBody RetrieveRequest request);
}
