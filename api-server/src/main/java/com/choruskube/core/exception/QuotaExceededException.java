package com.choruskube.core.exception;

/**
 * Thrown when a tier quota rejects an operation. Declared here rather than in the module that
 * enforces quotas because {@link com.choruskube.core.service.QuotaChecker} is an OSS seam: core
 * callers must be able to distinguish quota back-pressure from a genuine failure, and they cannot
 * catch a class they do not have.
 *
 * <p>The Autopilot depends on that distinction — hitting a quota ends a tick without counting
 * toward the failure breaker, because the work is fine and only the moment is wrong.
 *
 * <p>Implementations of {@code QuotaChecker} outside this repository must throw THIS class.
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
