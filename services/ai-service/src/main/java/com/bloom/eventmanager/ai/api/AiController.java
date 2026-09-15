package com.bloom.eventmanager.ai.api;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.ai.api.dto.EmbedBatchRequest;
import com.bloom.eventmanager.ai.api.dto.EmbedBatchResponse;
import com.bloom.eventmanager.ai.api.dto.EmbedRequest;
import com.bloom.eventmanager.ai.api.dto.EmbedResponse;
import com.bloom.eventmanager.ai.api.dto.GenerateRequest;
import com.bloom.eventmanager.ai.api.dto.GenerateResponse;
import com.bloom.eventmanager.ai.domain.EmbeddingPort;
import com.bloom.eventmanager.ai.domain.GenerationOptions;
import com.bloom.eventmanager.ai.domain.GenerationPort;
import com.bloom.eventmanager.ai.domain.GenerationResult;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final EmbeddingPort embeddings;
    private final GenerationPort generation;

    public AiController(EmbeddingPort embeddings, GenerationPort generation) {
        this.embeddings = embeddings;
        this.generation = generation;
    }

    @PostMapping("/embed")
    public EmbedResponse embed(@Valid @RequestBody EmbedRequest request) {
        return new EmbedResponse(embeddings.embed(request.text()));
    }

    @PostMapping("/embed/batch")
    public EmbedBatchResponse embedBatch(@Valid @RequestBody EmbedBatchRequest request) {
        return new EmbedBatchResponse(embeddings.embedBatch(request.texts()));
    }

    @PostMapping("/generate")
    public GenerateResponse generate(@Valid @RequestBody GenerateRequest request) {
        GenerationOptions options = request.config() == null ? GenerationOptions.defaults() : request.config();
        GenerationResult result = generation.generate(request.input(), options);
        return new GenerateResponse(result.text(), result.thoughts());
    }
}
