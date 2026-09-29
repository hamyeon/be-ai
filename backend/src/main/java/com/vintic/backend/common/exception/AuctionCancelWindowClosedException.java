package com.vintic.backend.common.exception;

public class AuctionCancelWindowClosedException extends RuntimeException {
    public AuctionCancelWindowClosedException(String message) {
        super(message);
    }
}
