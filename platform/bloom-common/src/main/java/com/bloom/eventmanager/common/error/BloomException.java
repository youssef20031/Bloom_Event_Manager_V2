package com.bloom.eventmanager.common.error;

public class BloomException extends RuntimeException {

    private final ErrorCode errorCode;

    public BloomException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BloomException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
