package com.vintic.backend.common.exception;

public class PromptTemplateNotFoundException extends RuntimeException {
    public PromptTemplateNotFoundException(String message) {
        super(message);
    }

    public PromptTemplateNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
