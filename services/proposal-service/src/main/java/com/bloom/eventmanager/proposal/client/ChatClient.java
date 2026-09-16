package com.bloom.eventmanager.proposal.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.bloom.eventmanager.proposal.client.dto.ChatSession;
import com.bloom.eventmanager.proposal.client.dto.CountResponse;

@FeignClient(name = "chat-service")
public interface ChatClient {

    @GetMapping("/api/v1/chat/sessions/search")
    List<ChatSession> sessionsMentioning(@RequestParam("mentions") String company);

    @GetMapping("/api/v1/chat/sessions/count")
    CountResponse countMentioning(@RequestParam("mentions") String company);
}
