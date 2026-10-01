package com.vintic.backend.common.exception;

public class AlreadyHighestBidderException extends RuntimeException {
    public AlreadyHighestBidderException(String message) {
        super(message);
    }
}
