package com.choruskube.core.controller;

import com.choruskube.core.dto.EpicMilestoneUpdateRequest;
import com.choruskube.core.dto.EpicPriorityUpdateRequest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.EpicStageUpdateRequest;
import com.choruskube.core.dto.EpicTargetDateUpdateRequest;
import com.choruskube.core.dto.EpicUpdateRequest;
import com.choruskube.core.model.enums.Priority;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.service.EpicService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/epics")
public class EpicController {

    private final EpicService service;

    public EpicController(EpicService service) {
        this.service = service;
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping
    public ResponseEntity<EpicResponse> create(@Valid @RequestBody EpicRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping
    public Page<EpicResponse> list(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Readiness readiness,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) UUID milestoneId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.list(title, readiness, priority, milestoneId, pageable);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{id}")
    public EpicResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize("@orgSecurity.canAdmin()")
    @PutMapping("/{id}")
    public EpicResponse update(@PathVariable UUID id, @Valid @RequestBody EpicUpdateRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize("@orgSecurity.canAdmin()")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PatchMapping("/{id}/stage")
    public EpicResponse updateStage(@PathVariable UUID id, @Valid @RequestBody EpicStageUpdateRequest request) {
        return service.updateStage(id, request.stage());
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PatchMapping("/{id}/priority")
    public EpicResponse updatePriority(@PathVariable UUID id, @Valid @RequestBody EpicPriorityUpdateRequest request) {
        return service.updatePriority(id, request.priority());
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PatchMapping("/{id}/target-date")
    public EpicResponse updateTargetDate(
            @PathVariable UUID id, @Valid @RequestBody EpicTargetDateUpdateRequest request) {
        return service.updateTargetDate(id, request.targetDate());
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PatchMapping("/{id}/milestone")
    public EpicResponse assignMilestone(@PathVariable UUID id, @Valid @RequestBody EpicMilestoneUpdateRequest request) {
        return service.assignMilestone(id, request.milestoneId());
    }
}
