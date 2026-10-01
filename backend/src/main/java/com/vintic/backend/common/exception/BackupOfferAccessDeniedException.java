package com.vintic.backend.common.exception;

public class BackupOfferAccessDeniedException extends RuntimeException {
    public BackupOfferAccessDeniedException(String message) {
        super(message);
    }
}
