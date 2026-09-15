package com.bloom.eventmanager.knowledge.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Vector index settings. Cypher cannot parameterise an index name, so it is
 * interpolated into CREATE VECTOR INDEX — the pattern restricts it to
 * identifier characters, which rules out Cypher injection through config.
 */
@Validated
@ConfigurationProperties(prefix = "bloom.knowledge")
public record KnowledgeProperties(
        @DefaultValue("chunk_embeddings") @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*") String vectorIndexName,
        @DefaultValue("3072") @Min(1) int embeddingDimensions,
        @DefaultValue("cosine") @Pattern(regexp = "cosine|euclidean") String similarityFunction) {
}
