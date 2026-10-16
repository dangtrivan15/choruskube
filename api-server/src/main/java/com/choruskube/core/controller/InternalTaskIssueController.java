package com.choruskube.core.controller;

import com.choruskube.core.dto.TaskIssueClosedRequest;
import com.choruskube.core.service.TaskGithubIssueCloser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the issue-close workflow records that a Worker closed a Task's linked GitHub issue. The
 * path has no {@code node-executions/{id}} segment, so only the orchestrator's secret reaches it.
 */
@RestController
@RequestMapping("/internal/tasks/{taskId}/github-issue")
public class InternalTaskIssueController {

    private final TaskGithubIssueCloser issueCloser;

    public InternalTaskIssueController(TaskGithubIssueCloser issueCloser) {
        this.issueCloser = issueCloser;
    }

    @PostMapping("/closed")
    public ResponseEntity<Void> closed(@PathVariable UUID taskId, @Valid @RequestBody TaskIssueClosedRequest body) {
        HttpStatus status =
                switch (issueCloser.recordClosed(taskId, body.issueNumber())) {
                    case RECORDED, ALREADY_CLOSED -> HttpStatus.NO_CONTENT;
                    case NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case MISMATCH -> HttpStatus.CONFLICT;
                };
        return ResponseEntity.status(status).build();
    }
}
