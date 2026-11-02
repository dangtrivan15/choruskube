package com.choruskube.core.controller;

import com.choruskube.core.dto.RunResponse;
import com.choruskube.core.dto.StoryConflictResponse;
import com.choruskube.core.dto.StoryPriorityUpdateRequest;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.StoryReviseRequest;
import com.choruskube.core.dto.StorySpecResponse;
import com.choruskube.core.dto.StorySpecVersionSummary;
import com.choruskube.core.dto.StoryStageUpdateRequest;
import com.choruskube.core.dto.StoryTargetDateUpdateRequest;
import com.choruskube.core.dto.StoryUpdateRequest;
import com.choruskube.core.model.enums.Priority;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.service.StoryDesignService;
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
    private final StoryDesignService designService;

    public StoryController(StoryService service, StoryDesignService designService) {
        this.service = service;
        this.designService = designService;
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

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/stories")
    public Page<StoryResponse> list(
            @RequestParam(required = false) WorkItemStatus stage,
            @RequestParam(required = false) Priority priority,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.list(stage, priority, pageable);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/stories/{id}")
    public StoryResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize("@orgSecurity.canAdmin()")
    @PutMapping("/api/v1/stories/{id}")
    public StoryResponse update(@PathVariable UUID id, @Valid @RequestBody StoryUpdateRequest request) {
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

    @PreAuthorize("@orgSecurity.canOperate()")
    @PatchMapping("/api/v1/stories/{id}/priority")
    public StoryResponse updatePriority(@PathVariable UUID id, @Valid @RequestBody StoryPriorityUpdateRequest request) {
        return service.updatePriority(id, request.priority());
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PatchMapping("/api/v1/stories/{id}/target-date")
    public StoryResponse updateTargetDate(
            @PathVariable UUID id, @Valid @RequestBody StoryTargetDateUpdateRequest request) {
        return service.updateTargetDate(id, request.targetDate());
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/stories/{id}/spec")
    public StorySpecResponse getSpec(@PathVariable UUID id) {
        return service.getSpec(id);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/stories/{id}/spec/versions")
    public List<StorySpecVersionSummary> listSpecVersions(@PathVariable UUID id) {
        return service.listSpecVersions(id);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/stories/{id}/spec/versions/{version}")
    public StorySpecResponse getSpecVersion(@PathVariable UUID id, @PathVariable int version) {
        return service.getSpecVersion(id, version);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/stories/{id}/conflicts")
    public List<StoryConflictResponse> listOpenConflicts(@PathVariable UUID id) {
        return service.listOpenConflicts(id);
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/api/v1/stories/{id}/design")
    public ResponseEntity<RunResponse> design(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(designService.startDesign(id));
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/api/v1/stories/{id}/revise")
    public ResponseEntity<RunResponse> revise(@PathVariable UUID id, @Valid @RequestBody StoryReviseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(designService.startRevision(id, request.guidance()));
    }
}
