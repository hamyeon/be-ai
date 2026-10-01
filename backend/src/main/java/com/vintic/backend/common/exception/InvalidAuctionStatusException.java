package com.vintic.backend.common.exception;

public class InvalidAuctionStatusException extends RuntimeException {
    public InvalidAuctionStatusException(String message) {
        super(message);
    }
}
