package com.bloom.eventmanager.proposal.domain;

import feign.FeignException;

import com.bloom.eventmanager.common.error.ExternalServiceException;

/** Turns storage-service Feign failures into the errors the SPA understands. */
public final class StorageErrors {

    private StorageErrors() {
    }

    public static RuntimeException translate(RuntimeException ex) {
        if (ex instanceof FeignException.Unauthorized) {
            return new DriveNotAuthenticatedException();
        }
        return new ExternalServiceException("storage-service call failed", ex);
    }
}
