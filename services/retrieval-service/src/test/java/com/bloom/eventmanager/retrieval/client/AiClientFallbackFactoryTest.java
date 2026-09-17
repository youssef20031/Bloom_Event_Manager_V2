package com.bloom.eventmanager.retrieval.client;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;

import org.junit.jupiter.api.Test;

import feign.FeignException;
import feign.Request;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both branches of the fallback rule, pinned. Phase 5 originally registered this as a
 * blanket {@code fallback = ...} that threw ExternalServiceException for every cause, which made
 * the {@code catch (FeignException ...)} branches in the callers unreachable and turned a
 * peer's deliberate 4xx into "the peer is down". The second test fails if that regresses;
 * the first pins the unavailability contract the phase specifies for retrieval-service.
 */
class AiClientFallbackFactoryTest {

    private static final Request REQUEST = Request.create(
            "POST", "/api/v1/ai/embed/batch", Map.of(), new byte[0], StandardCharsets.UTF_8);

    private final AiClientFallbackFactory factory = new AiClientFallbackFactory();

    @Test
    void genuineUnavailabilityBecomesExternalServiceException() {
        AiClient fallback = factory.create(new IllegalStateException("Connection refused"));

        assertThatThrownBy(() -> fallback.embedBatch(new EmbedBatchRequest(List.of("x"))))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("ai-service");
        assertThatThrownBy(() -> fallback.generate(new GenerateRequest("x")))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("ai-service");
    }

    @Test
    void aClientErrorIsHandedBackUnchangedSoTheCallersFeignCatchStillFires() {
        FeignException.BadRequest cause = new FeignException.BadRequest("Bad Request", REQUEST, new byte[0], Map.of());
        AiClient fallback = factory.create(cause);

        assertThatThrownBy(() -> fallback.embedBatch(new EmbedBatchRequest(List.of("x")))).isSameAs(cause);
        assertThatThrownBy(() -> fallback.generate(new GenerateRequest("x"))).isSameAs(cause);
    }
}
