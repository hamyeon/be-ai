package com.vintic.backend.common.exception;

public class AuctionRegistrationLimitExceededException extends RuntimeException {
    public AuctionRegistrationLimitExceededException(String message) {
        super(message);
    }
}
