package com.vintic.backend.common.exception;

public class BackupOfferAlreadyResolvedException extends RuntimeException {
    public BackupOfferAlreadyResolvedException(String message) {
        super(message);
    }
}
