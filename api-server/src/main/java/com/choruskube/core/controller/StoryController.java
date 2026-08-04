package com.choruskube.core.controller;

import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.StoryStageUpdateRequest;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.service.StoryService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StoryController {

    private final StoryService service;

    public StoryController(StoryService service) {
        this.service = service;
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/api/v1/epics/{epicId}/stories")
    public ResponseEntity<StoryResponse> create(@PathVariable UUID epicId, @Valid @RequestBody StoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(epicId, request));
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/epics/{epicId}/stories")
    public List<StoryResponse> list(@PathVariable UUID epicId) {
        return service.list(epicId);
    }

    /**
     * Global, cross-Epic Story listing for the Kanban board view (Backlog/In Progress/Rolled Out
     * columns map onto {@code stage}, mirroring the Epic board's stage-move endpoint shape).
     */
    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/stories")
    public Page<StoryResponse> list(
            @RequestParam(required = false) WorkItemStatus stage,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.list(stage, pageable);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/stories/{id}")
    public StoryResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize("@orgSecurity.canAdmin()")
    @PutMapping("/api/v1/stories/{id}")
    public StoryResponse update(@PathVariable UUID id, @Valid @RequestBody StoryRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize("@orgSecurity.canAdmin()")
    @DeleteMapping("/api/v1/stories/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PatchMapping("/api/v1/stories/{id}/stage")
    public StoryResponse updateStage(@PathVariable UUID id, @Valid @RequestBody StoryStageUpdateRequest request) {
        return service.updateStage(id, request.stage());
    }
}
