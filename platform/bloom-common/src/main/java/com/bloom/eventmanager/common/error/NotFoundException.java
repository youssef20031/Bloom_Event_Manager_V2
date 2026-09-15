package com.bloom.eventmanager.common.error;

public class NotFoundException extends BloomException {

    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}
