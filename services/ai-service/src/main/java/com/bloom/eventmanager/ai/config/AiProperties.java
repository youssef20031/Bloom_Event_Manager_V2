package com.bloom.eventmanager.ai.config;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Gemini and cache settings. The API key is the only secret this service holds
 * and the only module in the system that holds it.
 *
 * <p>Defaults track V1's CONSTANTS. {@code fallbackModels} differs on purpose:
 * V1's list named Gemini 1.5 models that no longer exist, so its fallback chain
 * had silently become "fail, fail, fail". Override via config as models evolve.
 */
@Validated
@ConfigurationProperties(prefix = "bloom.ai")
public record AiProperties(
        @NotBlank String apiKey,
        @DefaultValue("gemini-3-flash-preview") String model,
        @DefaultValue({ "gemini-2.5-flash", "gemini-2.0-flash-001" }) List<String> fallbackModels,
        @DefaultValue("gemini-embedding-001") String embeddingModel,
        @DefaultValue("3072") @Min(1) int embeddingDimensions,
        @DefaultValue("20") @Min(1) @Max(100) int embeddingBatchSize,
        @DefaultValue("bloom:") String cacheKeyPrefix) {
}
