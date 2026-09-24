package com.bloom.eventmanager.proposal.client;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import feign.FeignException;
import feign.Request;

import com.bloom.eventmanager.common.error.ServiceUnavailableException;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both branches of the fallback rule, pinned. Phase 5 originally registered this as a
 * blanket {@code fallback = ...} that threw ServiceUnavailableException for every cause, which made
 * the {@code catch (FeignException ...)} branches in the callers unreachable and turned a
 * peer's deliberate 4xx into "the peer is down". The second test fails if that regresses;
 * the first pins the unavailability contract the phase specifies for proposal-service.
 */
class StorageClientFallbackFactoryTest {

    private static final Request REQUEST = Request.create(
            "POST", "/api/v1/storage/companies", Map.of(), new byte[0], StandardCharsets.UTF_8);

    private final StorageClientFallbackFactory factory = new StorageClientFallbackFactory();

    @Test
    void genuineUnavailabilityBecomesServiceUnavailableException() {
        StorageClient fallback = factory.create(new IllegalStateException("Connection refused"));

        assertThatThrownBy(() -> fallback.companies())
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("storage-service");
        assertThatThrownBy(() -> fallback.uploadCompanyFile(new CompanyFileRequest("Acme", "p.pdf", "application/pdf", "YQ==")))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("storage-service");
    }

    @Test
    void aClientErrorIsHandedBackUnchangedSoTheCallersFeignCatchStillFires() {
        FeignException.Unauthorized cause = new FeignException.Unauthorized("Unauthorized", REQUEST, new byte[0], Map.of());
        StorageClient fallback = factory.create(cause);

        assertThatThrownBy(() -> fallback.companies()).isSameAs(cause);
        assertThatThrownBy(() -> fallback.uploadCompanyFile(new CompanyFileRequest("Acme", "p.pdf", "application/pdf", "YQ=="))).isSameAs(cause);
    }
}
