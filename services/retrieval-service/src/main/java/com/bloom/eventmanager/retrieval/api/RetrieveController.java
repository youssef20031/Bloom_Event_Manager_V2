package com.bloom.eventmanager.retrieval.api;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.retrieval.api.dto.RetrieveRequest;
import com.bloom.eventmanager.retrieval.api.dto.RetrieveResponse;
import com.bloom.eventmanager.retrieval.domain.RetrievalResult;
import com.bloom.eventmanager.retrieval.domain.RetrievalService;

@RestController
@RequestMapping("/api/v1")
public class RetrieveController {

    private final RetrievalService retrievalService;

    public RetrieveController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/retrieve")
    public RetrieveResponse retrieve(@Valid @RequestBody RetrieveRequest request) {
        if (!request.hasQuery() && !request.hasEmbedding()) {
            // V1's validateEmbedding middleware, same message.
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "queryEmbedding must be a non-empty array");
        }

        RetrievalResult result = retrievalService.retrieve(
                request.query(), request.queryEmbedding(), request.topK(), request.useAiOrDefault());

        return new RetrieveResponse("success", result.chunks(), new RetrieveResponse.Metadata(
                result.originalQuery(),
                result.variations(),
                result.totalRetrieved(),
                result.chunks().size(),
                result.aiEnhanced()));
    }
}
