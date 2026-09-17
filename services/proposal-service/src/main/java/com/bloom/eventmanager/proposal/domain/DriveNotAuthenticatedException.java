package com.bloom.eventmanager.proposal.domain;

import java.util.Map;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;

/** V1's requireDriveAuth response shape, raised when storage-service answers 401. */
public class DriveNotAuthenticatedException extends BloomException {

    private static final String MESSAGE = "Google Drive not authenticated.";

    public DriveNotAuthenticatedException() {
        super(ErrorCode.DRIVE_NOT_AUTHENTICATED, MESSAGE);
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("authUrl", "/api/v1/rag/sync/auth?force=true", "error", MESSAGE, "message", MESSAGE);
    }
}
