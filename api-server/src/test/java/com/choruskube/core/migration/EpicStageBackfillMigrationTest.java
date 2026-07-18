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
 * Tests {@code V4__backfill_epic_stage.sql}'s data-migration logic in isolation, on a fresh,
 * dedicated Postgres instance per test (same rationale as {@link WorkHierarchyMigrationTest}:
 * each test needs its own pristine schema, stopped at an exact intermediate version).
 *
 * <p>Context: V2 wrapped every legacy {@code feature_proposal} as Epic -> Story -> Task, but
 * {@code work_item_status} had no {@code rolled_out} member yet, so V2's CASE collapsed
 * {@code rolled_out} proposals into {@code task.status = 'done'}. V3 then added the persisted
 * {@code epic.stage} column with a blanket {@code DEFAULT 'backlog'} and re-added
 * {@code rolled_out} to the enum — but never recovered the roll-out state V2 had flattened, so
 * every pre-existing Epic sat in the board's Backlog column regardless of its real position.
 * V4 restores that lost mapping from the (deliberately retained) {@code feature_proposal} rows.
 */
class EpicStageBackfillMigrationTest {

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
    void rolled_out_proposal_restores_the_rolled_out_stage_v2_had_flattened_to_done() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();

        seedLegacyProposalThroughV3(projectId, proposalId, "rolled_out");

        migrateToVersion("4");

        assertThat(stageOf(proposalId)).isEqualTo("rolled_out");
    }

    @Test
    void in_progress_proposal_maps_to_in_progress_stage() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();

        seedLegacyProposalThroughV3(projectId, proposalId, "in_progress");

        migrateToVersion("4");

        assertThat(stageOf(proposalId)).isEqualTo("in_progress");
    }

    @Test
    void untouched_backlog_proposal_stays_in_backlog() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();

        seedLegacyProposalThroughV3(projectId, proposalId, "backlog");

        migrateToVersion("4");

        assertThat(stageOf(proposalId)).isEqualTo("backlog");
    }

    /**
     * The divergent case seen in production: the legacy status is a frozen snapshot from V2 day,
     * but descendant Tasks kept moving afterwards. Completed-since-migration work is promoted to
     * {@code in_progress} rather than {@code rolled_out} — "all tasks done" means code-complete,
     * not necessarily shipped, and stage is human-owned, so the last step stays a deliberate move.
     */
    @Test
    void backlog_proposal_whose_tasks_all_completed_after_migration_is_promoted_to_in_progress() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();

        seedLegacyProposalThroughV3(projectId, proposalId, "backlog");
        setAllTasksOfEpic(proposalId, "done");

        migrateToVersion("4");

        assertThat(stageOf(proposalId)).isEqualTo("in_progress");
    }

    @Test
    void backlog_proposal_whose_tasks_merely_started_after_migration_is_promoted_to_in_progress() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();

        seedLegacyProposalThroughV3(projectId, proposalId, "backlog");
        setAllTasksOfEpic(proposalId, "in_progress");

        migrateToVersion("4");

        assertThat(stageOf(proposalId)).isEqualTo("in_progress");
    }

    /** An Epic created after V2 has no {@code feature_proposal} row; the backfill must not touch it. */
    @Test
    void epic_with_no_legacy_proposal_is_left_in_backlog() throws Exception {
        migrateToVersion("3");

        UUID projectId = UUID.randomUUID();
        UUID epicId = UUID.randomUUID();
        try (Connection conn = connect()) {
            insertSoftwareProject(conn, projectId);
            insertEpic(conn, epicId, projectId);
        }

        migrateToVersion("4");

        assertThat(stageOf(epicId)).isEqualTo("backlog");
    }

    /**
     * A stage a human already moved on the board outranks the legacy snapshot: the backfill only
     * repairs Epics still sitting at V3's blanket {@code DEFAULT 'backlog'}.
     */
    @Test
    void stage_already_moved_by_a_human_is_not_clobbered() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();

        seedLegacyProposalThroughV3(projectId, proposalId, "rolled_out");
        setStage(proposalId, "in_progress");

        migrateToVersion("4");

        assertThat(stageOf(proposalId)).isEqualTo("in_progress");
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

    /**
     * Applies V1, seeds one legacy proposal, then applies V2 and V3 — leaving an Epic whose stage
     * is V3's blanket default and whose Task status is whatever V2's CASE derived.
     */
    private void seedLegacyProposalThroughV3(UUID projectId, UUID proposalId, String legacyStatus) throws Exception {
        migrateToVersion("1");
        try (Connection conn = connect()) {
            insertSoftwareProject(conn, projectId);
            insertFeatureProposal(conn, proposalId, projectId, legacyStatus);
        }
        migrateToVersion("3");
    }

    // --- Row seeding / mutation helpers ---

    private void insertSoftwareProject(Connection conn, UUID id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO public.software_project (id, name, type) VALUES (?, ?, 'git_repo')")) {
            ps.setObject(1, id);
            ps.setString(2, "test-project-" + id);
            ps.executeUpdate();
        }
    }

    private void insertFeatureProposal(Connection conn, UUID id, UUID projectId, String status) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO public.feature_proposal "
                + "(id, title, description, motivation, status, software_project_id) "
                + "VALUES (?, ?, ?, ?, ?::public.feature_proposal_status, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "Legacy proposal " + id);
            ps.setString(3, "Legacy description");
            ps.setString(4, "Legacy motivation");
            ps.setString(5, status);
            ps.setObject(6, projectId);
            ps.executeUpdate();
        }
    }

    private void insertEpic(Connection conn, UUID id, UUID projectId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO public.epic " + "(id, title, description, software_project_id) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "Post-migration epic " + id);
            ps.setString(3, "Created after V2, so it has no feature_proposal row");
            ps.setObject(4, projectId);
            ps.executeUpdate();
        }
    }

    private void setAllTasksOfEpic(UUID epicId, String status) throws Exception {
        try (Connection conn = connect();
                PreparedStatement ps = conn.prepareStatement("UPDATE public.task t "
                        + "SET status = ?::public.work_item_status "
                        + "FROM public.story s WHERE s.id = t.story_id AND s.epic_id = ?")) {
            ps.setString(1, status);
            ps.setObject(2, epicId);
            ps.executeUpdate();
        }
    }

    private void setStage(UUID epicId, String stage) throws Exception {
        try (Connection conn = connect();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE public.epic SET stage = ?::public.work_item_status WHERE id = ?")) {
            ps.setString(1, stage);
            ps.setObject(2, epicId);
            ps.executeUpdate();
        }
    }

    // --- Assertion helpers ---

    private String stageOf(UUID epicId) throws Exception {
        try (Connection conn = connect();
                PreparedStatement ps = conn.prepareStatement("SELECT stage::text FROM public.epic WHERE id = ?")) {
            ps.setObject(1, epicId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("expected an epic row for " + epicId).isTrue();
                return rs.getString(1);
            }
        }
    }
}
