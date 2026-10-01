package com.choruskube.core.model.enums;

/** Mirrors the {@code github_issue_state} Postgres enum: an issue this server filed for a Task is either still open or has been closed (by the server, on Task completion). */
public enum GithubIssueState {
    open,
    closed
}
