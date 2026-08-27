package com.choruskube.core.exception;

import com.choruskube.core.model.enums.WorkItemStatus;

/**
 * Thrown when a Task status write requests a transition outside the validated
 * whitelist ({@code backlog→in_progress}, {@code in_progress→done}, {@code in_progress→backlog}
 * on the public path; the internal/agent mirror further restricts this to just the latter two,
 * since agents report outcomes of a run already in progress rather than self-initiating one).
 *
 * <p>Extends {@link ConflictException} so it maps to {@code 409 Conflict} via the existing
 * {@code GlobalExceptionHandler} entry for {@link ConflictException} — Spring's exception-handler
 * resolution dispatches a subclass to its nearest registered handler, so no new handler method is
 * needed.
 */
public class InvalidStatusTransitionException extends ConflictException {
    public InvalidStatusTransitionException(WorkItemStatus current, WorkItemStatus target) {
        super("Cannot transition Task from " + current + " to " + target);
    }
}
