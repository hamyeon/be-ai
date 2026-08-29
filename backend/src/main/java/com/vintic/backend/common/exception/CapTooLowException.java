package com.vintic.backend.common.exception;

public class CapTooLowException extends RuntimeException {
    public CapTooLowException(String message) {
        super(message);
    }
}
