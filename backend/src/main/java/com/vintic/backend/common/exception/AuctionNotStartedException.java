package com.vintic.backend.common.exception;

public class AuctionNotStartedException extends RuntimeException {
    public AuctionNotStartedException(String message) {
        super(message);
    }
}
