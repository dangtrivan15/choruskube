package com.choruskube.core.model;

import com.choruskube.core.model.enums.GithubIssueState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Links a Task created by roadmap-extension materialization to the GitHub issue the server filed
 * for it — one row per Task ({@code task_id} unique), so {@code DefaultTaskService} can find and
 * close the issue the moment the Task reaches {@code done}.
 */
@Entity
@Table(name = "task_github_issue")
public class TaskGithubIssue extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "task_id", nullable = false, unique = true)
    private UUID taskId;

    @Column(name = "git_repo_id", nullable = false)
    private UUID gitRepoId;

    @Column(name = "issue_number", nullable = false)
    private int issueNumber;

    @Column(name = "issue_url", nullable = false)
    private String issueUrl;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "github_issue_state")
    private GithubIssueState state = GithubIssueState.open;

    @Column(name = "closed_at")
    private Instant closedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getGitRepoId() {
        return gitRepoId;
    }

    public void setGitRepoId(UUID gitRepoId) {
        this.gitRepoId = gitRepoId;
    }

    public int getIssueNumber() {
        return issueNumber;
    }

    public void setIssueNumber(int issueNumber) {
        this.issueNumber = issueNumber;
    }

    public String getIssueUrl() {
        return issueUrl;
    }

    public void setIssueUrl(String issueUrl) {
        this.issueUrl = issueUrl;
    }

    public GithubIssueState getState() {
        return state;
    }

    public void setState(GithubIssueState state) {
        this.state = state;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }
}
