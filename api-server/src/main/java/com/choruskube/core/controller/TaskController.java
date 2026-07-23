package com.choruskube.core.controller;

import com.choruskube.core.dto.RunSummary;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.dto.TaskStatusUpdateRequest;
import com.choruskube.core.service.TaskService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/api/v1/stories/{storyId}/tasks")
    public ResponseEntity<TaskResponse> create(@PathVariable UUID storyId, @Valid @RequestBody TaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(storyId, request));
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/stories/{storyId}/tasks")
    public List<TaskResponse> list(@PathVariable UUID storyId) {
        return service.list(storyId);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/tasks/{id}")
    public TaskResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize("@orgSecurity.canAdmin()")
    @PutMapping("/api/v1/tasks/{id}")
    public TaskResponse update(@PathVariable UUID id, @Valid @RequestBody TaskRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize("@orgSecurity.canAdmin()")
    @DeleteMapping("/api/v1/tasks/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/api/v1/tasks/{id}/start")
    public TaskResponse start(@PathVariable UUID id) {
        return service.start(id);
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PatchMapping("/api/v1/tasks/{id}/complete")
    public TaskResponse complete(@PathVariable UUID id) {
        return service.complete(id);
    }

    /**
     * Validated-transition status write (Decision 4) — generalizes {@link #start}/{@link
     * #complete} to also cover reporting a failed/aborted outcome ({@code in_progress->backlog},
     * for retry). {@code start}/{@code complete} remain as-is for backward compatibility.
     */
    @PreAuthorize("@orgSecurity.canOperate()")
    @PatchMapping("/api/v1/tasks/{id}/status")
    public TaskResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody TaskStatusUpdateRequest request) {
        return service.updateStatus(id, request.status(), request.runId(), request.note());
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/tasks/{id}/runs")
    public Page<RunSummary> listRuns(
            @PathVariable UUID id,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.listRuns(id, pageable);
    }
}
