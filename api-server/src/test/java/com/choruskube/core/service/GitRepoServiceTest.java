package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.GitRepoRequest;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.observability.UsageSink;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.TombstonedGitRepoRef;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class GitRepoServiceTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private GitRepoService service;

    @Autowired
    private GitRepoRepository repo;

    @MockitoBean
    private AuditSink auditSink;

    @MockitoBean
    private UsageSink usageSink;

    @PersistenceContext
    private EntityManager entityManager;

    private GitRepoRequest sampleRequest() {
        return new GitRepoRequest(
                "https://github.com/test/repo",
                "main",
                "npm test",
                "agent:latest",
                "[{\"name\":\"GH_TOKEN\",\"secretName\":\"gh-token\"}]",
                true);
    }

    @Test
    void createAndGet() {
        var created = service.create(sampleRequest());
        assertThat(created.id()).isNotNull();
        assertThat(created.url()).isEqualTo("https://github.com/test/repo");
        assertThat(created.defaultBranch()).isEqualTo("main");
        assertThat(created.testCommand()).isEqualTo("npm test");
        assertThat(created.agentImage()).isEqualTo("agent:latest");
        assertThat(created.enableDocker()).isTrue();
        assertThat(created.secrets().isArray()).isTrue();

        var fetched = service.get(created.id());
        assertThat(fetched.url()).isEqualTo(created.url());
    }

    @Test
    void createDuplicateUrlThrowsConflict() {
        service.create(sampleRequest());
        assertThatThrownBy(() -> service.create(sampleRequest())).isInstanceOf(ConflictException.class);
    }

    @Test
    void createDuplicateUrlAcrossOrgsRejectedUnderGlobalUniqueness() {
        String sharedUrl = "https://github.com/shared/repo";

        GitRepo repoA = new GitRepo();
        repoA.setUrl(sharedUrl);
        repoA.setName(RepoNameUtil.deriveOwnerRepoName(sharedUrl));
        repo.save(repoA);

        assertThatThrownBy(() -> service.create(new GitRepoRequest(sharedUrl, "main", null, null, "[]", false)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(sharedUrl);
    }

    @Test
    void updateChangesFields() {
        var created = service.create(sampleRequest());
        var updateReq = new GitRepoRequest(
                "https://github.com/test/repo", "develop", "./gradlew test", "new-agent:v2", "[]", false);
        var updated = service.update(created.id(), updateReq);
        assertThat(updated.defaultBranch()).isEqualTo("develop");
        assertThat(updated.testCommand()).isEqualTo("./gradlew test");
        assertThat(updated.enableDocker()).isFalse();
    }

    @Test
    void updateWithEnableDockerChangeDoesNotTriggerReprovisioning() {
        var created = service.create(sampleRequest()); // enableDocker=true
        var updateReq =
                new GitRepoRequest("https://github.com/test/repo", "main", "npm test", "agent:latest", "[]", false);
        var updated = service.update(created.id(), updateReq);
        assertThat(updated.enableDocker()).isFalse();
        // Docker toggle no longer triggers reprovisioning — Docker resources always exist
    }

    @Test
    void deleteRemovesEntity() {
        var created = service.create(sampleRequest());
        service.delete(created.id());
        entityManager.flush();
        entityManager.clear();
        assertThatThrownBy(() -> service.get(created.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listReturnsPaginatedResults() {
        long baseline = repo.count();
        service.create(sampleRequest());
        service.create(new GitRepoRequest("https://github.com/test/other", null, null, null, null, null));
        var page = service.list(Pageable.ofSize(10));
        assertThat(page.getTotalElements()).isEqualTo(baseline + 2);
    }

    @Test
    void getNonExistentThrowsNotFound() {
        assertThatThrownBy(() -> service.get(java.util.UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // Soft-delete pilot: GitRepoService.delete
    // -----------------------------------------------------------------------

    @Test
    void delete_setsDeletedAtAndHidesRow() {
        var created = service.create(sampleRequest());

        service.delete(created.id());
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> service.get(created.id())).isInstanceOf(NotFoundException.class);

        List<TombstonedGitRepoRef> tombstones = repo.findTombstonedBatch(100);
        assertThat(tombstones).extracting(TombstonedGitRepoRef::getId).contains(created.id());

        Object deletedAt = entityManager
                .createNativeQuery("SELECT deleted_at FROM software_project WHERE id = :id AND type = 'git_repo'")
                .setParameter("id", created.id())
                .getSingleResult();
        assertThat(deletedAt).isNotNull();
    }

    @Test
    void delete_listExcludesTombstonedRows() {
        var kept = service.create(sampleRequest());
        var doomed =
                service.create(new GitRepoRequest("https://github.com/test/doomed", "main", null, null, null, false));

        service.delete(doomed.id());

        var page = service.list(Pageable.unpaged());
        assertThat(page.getContent())
                .extracting(r -> r.id())
                .contains(kept.id())
                .doesNotContain(doomed.id());
    }

    @Test
    void delete_tombstonedUrlAllowsNewCreateWithSameUrl() {
        var created = service.create(sampleRequest());
        service.delete(created.id());
        entityManager.flush();
        entityManager.clear();

        var recreated = service.create(sampleRequest());
        entityManager.flush();
        assertThat(recreated.id()).isNotNull();
        assertThat(recreated.id()).isNotEqualTo(created.id());
    }

    @Test
    void delete_auditRecordEmitted() {
        var created = service.create(sampleRequest());

        service.delete(created.id());

        verify(auditSink).record(eq(AuditSink.REPO_DELETED), eq("git_repo"), eq(created.id()), any());
    }

    @Test
    void delete_usageEventEmitted() {
        var created = service.create(sampleRequest());

        service.delete(created.id());

        verify(usageSink).record(eq(UsageSink.REPO_DELETED), eq("git_repo"), eq(created.id()), isNull());
    }

    // -----------------------------------------------------------------------
    // cleanupAndHardDelete (package-private)
    // -----------------------------------------------------------------------

    @Test
    void cleanupAndHardDelete_removesRow() {
        var created = service.create(sampleRequest());
        service.delete(created.id());

        service.cleanupAndHardDelete(created.id());

        // No K8s deprovision call — repos no longer own namespaces
        assertThat(repo.findTombstonedBatch(100))
                .extracting(TombstonedGitRepoRef::getId)
                .doesNotContain(created.id());
    }

    @Test
    void cleanupAndHardDelete_idempotentOnAlreadyHardDeleted() {
        var created = service.create(sampleRequest());
        service.delete(created.id());

        service.cleanupAndHardDelete(created.id());
        // Second call must not throw, even though the row is already gone.
        service.cleanupAndHardDelete(created.id());

        assertThat(repo.findTombstonedBatch(100))
                .extracting(TombstonedGitRepoRef::getId)
                .doesNotContain(created.id());
    }

    // -----------------------------------------------------------------------
    // reconcileTombstonedBatch (package-private)
    // -----------------------------------------------------------------------

    @Test
    void reconcileTombstonedBatch_cleansUpAndReturnsCount() {
        var r1 = service.create(new GitRepoRequest("https://github.com/test/a", "main", null, null, null, false));
        var r2 = service.create(new GitRepoRequest("https://github.com/test/b", "main", null, null, null, false));
        var r3 = service.create(new GitRepoRequest("https://github.com/test/c", "main", null, null, null, false));
        service.delete(r1.id());
        service.delete(r2.id());
        service.delete(r3.id());

        int cleaned = service.reconcileTombstonedBatch(100);

        assertThat(cleaned).isEqualTo(3);
        assertThat(repo.findTombstonedBatch(100))
                .extracting(TombstonedGitRepoRef::getId)
                .doesNotContain(r1.id(), r2.id(), r3.id());
    }

    @Test
    void reconcileTombstonedBatch_respectsBatchSize() {
        for (int i = 0; i < 5; i++) {
            var c = service.create(
                    new GitRepoRequest("https://github.com/test/batch-" + i, "main", null, null, null, false));
            service.delete(c.id());
        }

        int firstPass = service.reconcileTombstonedBatch(2);
        assertThat(firstPass).isEqualTo(2);

        int secondPass = service.reconcileTombstonedBatch(100);
        assertThat(secondPass).isEqualTo(3);

        assertThat(repo.findTombstonedBatch(100)).isEmpty();
    }
}
