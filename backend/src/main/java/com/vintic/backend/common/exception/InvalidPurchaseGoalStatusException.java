package com.vintic.backend.common.exception;

public class InvalidPurchaseGoalStatusException extends RuntimeException {
    public InvalidPurchaseGoalStatusException(String message) {
        super(message);
    }
}
