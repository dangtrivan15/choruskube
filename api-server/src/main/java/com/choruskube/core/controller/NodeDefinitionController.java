package com.choruskube.core.controller;

import com.choruskube.core.dto.NodeDefinitionRequest;
import com.choruskube.core.dto.NodeDefinitionResponse;
import com.choruskube.core.service.NodeDefinitionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/node-definitions")
public class NodeDefinitionController {

    private final NodeDefinitionService service;

    public NodeDefinitionController(NodeDefinitionService service) {
        this.service = service;
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping
    public ResponseEntity<NodeDefinitionResponse> create(@Valid @RequestBody NodeDefinitionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping
    public Page<NodeDefinitionResponse> list(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String executorType,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return service.list(name, executorType, pageable);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{id}")
    public NodeDefinitionResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize("@orgSecurity.canAdmin()")
    @PutMapping("/{id}")
    public NodeDefinitionResponse update(@PathVariable UUID id, @Valid @RequestBody NodeDefinitionRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize("@orgSecurity.canAdmin()")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
