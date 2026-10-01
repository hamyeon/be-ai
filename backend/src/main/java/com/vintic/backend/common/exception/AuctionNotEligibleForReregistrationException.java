package com.vintic.backend.common.exception;

public class AuctionNotEligibleForReregistrationException extends RuntimeException {
    public AuctionNotEligibleForReregistrationException(String message) {
        super(message);
    }
}
