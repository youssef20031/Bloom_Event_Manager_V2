package com.bloom.eventmanager.retrieval.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** V1's RAG_TOP_K. */
@Validated
@ConfigurationProperties(prefix = "bloom.retrieval")
public record RetrievalProperties(@DefaultValue("15") @Min(1) @Max(100) int topK) {
}
