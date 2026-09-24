package com.bloom.eventmanager.storage.client;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import feign.FeignException;
import feign.Request;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.storage.client.dto.DriveFileUpsert;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both branches of the fallback rule, pinned. Phase 5 originally registered this as a
 * blanket {@code fallback = ...} that threw ExternalServiceException for every cause, which made
 * the {@code catch (FeignException ...)} branches in the callers unreachable and turned a
 * peer's deliberate 4xx into "the peer is down". The second test fails if that regresses;
 * the first pins the unavailability contract the phase specifies for storage-service.
 */
class KnowledgeClientFallbackFactoryTest {

    private static final Request REQUEST = Request.create(
            "POST", "/api/v1/drive-files/f1", Map.of(), new byte[0], StandardCharsets.UTF_8);

    private final KnowledgeClientFallbackFactory factory = new KnowledgeClientFallbackFactory();

    @Test
    void genuineUnavailabilityBecomesExternalServiceException() {
        KnowledgeClient fallback = factory.create(new IllegalStateException("Connection refused"));

        assertThatThrownBy(() -> fallback.allDriveFiles())
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("knowledge-service");
        assertThatThrownBy(() -> fallback.driveFile("f1"))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("knowledge-service");
        assertThatThrownBy(() -> fallback.upsertDriveFile("f1", new DriveFileUpsert("a.pdf", "t1")))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("knowledge-service");
    }

    @Test
    void aClientErrorIsHandedBackUnchangedSoTheCallersFeignCatchStillFires() {
        FeignException.NotFound cause = new FeignException.NotFound("Not Found", REQUEST, new byte[0], Map.of());
        KnowledgeClient fallback = factory.create(cause);

        assertThatThrownBy(() -> fallback.allDriveFiles()).isSameAs(cause);
        assertThatThrownBy(() -> fallback.driveFile("f1")).isSameAs(cause);
        assertThatThrownBy(() -> fallback.upsertDriveFile("f1", new DriveFileUpsert("a.pdf", "t1"))).isSameAs(cause);
    }
}
