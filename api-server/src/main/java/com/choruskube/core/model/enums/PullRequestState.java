package com.choruskube.core.model.enums;

/**
 * A pull request's lifecycle state as GitHub reports it. Deliberately mirrors GitHub's own
 * {@code state} field rather than adding a synthetic {@code merged} value: a PR can be
 * {@code closed} without having been merged, and conflating the two would lose exactly the
 * distinction the Task-closure guard depends on. "Merged" is
 * {@code RunPullRequest.mergedAt != null}.
 */
public enum PullRequestState {
    open,
    closed
}
