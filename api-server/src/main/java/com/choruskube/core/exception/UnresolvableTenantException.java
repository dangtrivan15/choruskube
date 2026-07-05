package com.choruskube.core.exception;

/**
 * Thrown when a request's organizational context cannot be determined.
 * Mapped to HTTP 403 by GlobalExceptionHandler.
 */
public class UnresolvableTenantException extends RuntimeException {
    public UnresolvableTenantException(String message) {
        super(message);
    }

    public UnresolvableTenantException(String message, Throwable cause) {
        super(message, cause);
    }
}
