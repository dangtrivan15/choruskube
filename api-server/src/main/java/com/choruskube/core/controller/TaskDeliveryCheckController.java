package com.choruskube.core.controller;

import com.choruskube.core.dto.TaskDeliveryCheckRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.service.TaskDeliveryCheckService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** A person's close of a Task whose work merged elsewhere; agents cannot reach {@code /api}. */
@RestController
public class TaskDeliveryCheckController {

    private final TaskDeliveryCheckService service;

    public TaskDeliveryCheckController(TaskDeliveryCheckService service) {
        this.service = service;
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/api/v1/tasks/{id}/delivery-check")
    public TaskResponse start(@PathVariable UUID id, @Valid @RequestBody TaskDeliveryCheckRequest request) {
        return service.start(id, request.pullRequestUrls(), request.note());
    }
}
