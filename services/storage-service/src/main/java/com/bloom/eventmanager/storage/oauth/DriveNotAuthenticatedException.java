package com.bloom.eventmanager.storage.oauth;

import java.util.Map;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;

/**
 * 401 with the extra members V1's requireDriveAuth response carried
 * ({@code error}, {@code message}, {@code authUrl}); the React app reads them.
 */
public class DriveNotAuthenticatedException extends BloomException {

    public static final String REAUTH_URL = "/api/v1/rag/sync/auth?force=true";
    private static final String MESSAGE = "Google Drive not authenticated.";

    public DriveNotAuthenticatedException() {
        super(ErrorCode.DRIVE_NOT_AUTHENTICATED, MESSAGE);
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("authUrl", REAUTH_URL, "error", MESSAGE, "message", MESSAGE);
    }
}
