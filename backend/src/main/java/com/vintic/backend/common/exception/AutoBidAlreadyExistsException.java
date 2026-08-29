package com.vintic.backend.common.exception;

public class AutoBidAlreadyExistsException extends RuntimeException {
    public AutoBidAlreadyExistsException(String message) {
        super(message);
    }
}
