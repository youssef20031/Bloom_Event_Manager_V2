package com.bloom.eventmanager.proposal.domain;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import feign.FeignException;
import feign.Request;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.common.error.ExternalServiceException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1 parity: the React app's re-auth handling reads {@code errorCode} and
 * {@code authUrl} off a 401 to redirect the user through Drive OAuth again.
 * These two tests pin both directions of {@link StorageErrors#translate}'s
 * branch, the way {@code chat-service}'s {@code SessionControllerTest} pins
 * its equivalent catch-order: a test that only checked the 401 branch (or
 * only the generic one) would not catch the branches being swapped.
 */
class StorageErrorsTest {

    private static final Request REQUEST = Request.create(
            "POST", "/api/v1/storage/company-files", Map.of(), new byte[0], StandardCharsets.UTF_8);

    @Test
    void a401FromStorageServiceBecomesDriveNotAuthenticated() {
        RuntimeException translated = StorageErrors.translate(
                new FeignException.Unauthorized("Unauthorized", REQUEST, new byte[0], Map.of()));

        assertThat(translated).isInstanceOf(DriveNotAuthenticatedException.class)
                .isNotInstanceOf(ExternalServiceException.class);
        assertThat(((BloomException) translated).errorCode()).isEqualTo(ErrorCode.DRIVE_NOT_AUTHENTICATED);
        assertThat(((BloomException) translated).properties())
                .containsEntry("authUrl", "/api/v1/rag/sync/auth?force=true");
    }

    @Test
    void anyOtherFailureBecomesExternalServiceException() {
        RuntimeException translated = StorageErrors.translate(new RuntimeException("storage-service unreachable"));

        assertThat(translated).isInstanceOf(ExternalServiceException.class)
                .isNotInstanceOf(DriveNotAuthenticatedException.class);
    }
}
