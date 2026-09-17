package com.bloom.eventmanager.ingestion.client;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import feign.FeignException;
import feign.Request;

import com.bloom.eventmanager.common.error.ExternalServiceException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both branches of the fallback rule, pinned. Phase 5 originally registered this as a
 * blanket {@code fallback = ...} that threw ExternalServiceException for every cause, which made
 * the {@code catch (FeignException ...)} branches in the callers unreachable and turned a
 * peer's deliberate 4xx into "the peer is down". The second test fails if that regresses;
 * the first pins the unavailability contract the phase specifies for ingestion-service.
 */
class StorageClientFallbackFactoryTest {

    private static final Request REQUEST = Request.create(
            "POST", "/api/v1/storage/files/drive-1/content", Map.of(), new byte[0], StandardCharsets.UTF_8);

    private final StorageClientFallbackFactory factory = new StorageClientFallbackFactory();

    @Test
    void genuineUnavailabilityBecomesExternalServiceException() {
        StorageClient fallback = factory.create(new IllegalStateException("Connection refused"));

        assertThatThrownBy(() -> fallback.download("drive-1"))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("storage-service");
    }

    @Test
    void aClientErrorIsHandedBackUnchangedSoTheCallersFeignCatchStillFires() {
        FeignException.NotFound cause = new FeignException.NotFound("Not Found", REQUEST, new byte[0], Map.of());
        StorageClient fallback = factory.create(cause);

        assertThatThrownBy(() -> fallback.download("drive-1")).isSameAs(cause);
    }
}
