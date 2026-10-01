package com.vintic.backend.common.exception;

public class BidAmountTooLowException extends RuntimeException {
    public BidAmountTooLowException(String message) {
        super(message);
    }
}
