package com.vintic.backend.common.exception;

public class InvalidOrderStatusException extends RuntimeException {
    public InvalidOrderStatusException(String message) {
        super(message);
    }
}
