package com.vintic.backend.common.exception;

public class ActiveAuctionAlreadyExistsException extends RuntimeException {
    public ActiveAuctionAlreadyExistsException(String message) {
        super(message);
    }
}
