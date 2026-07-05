package com.choruskube.core.e2e;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.reconciler.GitRepoReconciler;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.TombstonedGitRepoRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end coverage of the git_repo soft-delete flow through the REST surface: API
 * visibility after delete, tombstone retention, reconciler cleanup, and the
 * create-after-tombstone 409-then-201 UX.
 *
 * <p>Not {@code @Transactional} on purpose: each MockMvc call must run in its own Spring-managed
 * transaction so Hibernate's first-level cache doesn't serve stale (pre-delete) entities on
 * later reads, bypassing {@code @SQLRestriction}. The {@code provisioningExecutor} bean is
 * replaced with a no-op Mockito mock so the afterCommit {@code CompletableFuture.runAsync}
 * cleanup task never dispatches — letting us assert on tombstone state without racing against
 * the happy-path cleanup, and letting the reconciler path be the only thing that moves
 * tombstones → hard-deleted within the test.
 *
 * <p>Repos no longer own K8s namespaces — provisioning is at the org level.
 * Repo soft-delete and reconciler hard-delete are tested here without K8s
 * interaction.
 */
@AutoConfigureMockMvc
public class GitRepoSoftDeleteE2ETest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private GitRepoReconciler reconciler;

    // Kill the afterCommit cleanup path — we want tombstones to persist until this test
    // invokes the reconciler explicitly. The mock's execute(Runnable) is a no-op, so
    // CompletableFuture.runAsync(task, mockExecutor) never runs the task.
    @MockitoBean(name = "provisioningExecutor")
    private Executor provisioningExecutor;

    @MockitoBean
    private io.temporal.serviceclient.WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private io.temporal.client.WorkflowClient workflowClient;

    @Test
    void delete_hidesRowFromApi_retainsTombstoneInDb() throws Exception {
        String url = "https://github.com/e2e/hide-" + UUID.randomUUID();
        UUID id = createRepoViaApi(url);

        mockMvc.perform(delete("/api/v1/git-repos/" + id)).andExpect(status().isNoContent());

        // Invisible to user-facing get + list (both go through @SQLRestriction).
        mockMvc.perform(get("/api/v1/git-repos/" + id)).andExpect(status().isNotFound());

        MvcResult listResult = mockMvc.perform(get("/api/v1/git-repos"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listJson = objectMapper.readTree(listResult.getResponse().getContentAsString());
        for (JsonNode row : listJson.get("content")) {
            assertThat(row.get("id").asText()).isNotEqualTo(id.toString());
        }

        // But still present in the DB for the reconciler to pick up.
        assertThat(gitRepoRepo.findTombstonedBatch(1000))
                .extracting(TombstonedGitRepoRef::getId)
                .contains(id);
    }

    @Test
    void reconciler_clearsTombstoneAndHardDeletes() throws Exception {
        String url = "https://github.com/e2e/reconcile-" + UUID.randomUUID();
        UUID id = createRepoViaApi(url);

        mockMvc.perform(delete("/api/v1/git-repos/" + id)).andExpect(status().isNoContent());
        assertThat(gitRepoRepo.findTombstonedBatch(1000))
                .extracting(TombstonedGitRepoRef::getId)
                .contains(id);

        // Forcing a reconciler tick rather than waiting PT1M. Same code path as production.
        reconciler.reconcile();

        assertThat(gitRepoRepo.findTombstonedBatch(1000))
                .extracting(TombstonedGitRepoRef::getId)
                .doesNotContain(id);
    }

    @Test
    void create_afterSoftDelete_succeedsWhileTombstonePending() throws Exception {
        String url = "https://github.com/e2e/reuse-" + UUID.randomUUID();
        UUID id = createRepoViaApi(url);

        mockMvc.perform(delete("/api/v1/git-repos/" + id)).andExpect(status().isNoContent());

        UUID recreatedId = createRepoViaApi(url);
        assertThat(recreatedId).isNotEqualTo(id);

        // Reconciler clears the original tombstone; the recreated live row is untouched.
        reconciler.reconcile();

        mockMvc.perform(get("/api/v1/git-repos/" + recreatedId)).andExpect(status().isOk());
    }

    private UUID createRepoViaApi(String url) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/git-repos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(url)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(json.get("id").asText());
    }

    private String createBody(String url) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "url", url,
                "defaultBranch", "main",
                "agentImage", "test-agent:latest",
                "secrets", "[]",
                "enableDocker", false));
    }
}
