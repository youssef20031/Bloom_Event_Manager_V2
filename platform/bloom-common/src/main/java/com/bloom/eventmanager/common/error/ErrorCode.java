package com.bloom.eventmanager.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource Not Found"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation Failed"),
    EXTERNAL_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "External Service Error"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** Lower-kebab form used as the last segment of the problem type URI. */
    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }
}
