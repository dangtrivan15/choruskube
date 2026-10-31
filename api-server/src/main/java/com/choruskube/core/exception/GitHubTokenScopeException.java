package com.choruskube.core.exception;

/**
 * No repository in a run's scope belongs to the installation's account, the narrowed permission
 * set came out empty, or a narrowed mint GitHub answered with 422. Extends {@link
 * IllegalStateException} so a caller resolving a run's credential surfaces the message as the
 * gate's refusal reason without a code change there.
 */
public class GitHubTokenScopeException extends IllegalStateException {

    public GitHubTokenScopeException(String message) {
        super(message);
    }

    public GitHubTokenScopeException(String message, Throwable cause) {
        super(message, cause);
    }
}
