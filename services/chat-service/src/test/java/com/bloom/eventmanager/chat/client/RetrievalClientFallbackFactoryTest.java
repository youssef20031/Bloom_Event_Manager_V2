package com.bloom.eventmanager.chat.client;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import feign.FeignException;
import feign.Request;

import com.bloom.eventmanager.chat.client.dto.RetrieveRequest;
import com.bloom.eventmanager.common.error.ServiceUnavailableException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both branches of the fallback rule, pinned. Phase 5 originally registered this as a
 * blanket {@code fallback = ...} that threw ServiceUnavailableException for every cause, which made
 * the {@code catch (FeignException ...)} branches in the callers unreachable and turned a
 * peer's deliberate 4xx into "the peer is down". The second test fails if that regresses;
 * the first pins the unavailability contract the phase specifies for chat-service.
 */
class RetrievalClientFallbackFactoryTest {

    private static final Request REQUEST = Request.create(
            "POST", "/api/v1/retrieve", Map.of(), new byte[0], StandardCharsets.UTF_8);

    private final RetrievalClientFallbackFactory factory = new RetrievalClientFallbackFactory();

    @Test
    void genuineUnavailabilityBecomesServiceUnavailableException() {
        RetrievalClient fallback = factory.create(new IllegalStateException("Connection refused"));

        assertThatThrownBy(() -> fallback.retrieve(new RetrieveRequest("x", 15, false)))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("retrieval-service");
    }

    @Test
    void aClientErrorIsHandedBackUnchangedSoTheCallersFeignCatchStillFires() {
        FeignException.BadRequest cause = new FeignException.BadRequest("Bad Request", REQUEST, new byte[0], Map.of());
        RetrievalClient fallback = factory.create(cause);

        assertThatThrownBy(() -> fallback.retrieve(new RetrieveRequest("x", 15, false))).isSameAs(cause);
    }
}
