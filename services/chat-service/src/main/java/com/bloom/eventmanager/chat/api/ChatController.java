package com.bloom.eventmanager.chat.api;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.chat.api.dto.ChatRequest;
import com.bloom.eventmanager.chat.domain.ChatResult;
import com.bloom.eventmanager.chat.domain.ChatWithDocumentsUseCase;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatWithDocumentsUseCase useCase;

    public ChatController(ChatWithDocumentsUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    public Map<String, Object> chat(@Valid @RequestBody ChatRequest request) {
        ChatResult result = useCase.execute(request.message(), request.sessionId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("answer", result.answer());
        if (result.thoughts() != null) {
            body.put("thoughts", result.thoughts());
        }
        body.put("sessionId", result.sessionId());
        body.put("sources", result.sources());
        return body;
    }
}
