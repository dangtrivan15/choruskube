package com.choruskube.core.service;

import com.choruskube.core.dto.GitRepoRequest;
import com.choruskube.core.dto.GitRepoResponse;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.observability.AuditDetail;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.observability.UsageSink;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.RepoGroupMemberRepository;
import com.choruskube.core.repository.TombstonedGitRepoRef;
import com.choruskube.core.scope.ScopeProvider;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GitRepoService {

    private static final Logger log = LoggerFactory.getLogger(GitRepoService.class);

    private final GitRepoRepository repo;
    private final ObjectMapper objectMapper;
    private final OrgReadinessGate orgReadinessGate;
    private final RepoUniquenessChecker uniquenessChecker;
    private final AuthorizationService authService;
    private final Optional<QuotaChecker> quotaService;
    private final UsageSink usageSink;
    private final AuditSink auditSink;
    private final RepoGroupMemberRepository groupMembers;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ScopeProvider scopeProvider;

    public GitRepoService(
            GitRepoRepository repo,
            ObjectMapper objectMapper,
            OrgReadinessGate orgReadinessGate,
            RepoUniquenessChecker uniquenessChecker,
            AuthorizationService authService,
            Optional<QuotaChecker> quotaService,
            UsageSink usageSink,
            AuditSink auditSink,
            RepoGroupMemberRepository groupMembers,
            PlatformTransactionManager txManager,
            ApplicationEventPublisher applicationEventPublisher,
            ScopeProvider scopeProvider) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.orgReadinessGate = orgReadinessGate;
        this.uniquenessChecker = uniquenessChecker;
        this.authService = authService;
        this.quotaService = quotaService;
        this.usageSink = usageSink;
        this.auditSink = auditSink;
        this.groupMembers = groupMembers;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.applicationEventPublisher = applicationEventPublisher;
        this.scopeProvider = scopeProvider;
    }

    @Transactional
    public GitRepoResponse create(GitRepoRequest request) {
        uniquenessChecker.assertUrlAvailable(request.url(), null);
        orgReadinessGate.assertReadyForCreate();

        GitRepo entity = fromRequest(new GitRepo(), request);
        quotaService.ifPresent(QuotaChecker::checkRepoQuota);
        try {
            entity = repo.saveAndFlush(entity);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ConflictException("Git repo with URL '" + request.url()
                    + "' is unavailable — it may still be cleaning up from a recent deletion. Try again in a moment.");
        }
        applicationEventPublisher.publishEvent(MappableCreated.of("software_project", entity.getId()));
        usageSink.record(UsageSink.REPO_CREATED, "git_repo", entity.getId(), null);
        auditSink.record(AuditSink.REPO_CREATED, "git_repo", entity.getId(), toAuditDetail(null, entity));
        return toResponse(entity);
    }

    public GitRepoResponse get(UUID id) {
        GitRepo entity = findOrThrow(id);
        authService.checkOrgAccess("git_repo", id);
        return toResponse(entity);
    }

    public Page<GitRepoResponse> list(Pageable pageable) {
        Specification<GitRepo> spec = scopeProvider.scope(GitRepo.class);
        return repo.findAll(spec, pageable).map(this::toResponse);
    }

    @Transactional
    public GitRepoResponse update(UUID id, GitRepoRequest request) {
        GitRepo existing = findOrThrow(id);
        authService.checkOrgAccess("git_repo", id);

        // Capture before state for audit
        Map<String, Object> beforeSnapshot = repoSnapshot(existing);

        boolean enableDockerChanged =
                existing.isEnableDocker() != (request.enableDocker() != null ? request.enableDocker() : false);

        if (enableDockerChanged) {
            orgReadinessGate.assertNoRunningJobsForDockerToggle(existing.getId());
        }

        if (!existing.getUrl().equals(request.url())) {
            uniquenessChecker.assertUrlAvailable(request.url(), existing.getId());
        }

        GitRepo updated = repo.save(fromRequest(existing, request));
        auditSink.record(AuditSink.REPO_UPDATED, "git_repo", id, toAuditDetail(beforeSnapshot, repoSnapshot(updated)));

        // Docker toggle no longer triggers reprovisioning — Docker resources always exist at org level.
        // The toggle only affects DooD sidecar attachment on future pod creation.

        return toResponse(updated);
    }

    /**
     * Soft-delete a single git_repo. Repos no longer own K8s namespaces, so cleanup
     * is just the DB hard-delete via the reconciler backstop.
     */
    public void delete(UUID id) {
        GitRepo existing = findOrThrow(id);
        authService.checkOrgAccess("git_repo", id);

        if (!groupMembers.findByGitRepoId(id).isEmpty()) {
            throw new ConflictException("Cannot delete: repo is a member of RepoGroup(s); remove from group first");
        }

        final UUID repoId = existing.getId();
        final Map<String, Object> beforeSnapshot = repoSnapshot(existing);

        transactionTemplate.execute(status -> {
            existing.setDeletedAt(Instant.now());
            repo.save(existing);
            auditSink.record(AuditSink.REPO_DELETED, "git_repo", id, toAuditDetail(beforeSnapshot, null));
            usageSink.record(UsageSink.REPO_DELETED, "git_repo", id, null);

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeCleanup(repoId);
                }
            });
            return null;
        });
    }

    /**
     * Shared cleanup primitive for a single tombstoned row. Repos no longer own K8s
     * namespaces, so this just does the DB hard-delete.
     */
    void cleanupAndHardDelete(UUID id) {
        Integer rowsDeleted = transactionTemplate.execute(status -> repo.hardDeleteTombstoneById(id));
        if (rowsDeleted != null && rowsDeleted > 0) {
            log.info("Hard-deleted tombstoned git_repo {}", id);
        }
    }

    /**
     * Reconciler driver entry point: fetch up to {@code batchSize} tombstoned rows and clean
     * each.
     */
    public int reconcileTombstonedBatch(int batchSize) {
        List<TombstonedGitRepoRef> batch = repo.findTombstonedBatch(batchSize);
        int cleaned = 0;
        for (TombstonedGitRepoRef ref : batch) {
            try {
                cleanupAndHardDelete(ref.getId());
                cleaned++;
            } catch (Exception e) {
                log.warn(
                        "Reconciler cleanup for tombstoned git_repo {} failed; will retry next tick: {}",
                        ref.getId(),
                        e.getMessage());
            }
        }
        return cleaned;
    }

    /**
     * afterCommit wrapper that swallows all exceptions so a single repo's cleanup failure
     * never takes down the whole cascade.
     */
    private void safeCleanup(UUID repoId) {
        try {
            cleanupAndHardDelete(repoId);
        } catch (Exception e) {
            log.warn("afterCommit cleanup for git_repo {} failed; reconciler will retry: {}", repoId, e.getMessage());
        }
    }

    private Map<String, Object> repoSnapshot(GitRepo entity) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("url", entity.getUrl());
        snapshot.put("defaultBranch", entity.getDefaultBranch());
        snapshot.put("agentImage", entity.getAgentImage());
        snapshot.put("enableDocker", entity.isEnableDocker());
        return snapshot;
    }

    private String toAuditDetail(Object before, Object after) {
        return AuditDetail.json(objectMapper, before, after);
    }

    private GitRepo findOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Git repo not found: " + id));
    }

    private GitRepo fromRequest(GitRepo entity, GitRepoRequest request) {
        entity.setUrl(request.url());
        entity.setName(RepoNameUtil.deriveOwnerRepoName(request.url()));
        entity.setDefaultBranch(request.defaultBranch() != null ? request.defaultBranch() : "main");
        entity.setTestCommand(request.testCommand());
        entity.setAgentImage(request.agentImage());
        entity.setSecrets(request.secrets() != null ? request.secrets() : "[]");
        entity.setEnableDocker(request.enableDocker() != null ? request.enableDocker() : false);
        return entity;
    }

    private JsonNode parseJson(String json, String fallback) {
        try {
            return objectMapper.readTree(json != null && !json.isBlank() ? json : fallback);
        } catch (Exception e) {
            try {
                return objectMapper.readTree(fallback);
            } catch (Exception ex) {
                return objectMapper.createObjectNode();
            }
        }
    }

    GitRepoResponse toResponse(GitRepo entity) {
        JsonNode secretsNode = parseJson(entity.getSecrets(), "[]");
        return new GitRepoResponse(
                entity.getId(),
                entity.getUrl(),
                entity.getDefaultBranch(),
                entity.getTestCommand(),
                entity.getAgentImage(),
                secretsNode,
                entity.isEnableDocker(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
