package com.choruskube.core.exception;

/**
 * No repository in a run's scope belongs to the installation's account, the narrowed permission
 * set came out empty, or a narrowed mint GitHub answered with 422.
 */
public class GitHubTokenScopeException extends IllegalStateException {

    public GitHubTokenScopeException(String message) {
        super(message);
    }

    public GitHubTokenScopeException(String message, Throwable cause) {
        super(message, cause);
    }
}
