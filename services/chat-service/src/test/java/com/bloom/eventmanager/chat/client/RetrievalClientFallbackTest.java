package com.bloom.eventmanager.chat.client;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.chat.client.dto.RetrieveRequest;
import com.bloom.eventmanager.common.error.ServiceUnavailableException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalClientFallbackTest {

    private final RetrievalClientFallback fallback = new RetrievalClientFallback();

    @Test
    void retrieveThrowsServiceUnavailableSoTheBrowserSeesAClean503() {
        assertThatThrownBy(() -> fallback.retrieve(new RetrieveRequest("x", 15, false)))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("retrieval-service");
    }
}
