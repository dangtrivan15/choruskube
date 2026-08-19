package com.choruskube.core.model;

import com.choruskube.core.model.enums.PullRequestState;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "run_pull_request")
public class RunPullRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Column(name = "git_repo_id", nullable = false)
    private UUID gitRepoId;

    @Column(name = "node_execution_id")
    private UUID nodeExecutionId;

    @Column(name = "pr_url", nullable = false)
    private String prUrl;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "title")
    private String title;

    @Column(name = "repo_name")
    private String repoName;

    @Enumerated(EnumType.STRING)
    @Column(name = "state")
    private PullRequestState state;

    @Column(name = "merged_at")
    private Instant mergedAt;

    /** When GitHub last answered for this row. Written on success only — see {@link #nextCheckAt}. */
    @Column(name = "state_checked_at")
    private Instant stateCheckedAt;

    /**
     * Consecutive failed refresh attempts; reset to zero the moment GitHub answers. Drives the
     * backoff exponent, and is the only durable record that a row is in trouble.
     */
    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    /**
     * When this row is next eligible for the scan — the scan's sort key, and the one column a
     * failure is allowed to write. Separate from {@link #stateCheckedAt} on purpose: deferring a row
     * that failed and recording that GitHub answered are different facts, and collapsing them would
     * mean a failure either lies about having been checked or cannot yield its place at all.
     */
    @Column(name = "next_check_at", nullable = false)
    private Instant nextCheckAt;

    /**
     * Set when GitHub answers in a way waiting cannot fix, cleared the moment it answers again.
     * Its presence — not {@link #failureCount}, which also rises on transient faults — is what
     * makes a row's Task reportable as stuck.
     */
    @Column(name = "unreadable_since")
    private Instant unreadableSince;

    /** Why {@link #unreadableSince} is set, in the words the Autopilot panel renders verbatim. */
    @Column(name = "unreadable_reason")
    private String unreadableReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (nextCheckAt == null) {
            // Due immediately: a freshly registered PR is the case the reconciler most wants to
            // pick up on its next tick. The column's DEFAULT says the same thing, but Hibernate
            // sends an explicit null over it unless the field is populated here.
            nextCheckAt = createdAt;
        }
    }

    // --- Getters and Setters ---

    public Instant getUnreadableSince() {
        return unreadableSince;
    }

    public void setUnreadableSince(Instant unreadableSince) {
        this.unreadableSince = unreadableSince;
    }

    public String getUnreadableReason() {
        return unreadableReason;
    }

    public void setUnreadableReason(String unreadableReason) {
        this.unreadableReason = unreadableReason;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getWorkflowRunId() {
        return workflowRunId;
    }

    public void setWorkflowRunId(UUID workflowRunId) {
        this.workflowRunId = workflowRunId;
    }

    public UUID getGitRepoId() {
        return gitRepoId;
    }

    public void setGitRepoId(UUID gitRepoId) {
        this.gitRepoId = gitRepoId;
    }

    public UUID getNodeExecutionId() {
        return nodeExecutionId;
    }

    public void setNodeExecutionId(UUID nodeExecutionId) {
        this.nodeExecutionId = nodeExecutionId;
    }

    public String getPrUrl() {
        return prUrl;
    }

    public void setPrUrl(String prUrl) {
        this.prUrl = prUrl;
    }

    public Integer getPrNumber() {
        return prNumber;
    }

    public void setPrNumber(Integer prNumber) {
        this.prNumber = prNumber;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRepoName() {
        return repoName;
    }

    public void setRepoName(String repoName) {
        this.repoName = repoName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public PullRequestState getState() {
        return state;
    }

    public void setState(PullRequestState state) {
        this.state = state;
    }

    public Instant getMergedAt() {
        return mergedAt;
    }

    public void setMergedAt(Instant mergedAt) {
        this.mergedAt = mergedAt;
    }

    public Instant getStateCheckedAt() {
        return stateCheckedAt;
    }

    public void setStateCheckedAt(Instant stateCheckedAt) {
        this.stateCheckedAt = stateCheckedAt;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public Instant getNextCheckAt() {
        return nextCheckAt;
    }

    public void setNextCheckAt(Instant nextCheckAt) {
        this.nextCheckAt = nextCheckAt;
    }
}
