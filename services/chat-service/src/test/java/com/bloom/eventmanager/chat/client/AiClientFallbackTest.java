package com.bloom.eventmanager.chat.client;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.chat.client.dto.GenerateRequest;
import com.bloom.eventmanager.common.error.ServiceUnavailableException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiClientFallbackTest {

    private final AiClientFallback fallback = new AiClientFallback();

    @Test
    void generateThrowsServiceUnavailableSoTheBrowserSeesAClean503() {
        assertThatThrownBy(() -> fallback.generate(new GenerateRequest("x", null)))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("ai-service");
    }
}
