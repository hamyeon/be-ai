package com.vintic.backend.common.exception;

public class CapNotIncreasedException extends RuntimeException {
    public CapNotIncreasedException(String message) {
        super(message);
    }
}
