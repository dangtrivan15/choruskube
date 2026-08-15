package com.choruskube.core.migration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Schema-level test for {@code V12__work_item_dependency_epic_tier.sql}: the
 * {@code blocking_item_type}/{@code blocked_item_type} CHECK constraints must accept
 * {@code 'epic'} alongside {@code 'story'} and {@code 'task'}, and must still reject
 * anything else. Verified against a fresh, dedicated Postgres instance, same rationale
 * as {@link V11MilestoneMigrationTest}.
 */
class V12EpicTierMigrationTest {

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

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private void insertEdge(String blockingType, String blockedType) throws SQLException {
        try (Connection c = conn();
                var ps = c.prepareStatement("INSERT INTO work_item_dependency"
                        + " (blocking_item_type, blocking_item_id, blocked_item_type, blocked_item_id)"
                        + " VALUES (?, ?, ?, ?)")) {
            ps.setString(1, blockingType);
            ps.setObject(2, UUID.randomUUID());
            ps.setString(3, blockedType);
            ps.setObject(4, UUID.randomUUID());
            ps.executeUpdate();
        }
    }

    @Test
    void epicOnBothSides_isAccepted() {
        assertThatCode(() -> insertEdge("epic", "epic")).doesNotThrowAnyException();
    }

    @Test
    void epicMixedWithOtherTiers_isAccepted() {
        assertThatCode(() -> insertEdge("epic", "story")).doesNotThrowAnyException();
        assertThatCode(() -> insertEdge("task", "epic")).doesNotThrowAnyException();
    }

    @Test
    void preExistingTiers_stillAccepted() {
        assertThatCode(() -> insertEdge("story", "task")).doesNotThrowAnyException();
    }

    @Test
    void unknownTier_isStillRejected() {
        assertThatThrownBy(() -> insertEdge("milestone", "task")).isInstanceOf(SQLException.class);
    }
}
