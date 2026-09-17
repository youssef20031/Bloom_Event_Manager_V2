package com.bloom.eventmanager.common.error;

/** A peer service's circuit is open or it cannot be reached. Retry later; nothing is wrong with the request. */
public class ServiceUnavailableException extends BloomException {

    public ServiceUnavailableException(String message, Throwable cause) {
        super(ErrorCode.SERVICE_UNAVAILABLE, message, cause);
    }
}
