package com.choruskube.core.service;

import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.TaskGithubIssue;
import com.choruskube.core.model.enums.GithubIssueState;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.TaskGithubIssueRepository;
import com.choruskube.core.util.RepoNameUtil;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Closes the GitHub issue linked to a Task, if one exists and is still open — best-effort, same as
 * the linkage's own creation: a GitHub outage never fails the Task's completion, it just leaves the
 * row open (nothing retries it; an operator closes it by hand). A missing or already-{@code closed}
 * linkage row is a silent no-op.
 */
@Component
public class TaskGithubIssueCloser {

    private static final Logger log = LoggerFactory.getLogger(TaskGithubIssueCloser.class);

    private final TaskGithubIssueRepository taskGithubIssueRepository;
    private final GitRepoRepository gitRepoRepo;
    private final GitHubCredentialResolver gitHubCredentialResolver;
    private final GitHubAppService gitHubAppService;

    public TaskGithubIssueCloser(
            TaskGithubIssueRepository taskGithubIssueRepository,
            GitRepoRepository gitRepoRepo,
            GitHubCredentialResolver gitHubCredentialResolver,
            GitHubAppService gitHubAppService) {
        this.taskGithubIssueRepository = taskGithubIssueRepository;
        this.gitRepoRepo = gitRepoRepo;
        this.gitHubCredentialResolver = gitHubCredentialResolver;
        this.gitHubAppService = gitHubAppService;
    }

    public void close(UUID taskId) {
        Optional<TaskGithubIssue> linkage = taskGithubIssueRepository.findByTaskId(taskId);
        if (linkage.isEmpty() || linkage.get().getState() != GithubIssueState.open) {
            return;
        }
        TaskGithubIssue issue = linkage.get();
        try {
            GitRepo gitRepo = gitRepoRepo
                    .findById(issue.getGitRepoId())
                    .orElseThrow(() -> new NotFoundException("GitRepo not found: " + issue.getGitRepoId()));
            String token = gitHubCredentialResolver.getTokenForRepo(issue.getGitRepoId());
            gitHubAppService.closeIssue(
                    token, RepoNameUtil.deriveOwnerRepoName(gitRepo.getUrl()), issue.getIssueNumber());
            issue.setState(GithubIssueState.closed);
            issue.setClosedAt(Instant.now());
            taskGithubIssueRepository.save(issue);
        } catch (Exception e) {
            log.warn(
                    "Failed to close GitHub issue #{} for Task {}: {}", issue.getIssueNumber(), taskId, e.getMessage());
        }
    }
}
