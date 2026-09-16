package com.bloom.eventmanager.chat.config;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** V1: ragConfig.topK = 15; ChatWithDocumentsUseCase kept the last 6 messages. */
@Validated
@ConfigurationProperties(prefix = "bloom.chat")
public record ChatProperties(@DefaultValue("15") @Min(1) int topK, @DefaultValue("6") @Min(1) int historyLimit) {
}
