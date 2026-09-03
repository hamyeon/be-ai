package com.vintic.backend.common.exception;

public class NotificationNotFoundException extends RuntimeException {
    public NotificationNotFoundException(String message) {
        super(message);
    }
}
