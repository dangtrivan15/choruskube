package com.choruskube.core.controller;

import com.choruskube.core.dto.RepoGroupMembersRequest;
import com.choruskube.core.dto.RepoGroupRequest;
import com.choruskube.core.dto.RepoGroupResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.repository.FeatureProposalRepository;
import com.choruskube.core.repository.RepoGroupRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.scope.ScopeProvider;
import com.choruskube.core.service.AuthorizationService;
import com.choruskube.core.service.RepoGroupService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/repo-groups")
public class RepoGroupController {

    private final RepoGroupService service;
    private final RepoGroupRepository groups;
    private final WorkflowRunRepository runs;
    private final FeatureProposalRepository proposals;
    private final AuthorizationService authService;
    private final ScopeProvider scopeProvider;

    public RepoGroupController(
            RepoGroupService service,
            RepoGroupRepository groups,
            WorkflowRunRepository runs,
            FeatureProposalRepository proposals,
            AuthorizationService authService,
            ScopeProvider scopeProvider) {
        this.service = service;
        this.groups = groups;
        this.runs = runs;
        this.proposals = proposals;
        this.authService = authService;
        this.scopeProvider = scopeProvider;
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping
    // open-in-view is disabled; the repo's findByOrganizationId used an @EntityGraph to eager-fetch
    // `members`. The Specification path (findAll) cannot carry that graph, so wrap the
    // response-mapping in a read-only tx to keep the JPA session open while toResponse() touches the
    // lazy `members` collection — mirroring SoftwareProjectController#list().
    @Transactional(readOnly = true)
    public List<RepoGroupResponse> list() {
        return groups.findAll(scopeProvider.scope(RepoGroup.class)).stream()
                .map(RepoGroupController::toResponse)
                .toList();
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{id}")
    public RepoGroupResponse get(@PathVariable UUID id) {
        return toResponse(findInActiveOrg(id));
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RepoGroupResponse create(@Valid @RequestBody RepoGroupRequest body) {
        RepoGroup group = service.create(body.name(), body.agentImage(), body.description(), body.memberRepoIds());
        return toResponse(group);
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PutMapping("/{id}")
    public RepoGroupResponse update(@PathVariable UUID id, @Valid @RequestBody RepoGroupRequest body) {
        findInActiveOrg(id);
        service.update(id, body.name(), body.agentImage(), body.description());
        if (body.memberRepoIds() != null) {
            service.replaceMembers(id, body.memberRepoIds());
        }
        return toResponse(findInActiveOrg(id));
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PutMapping("/{id}/members")
    public RepoGroupResponse replaceMembers(@PathVariable UUID id, @Valid @RequestBody RepoGroupMembersRequest body) {
        findInActiveOrg(id);
        service.replaceMembers(id, body.memberRepoIds());
        return toResponse(findInActiveOrg(id));
    }

    @PreAuthorize("@orgSecurity.canAdmin()")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        findInActiveOrg(id);
        long activeRuns = runs.countNonTerminalBySoftwareProjectId(id);
        long activeProposals = proposals.countNonRolledOutBySoftwareProjectId(id);
        if (activeRuns > 0 || activeProposals > 0) {
            throw new ConflictException("Cannot delete RepoGroup: %d active run(s), %d active proposal(s)"
                    .formatted(activeRuns, activeProposals));
        }
        service.delete(id);
    }

    /**
     * Loads a RepoGroup by id, throws 404 if missing, and rejects with 403 (ForbiddenException)
     * if the group's organization does not match the active tenant. Used by every {id}-bearing
     * endpoint so the org-scope check is enforced uniformly.
     */
    private RepoGroup findInActiveOrg(UUID id) {
        RepoGroup group = groups.findById(id).orElseThrow(() -> new NotFoundException("RepoGroup not found: " + id));
        authService.checkOrgAccess("repo_group", id);
        return group;
    }

    private static RepoGroupResponse toResponse(RepoGroup g) {
        List<RepoGroupResponse.MemberView> members = g.getMembers().stream()
                .map(m -> new RepoGroupResponse.MemberView(
                        m.getGitRepo().getId(), m.getGitRepo().getName(), m.getPosition()))
                .toList();
        return new RepoGroupResponse(
                g.getId(),
                g.getName(),
                g.getAgentImage(),
                g.getDescription(),
                g.getRuntimeRequirements(),
                members,
                g.getCreatedAt(),
                g.getUpdatedAt());
    }
}
