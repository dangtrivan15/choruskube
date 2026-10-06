package com.choruskube.core.exception;

/**
 * A user-facing summary of a merge-configured approval attempt's outcome: what merged, what
 * blocked, and that the gate is still open. {@link GlobalExceptionHandler} maps this to a plain
 * 409 body, same as any other {@link ConflictException} — the text is built entirely from
 * {@code PullRequestMergeService}'s own classification plus GitHub's already-sanitised refusal
 * reason, so no further filtering is needed here.
 */
public class PullRequestMergeException extends ConflictException {

    public PullRequestMergeException(String message) {
        super(message);
    }
}
