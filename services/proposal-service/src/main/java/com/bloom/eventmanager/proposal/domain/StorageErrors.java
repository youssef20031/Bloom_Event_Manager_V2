package com.bloom.eventmanager.proposal.domain;

import feign.FeignException;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ExternalServiceException;

/** Turns storage-service Feign failures into the errors the SPA understands. */
public final class StorageErrors {

    private StorageErrors() {
    }

    public static RuntimeException translate(RuntimeException ex) {
        if (ex instanceof BloomException bloom) {
            // Already classified — by StorageClientFallbackFactory (503 when storage-service
            // is down or its breaker is open) or by a domain check. Re-wrapping it here was
            // masking that 503 as a 502 EXTERNAL_SERVICE_ERROR, which is not the contract
            // proposal-service states for a browser-facing failure. Same idiom as
            // GenerateProposalUseCase.safely().
            return bloom;
        }
        if (ex instanceof FeignException.Unauthorized) {
            return new DriveNotAuthenticatedException();
        }
        return new ExternalServiceException("storage-service call failed", ex);
    }
}
