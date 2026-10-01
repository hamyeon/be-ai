package com.vintic.backend.common.exception;

public class BackupOfferExpiredException extends RuntimeException {
    public BackupOfferExpiredException(String message) {
        super(message);
    }
}
