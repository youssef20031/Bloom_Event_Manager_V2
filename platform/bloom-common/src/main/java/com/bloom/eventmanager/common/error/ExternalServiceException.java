package com.bloom.eventmanager.common.error;

public class ExternalServiceException extends BloomException {

    public ExternalServiceException(String message, Throwable cause) {
        super(ErrorCode.EXTERNAL_SERVICE_ERROR, message, cause);
    }
}
