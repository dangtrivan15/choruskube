package com.choruskube.core.service;

import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.RepoGroupMember;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.RepoGroupRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepoGroupService {

    private final RepoGroupRepository groups;
    private final GitRepoRepository gitRepos;
    private final RepoUniquenessChecker uniquenessChecker;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final AuthorizationService authService;

    @PersistenceContext
    private EntityManager entityManager;

    public RepoGroupService(
            RepoGroupRepository groups,
            GitRepoRepository gitRepos,
            RepoUniquenessChecker uniquenessChecker,
            ApplicationEventPublisher applicationEventPublisher,
            AuthorizationService authService) {
        this.groups = groups;
        this.gitRepos = gitRepos;
        this.uniquenessChecker = uniquenessChecker;
        this.applicationEventPublisher = applicationEventPublisher;
        this.authService = authService;
    }

    @Transactional
    public RepoGroup create(String name, String agentImage, String description, List<UUID> memberRepoIds) {
        // Request path: every member repo must belong to the caller's org. Resolved from ownership by
        // the active strategy (no-op under always-allow); throws ForbiddenException (403) on mismatch.
        assertMembersInCallerOrg(memberRepoIds);
        uniquenessChecker.assertNameAvailable(name);
        RepoGroup group = createInternal(name, agentImage, description, memberRepoIds);
        applicationEventPublisher.publishEvent(MappableCreated.of("software_project", group.getId()));
        return group;
    }

    @Transactional
    public RepoGroup createInternal(String name, String agentImage, String description, List<UUID> memberRepoIds) {
        RepoGroup group = new RepoGroup();
        group.setName(name);
        group.setAgentImage(agentImage);
        group.setDescription(description);
        groups.save(group);
        applyMembers(group, memberRepoIds);
        return group;
    }

    @Transactional
    public void replaceMembers(UUID groupId, List<UUID> memberRepoIds) {
        // Request path: every new member repo must belong to the caller's org (same guard as create).
        assertMembersInCallerOrg(memberRepoIds);
        RepoGroup group =
                groups.findById(groupId).orElseThrow(() -> new BadRequestException("RepoGroup not found: " + groupId));
        group.getMembers().clear();
        // Force orphan-removal DELETEs to hit the DB before new INSERTs are queued, otherwise an
        // overlapping member (same composite PK in old and new lists) would conflict at flush time.
        entityManager.flush();
        applyMembers(group, memberRepoIds);
    }

    @Transactional
    public RepoGroup update(UUID groupId, String name, String agentImage, String description) {
        RepoGroup group =
                groups.findById(groupId).orElseThrow(() -> new BadRequestException("RepoGroup not found: " + groupId));
        if (!group.getName().equals(name)) {
            uniquenessChecker.assertNameAvailable(name);
            group.setName(name);
        }
        group.setAgentImage(agentImage);
        group.setDescription(description);
        return group;
    }

    @Transactional
    public void delete(UUID groupId) {
        // Active-run/task blocking belongs to the controller layer (returns 409) where the
        // WorkflowRunRepository and TaskRepository are in scope; here the group is hard-deleted and
        // orphanRemoval cascades the members.
        groups.deleteById(groupId);
    }

    private void applyMembers(RepoGroup group, List<UUID> memberRepoIds) {
        if (memberRepoIds == null || memberRepoIds.isEmpty()) {
            throw new BadRequestException("RepoGroup must have at least one member");
        }
        List<GitRepo> repos = gitRepos.findAllById(memberRepoIds);
        if (repos.size() != memberRepoIds.size()) {
            throw new BadRequestException("One or more memberRepoIds do not exist");
        }
        // The cross-org guard (members must share the caller's org) lives in the request-scoped
        // create/replaceMembers entry points, not here: applyMembers is also reached by the seeder
        // path (createInternal) where there is no TenantContext and the group has no ownership row
        // yet, and where all resources are trusted system-org data.
        Map<UUID, GitRepo> byId = repos.stream().collect(Collectors.toMap(GitRepo::getId, Function.identity()));
        List<RepoGroupMember> members = new ArrayList<>();
        for (int i = 0; i < memberRepoIds.size(); i++) {
            RepoGroupMember m = new RepoGroupMember();
            m.setRepoGroup(group);
            m.setGitRepo(byId.get(memberRepoIds.get(i)));
            m.setPosition(i);
            members.add(m);
        }
        group.getMembers().addAll(members);
    }

    /**
     * Request-path cross-org guard: each member repo must belong to the caller's organization.
     * Resolved from ownership data by the active authorization strategy — no-op under always-allow
     * (OSS single-tenant), throws {@code ForbiddenException} (403) on mismatch under auth.
     */
    private void assertMembersInCallerOrg(List<UUID> memberRepoIds) {
        if (memberRepoIds == null) {
            return;
        }
        for (UUID memberRepoId : memberRepoIds) {
            authService.checkOrgAccess("software_project", memberRepoId);
        }
    }
}
