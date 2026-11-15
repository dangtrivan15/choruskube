package com.choruskube.core.service;

import com.choruskube.core.dto.RepoGroupRequest;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.RepoGroupDependency;
import com.choruskube.core.model.RepoGroupMember;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.RepoGroupDependencyRepository;
import com.choruskube.core.repository.RepoGroupRepository;
import com.choruskube.core.util.MemoryQuantity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final RepoGroupDependencyRepository dependencies;

    @PersistenceContext
    private EntityManager entityManager;

    public RepoGroupService(
            RepoGroupRepository groups,
            GitRepoRepository gitRepos,
            RepoUniquenessChecker uniquenessChecker,
            ApplicationEventPublisher applicationEventPublisher,
            AuthorizationService authService,
            RepoGroupDependencyRepository dependencies) {
        this.groups = groups;
        this.gitRepos = gitRepos;
        this.uniquenessChecker = uniquenessChecker;
        this.applicationEventPublisher = applicationEventPublisher;
        this.authService = authService;
        this.dependencies = dependencies;
    }

    @Transactional
    public RepoGroup create(RepoGroupRequest request) {
        assertMembersInCallerOrg(request.memberRepoIds());
        uniquenessChecker.assertNameAvailable(request.name());
        RepoGroup group = createInternal(request);
        applicationEventPublisher.publishEvent(MappableCreated.of("software_project", group.getId()));
        return group;
    }

    @Transactional
    public RepoGroup createInternal(RepoGroupRequest request) {
        RepoGroup group = new RepoGroup();
        group.setName(request.name());
        group.setAgentImage(request.agentImage());
        group.setDescription(request.description());
        group.setEnableDocker(request.enableDocker() != null ? request.enableDocker() : false);
        group.setDindImage(request.dindImage());
        applyMemoryRequests(group, request);
        groups.save(group);
        applyMembers(group, request.memberRepoIds());
        return group;
    }

    @Transactional
    public void replaceMembers(UUID groupId, List<UUID> memberRepoIds) {
        assertMembersInCallerOrg(memberRepoIds);
        RepoGroup group =
                groups.findById(groupId).orElseThrow(() -> new BadRequestException("RepoGroup not found: " + groupId));
        group.getMembers().clear();
        // Force orphan-removal DELETEs to hit the DB before new INSERTs are queued, otherwise an
        // overlapping member (same composite PK in old and new lists) would conflict at flush time.
        entityManager.flush();
        applyMembers(group, memberRepoIds);
        // Edges are keyed by repo id, not by the member rows, so nothing cascades: drop the ones
        // whose endpoint just left the group, or they would outlive the membership they describe.
        Set<UUID> kept = new HashSet<>(memberRepoIds);
        dependencies.deleteAll(dependencies.findByRepoGroupId(groupId).stream()
                .filter(e -> !kept.contains(e.getGitRepoId()) || !kept.contains(e.getDependsOnGitRepoId()))
                .toList());
    }

    /** Sets the affected-only switch and replaces every dependency edge; rejects non-members, self-edges and cycles. */
    @Transactional
    public void replaceTestScope(UUID groupId, boolean affectedOnly, Map<UUID, Set<UUID>> dependsOn) {
        RepoGroup group =
                groups.findById(groupId).orElseThrow(() -> new NotFoundException("RepoGroup not found: " + groupId));
        Map<UUID, String> names = new HashMap<>();
        group.getMembers()
                .forEach(m -> names.put(m.getGitRepo().getId(), m.getGitRepo().getName()));
        for (Map.Entry<UUID, Set<UUID>> edge : dependsOn.entrySet()) {
            assertMember(names, edge.getKey());
            for (UUID target : edge.getValue()) {
                assertMember(names, target);
                if (target.equals(edge.getKey())) {
                    throw new BadRequestException("A repo cannot depend on itself: " + names.get(target));
                }
            }
        }
        RepoDependencyGraph.findCycle(dependsOn).ifPresent(cycle -> {
            throw new BadRequestException("Repo dependencies form a cycle: "
                    + cycle.stream()
                            .map(id -> names.getOrDefault(id, id.toString()))
                            .collect(Collectors.joining(" → ")));
        });
        group.setTestAffectedOnly(affectedOnly);
        dependencies.deleteByRepoGroupId(groupId);
        // Hibernate runs inserts before deletes at flush; without this, re-inserting an unchanged
        // edge collides with its not-yet-deleted row.
        entityManager.flush();
        List<RepoGroupDependency> edges = new ArrayList<>();
        dependsOn.forEach(
                (from, targets) -> targets.forEach(to -> edges.add(new RepoGroupDependency(groupId, from, to))));
        dependencies.saveAll(edges);
    }

    /** Dependent to its dependencies, each list in member-position order; repos with none are absent. */
    @Transactional(readOnly = true)
    public Map<UUID, List<UUID>> dependenciesOf(UUID groupId) {
        RepoGroup group =
                groups.findById(groupId).orElseThrow(() -> new NotFoundException("RepoGroup not found: " + groupId));
        Map<UUID, Integer> position = new HashMap<>();
        group.getMembers().forEach(m -> position.put(m.getGitRepo().getId(), m.getPosition()));
        Map<UUID, List<UUID>> result = new LinkedHashMap<>();
        for (RepoGroupDependency edge : dependencies.findByRepoGroupId(groupId)) {
            // A concurrent member removal can leave an edge to a non-member; it has no position.
            if (!position.containsKey(edge.getGitRepoId()) || !position.containsKey(edge.getDependsOnGitRepoId())) {
                continue;
            }
            result.computeIfAbsent(edge.getGitRepoId(), k -> new ArrayList<>()).add(edge.getDependsOnGitRepoId());
        }
        result.values().forEach(l -> l.sort(Comparator.comparing(position::get)));
        return result;
    }

    private static void assertMember(Map<UUID, String> memberNames, UUID id) {
        if (!memberNames.containsKey(id)) {
            throw new BadRequestException("Repo is not a member of this group: " + id);
        }
    }

    @Transactional
    public RepoGroup update(UUID groupId, RepoGroupRequest request) {
        RepoGroup group =
                groups.findById(groupId).orElseThrow(() -> new BadRequestException("RepoGroup not found: " + groupId));
        if (!group.getName().equals(request.name())) {
            uniquenessChecker.assertNameAvailable(request.name());
            group.setName(request.name());
        }
        group.setAgentImage(request.agentImage());
        group.setDescription(request.description());
        group.setEnableDocker(request.enableDocker() != null ? request.enableDocker() : false);
        group.setDindImage(request.dindImage());
        applyMemoryRequests(group, request);
        return group;
    }

    @Transactional
    public void delete(UUID groupId) {
        RepoGroup group =
                groups.findById(groupId).orElseThrow(() -> new NotFoundException("RepoGroup not found: " + groupId));
        // Archive, not hard-delete: @SQLRestriction hides the tombstoned row while Epics/Tasks/runs
        // that still reference this software_project_id keep a live FK target. Clearing members frees
        // each member git_repo from being delete-blocked by a group the user can no longer see.
        group.getMembers().clear();
        dependencies.deleteByRepoGroupId(groupId);
        group.setDeletedAt(Instant.now());
        groups.save(group);
    }

    private static void applyMemoryRequests(RepoGroup group, RepoGroupRequest request) {
        group.setAgentMemoryRequest(MemoryQuantity.normalize("agentMemoryRequest", request.agentMemoryRequest()));
        group.setDindMemoryRequest(MemoryQuantity.normalize("dindMemoryRequest", request.dindMemoryRequest()));
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
