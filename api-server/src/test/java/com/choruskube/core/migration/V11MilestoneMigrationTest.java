package com.choruskube.core.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Schema-level test for {@code V11__milestone.sql} (the "Group Epics under a
 * named Milestone / Release" feature; renumbered from the spec's nominal {@code V10} because
 * {@code V10__epic_story_target_date.sql} already occupies that slot in this repo — see the
 * migration directory listing at implementation time): the {@code milestone} table, its per-project
 * case-insensitive unique name index, and {@code epic.milestone_id}'s {@code ON DELETE SET NULL}
 * FK behavior, verified against a fresh, dedicated Postgres instance (same rationale as {@link
 * WorkHierarchyMigrationTest}).
 */
class V11MilestoneMigrationTest {

    private PostgreSQLContainer container;

    @BeforeEach
    void startContainer() {
        container = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("migration_test")
                .withUsername("migration_test")
                .withPassword("migration_test");
        container.start();
        Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterEach
    void stopContainer() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void duplicateNameInSameProject_differentCase_violatesUniqueIndex() throws Exception {
        UUID projectId = UUID.randomUUID();
        try (Connection conn = connect()) {
            insertSoftwareProject(conn, projectId);
            insertMilestone(conn, UUID.randomUUID(), projectId, "Q3 Launch");
        }

        assertThatThrownBy(() -> {
                    try (Connection conn = connect()) {
                        insertMilestone(conn, UUID.randomUUID(), projectId, "q3 launch");
                    }
                })
                .isInstanceOf(SQLException.class);
    }

    @Test
    void sameNameDifferentProject_isAllowed() throws Exception {
        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();
        try (Connection conn = connect()) {
            insertSoftwareProject(conn, projectA);
            insertSoftwareProject(conn, projectB);
            insertMilestone(conn, UUID.randomUUID(), projectA, "Q3 Launch");
            insertMilestone(conn, UUID.randomUUID(), projectB, "Q3 Launch");
        }

        try (Connection conn = connect()) {
            assertThat(countWhere(conn, "milestone", "software_project_id", projectA))
                    .isEqualTo(1);
            assertThat(countWhere(conn, "milestone", "software_project_id", projectB))
                    .isEqualTo(1);
        }
    }

    @Test
    void deletingMilestone_setsEpicMilestoneIdToNull_leavesEpicRowIntact() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        UUID epicId = UUID.randomUUID();
        try (Connection conn = connect()) {
            insertSoftwareProject(conn, projectId);
            insertMilestone(conn, milestoneId, projectId, "Deletable Milestone");
            insertEpic(conn, epicId, projectId, milestoneId);
        }

        try (Connection conn = connect();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM public.milestone WHERE id = ?")) {
            ps.setObject(1, milestoneId);
            ps.executeUpdate();
        }

        try (Connection conn = connect()) {
            assertThat(countWhere(conn, "epic", "id", epicId)).isEqualTo(1);
            assertThat(milestoneIdOf(conn, epicId)).isNull();
        }
    }

    // --- Row seeding helpers ---

    private Connection connect() throws Exception {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private void insertSoftwareProject(Connection conn, UUID id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO public.software_project (id, name, type) VALUES (?, ?, 'git_repo')")) {
            ps.setObject(1, id);
            ps.setString(2, "test-project-" + id);
            ps.executeUpdate();
        }
    }

    private void insertMilestone(Connection conn, UUID id, UUID projectId, String name) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO public.milestone (id, software_project_id, name) VALUES (?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, projectId);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    private void insertEpic(Connection conn, UUID id, UUID projectId, UUID milestoneId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO public.epic "
                + "(id, title, description, software_project_id, milestone_id) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "Tagged epic " + id);
            ps.setString(3, "Description");
            ps.setObject(4, projectId);
            ps.setObject(5, milestoneId);
            ps.executeUpdate();
        }
    }

    // --- Assertion helpers ---

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

    private UUID milestoneIdOf(Connection conn, UUID epicId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT milestone_id FROM public.epic WHERE id = ?")) {
            ps.setObject(1, epicId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("expected an epic row for " + epicId).isTrue();
                Object v = rs.getObject(1);
                return v == null ? null : (v instanceof UUID uuid ? uuid : UUID.fromString(v.toString()));
            }
        }
    }
}
