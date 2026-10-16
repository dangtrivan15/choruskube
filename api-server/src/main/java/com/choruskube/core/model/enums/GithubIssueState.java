package com.choruskube.core.model.enums;

/** Mirrors the {@code github_issue_state} Postgres enum: an issue this server filed for a Task is either still open or has been closed (by a Worker, once the Task is done). */
public enum GithubIssueState {
    open,
    closed
}
