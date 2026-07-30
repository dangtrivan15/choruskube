package com.choruskube.core.controller;

import com.choruskube.core.dto.BlockingChainResponse;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.service.BlockingChainService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BlockingChainController {

    private final BlockingChainService service;

    public BlockingChainController(BlockingChainService service) {
        this.service = service;
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/stories/{id}/blocking-chain")
    public BlockingChainResponse getStoryChain(@PathVariable UUID id) {
        return service.getChain(BlockableItemType.story, id);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/tasks/{id}/blocking-chain")
    public BlockingChainResponse getTaskChain(@PathVariable UUID id) {
        return service.getChain(BlockableItemType.task, id);
    }
}
