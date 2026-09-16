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

    /**
     * Extra members for the RFC 9457 body. Subclasses override to add things
     * like a re-auth URL; the handler copies every entry onto the ProblemDetail.
     */
    public java.util.Map<String, Object> properties() {
        return java.util.Map.of();
    }
}
