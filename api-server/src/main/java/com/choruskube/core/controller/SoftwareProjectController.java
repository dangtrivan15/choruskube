package com.choruskube.core.controller;

import com.choruskube.core.dto.SoftwareProjectResponse;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.SoftwareProject;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.scope.ScopeProvider;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/software-projects")
public class SoftwareProjectController {

    private final SoftwareProjectRepository projects;
    private final ScopeProvider scopeProvider;

    public SoftwareProjectController(SoftwareProjectRepository projects, ScopeProvider scopeProvider) {
        this.projects = projects;
        this.scopeProvider = scopeProvider;
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping
    // open-in-view is disabled, so wrap the response-mapping in a read-only tx
    // to keep the JPA session open while RepoGroup#getRuntimeRequirements()
    // touches the lazy `members` collection. EntityGraph on the parent
    // SoftwareProject query is awkward (`members` only exists on the RepoGroup
    // subtype under JOINED inheritance).
    @Transactional(readOnly = true)
    public List<SoftwareProjectResponse> list() {
        return projects.findAll(scopeProvider.scope(SoftwareProject.class)).stream()
                .map(SoftwareProjectController::toResponse)
                .toList();
    }

    private static SoftwareProjectResponse toResponse(SoftwareProject p) {
        String type = (p instanceof RepoGroup) ? "repo_group" : "git_repo";
        return new SoftwareProjectResponse(
                p.getId(),
                p.getName(),
                type,
                p.getAgentImage(),
                p.getDescription(),
                p.getRuntimeRequirements(),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
