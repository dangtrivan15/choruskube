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
 * Tests {@code V18__drop_feature_proposal.sql} on a fresh, dedicated Postgres instance (same
 * rationale as {@link WorkHierarchyMigrationTest} and {@link EpicStageBackfillMigrationTest}).
 *
 * <p>This covers core standing alone, which is how the OSS repo runs: only {@code db/migration},
 * applied straight through. The risk pinned here is ordering, not the DDL — {@code feature_proposal}
 * is read by V2 (which derives Epic/Story/Task from it) and by V4 (which recovers {@code epic.stage}
 * from it through the preserved {@code epic.id = feature_proposal.id}), and only then dropped. If
 * the drop ever moved earlier, or a later migration reintroduced a read of it, the recovered
 * roll-out state would silently fall back to V3's blanket {@code 'backlog'} default.
 */
class DropFeatureProposalMigrationTest {

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
    void a_rolled_out_proposal_still_reaches_its_epic_stage_before_the_table_is_dropped() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();

        seedLegacyProposalThroughV1(projectId, proposalId, "rolled_out");
        migrateToLatest();

        // V4 read the proposal before V18 removed it, so the recovered stage survives the drop.
        assertThat(stageOf(proposalId)).isEqualTo("rolled_out");
        // ...and the hierarchy V2 derived from that same row is still there.
        assertThat(countOf("SELECT count(*) FROM public.story WHERE epic_id = '" + proposalId + "'"))
                .isEqualTo(1);
    }

    @Test
    void the_table_and_its_enum_are_gone_once_every_migration_has_run() throws Exception {
        seedLegacyProposalThroughV1(UUID.randomUUID(), UUID.randomUUID(), "backlog");
        migrateToLatest();

        assertThat(countOf("SELECT count(*) FROM pg_class WHERE relname = 'feature_proposal'"))
                .isZero();
        assertThat(countOf("SELECT count(*) FROM pg_type WHERE typname = 'feature_proposal_status'"))
                .isZero();
    }

    // --- Flyway driving helpers ---

    private void migrateToLatest() {
        flyway(null).migrate();
    }

    /** Applies V1 only, then inserts a legacy proposal — the state a real pre-hierarchy DB was in. */
    private void seedLegacyProposalThroughV1(UUID projectId, UUID proposalId, String status) throws Exception {
        flyway("1").migrate();

        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO public.software_project (id, name, type) VALUES (?, ?, 'git_repo')")) {
                ps.setObject(1, projectId);
                ps.setString(2, "proj-" + projectId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO public.feature_proposal "
                    + "(id, title, description, motivation, status, software_project_id) "
                    + "VALUES (?, ?, ?, ?, ?::public.feature_proposal_status, ?)")) {
                ps.setObject(1, proposalId);
                ps.setString(2, "Legacy proposal");
                ps.setString(3, "desc");
                ps.setString(4, "motivation");
                ps.setString(5, status);
                ps.setObject(6, projectId);
                ps.executeUpdate();
            }
        }
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(MigrationVersion.fromVersion(target));
        }
        return config.load();
    }

    private String stageOf(UUID epicId) throws Exception {
        try (Connection conn = connect();
                PreparedStatement ps = conn.prepareStatement("SELECT stage::text FROM public.epic WHERE id = ?")) {
            ps.setObject(1, epicId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private long countOf(String sql) throws Exception {
        try (Connection conn = connect();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }
}
