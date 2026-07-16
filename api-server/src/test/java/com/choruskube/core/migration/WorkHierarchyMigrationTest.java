package com.choruskube.core.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Tests {@code V2__work_hierarchy.sql}'s data-migration logic in isolation, on a fresh,
 * dedicated Postgres instance per test (not the shared app-wide TestContainers instance
 * {@code BaseTest} uses, which is already fully migrated past V2 for the whole JVM, and not
 * shared across test methods here either — each test needs its own pristine, V1-only schema to
 * seed legacy rows into before applying V2): applies only V1, seeds legacy
 * {@code feature_proposal} rows directly (with and without a linked run), then applies V2 and
 * asserts the resulting Epic/Story/Task/workflow_run rows — id preservation, exactly one
 * Story/Task per migrated Epic, and {@code workflow_run.task_id} set correctly when a run was
 * linked (Decision 3).
 */
class WorkHierarchyMigrationTest {

    private PostgreSQLContainer container;

    @BeforeEach
    void startContainer() {
        container = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("migration_test")
                .withUsername("migration_test")
                .withPassword("migration_test");
        container.start();
    }

    @AfterEach
    void stopContainer() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void proposal_with_linked_run_migrates_to_epic_story_task_and_sets_workflow_run_task_id() throws Exception {
        migrateToVersion("1");

        UUID templateId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();

        try (Connection conn = connect()) {
            insertSoftwareProject(conn, projectId);
            insertGraphTemplate(conn, templateId);
            insertWorkflowRun(conn, runId, templateId);
            insertFeatureProposal(conn, proposalId, projectId, runId, "backlog");
        }

        migrateToVersion("2");

        try (Connection conn = connect()) {
            // Epic id preserved from feature_proposal id (Decision 3).
            assertThat(countWhere(conn, "epic", "id", proposalId)).isEqualTo(1);

            // Exactly one Story under that Epic.
            assertThat(countWhere(conn, "story", "epic_id", proposalId)).isEqualTo(1);
            UUID storyId = singleUuid(conn, "SELECT id FROM story WHERE epic_id = ?", proposalId);

            // Exactly one Task under that Story.
            assertThat(countWhere(conn, "task", "story_id", storyId)).isEqualTo(1);
            UUID taskId = singleUuid(conn, "SELECT id FROM task WHERE story_id = ?", storyId);

            String status = singleString(conn, "SELECT status::text FROM task WHERE id = ?", taskId);
            assertThat(status).isEqualTo("backlog");

            // workflow_run.task_id set to the migrated Task's id.
            UUID linkedTaskId = singleUuid(conn, "SELECT task_id FROM workflow_run WHERE id = ?", runId);
            assertThat(linkedTaskId).isEqualTo(taskId);
        }
    }

    @Test
    void proposal_without_linked_run_migrates_to_epic_story_task_with_no_run_history() throws Exception {
        migrateToVersion("1");

        UUID projectId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();

        try (Connection conn = connect()) {
            insertSoftwareProject(conn, projectId);
            insertFeatureProposal(conn, proposalId, projectId, null, "rolled_out");
        }

        migrateToVersion("2");

        try (Connection conn = connect()) {
            assertThat(countWhere(conn, "epic", "id", proposalId)).isEqualTo(1);
            UUID storyId = singleUuid(conn, "SELECT id FROM story WHERE epic_id = ?", proposalId);
            UUID taskId = singleUuid(conn, "SELECT id FROM task WHERE story_id = ?", storyId);

            // rolled_out maps to done: CASE fp.status WHEN 'rolled_out' THEN 'done'.
            String status = singleString(conn, "SELECT status::text FROM task WHERE id = ?", taskId);
            assertThat(status).isEqualTo("done");

            // No workflow_run row references this task — the proposal never had a linked run.
            assertThat(countWhere(conn, "workflow_run", "task_id", taskId)).isZero();
        }
    }

    // --- Flyway driving helpers ---

    private void migrateToVersion(String target) {
        Flyway flyway = Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target))
                .load();
        flyway.migrate();
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    // --- Legacy (pre-V2) row seeding helpers ---

    private void insertSoftwareProject(Connection conn, UUID id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO public.software_project (id, name, type) VALUES (?, ?, 'git_repo')")) {
            ps.setObject(1, id);
            ps.setString(2, "test-project-" + id);
            ps.executeUpdate();
        }
    }

    private void insertGraphTemplate(Connection conn, UUID id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO public.graph_template (id, name, graph_id, version) VALUES (?, ?, ?, 1)")) {
            ps.setObject(1, id);
            ps.setString(2, "Test Template");
            ps.setString(3, "test-template-" + id);
            ps.executeUpdate();
        }
    }

    private void insertWorkflowRun(Connection conn, UUID id, UUID graphTemplateId) throws Exception {
        try (PreparedStatement ps =
                conn.prepareStatement("INSERT INTO public.workflow_run (id, graph_template_id) VALUES (?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, graphTemplateId);
            ps.executeUpdate();
        }
    }

    private void insertFeatureProposal(Connection conn, UUID id, UUID projectId, UUID workflowRunId, String status)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO public.feature_proposal "
                + "(id, title, description, motivation, status, software_project_id, workflow_run_id) "
                + "VALUES (?, ?, ?, ?, ?::public.feature_proposal_status, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "Legacy proposal " + id);
            ps.setString(3, "Legacy description");
            ps.setString(4, "Legacy motivation");
            ps.setString(5, status);
            ps.setObject(6, projectId);
            ps.setObject(7, workflowRunId);
            ps.executeUpdate();
        }
    }

    // --- Post-migration assertion helpers ---

    private int countWhere(Connection conn, String table, String column, Object value) throws Exception {
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT count(*) FROM " + table + " WHERE " + column + " = ?")) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private UUID singleUuid(Connection conn, String sql, Object param) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("expected exactly one row for: " + sql).isTrue();
                Object v = rs.getObject(1);
                return v == null ? null : (v instanceof UUID uuid ? uuid : UUID.fromString(v.toString()));
            }
        }
    }

    private String singleString(Connection conn, String sql, Object param) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("expected exactly one row for: " + sql).isTrue();
                return rs.getString(1);
            }
        }
    }
}
