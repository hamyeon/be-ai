package com.vintic.backend.common.exception;

public class InvalidAuctionTimeException extends RuntimeException {
    public InvalidAuctionTimeException(String message) {
        super(message);
    }
}
