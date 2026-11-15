package com.choruskube.core.migration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.SharedContextTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Schema-level test for {@code V12__work_item_dependency_epic_tier.sql}: the
 * {@code blocking_item_type}/{@code blocked_item_type} CHECK constraints must accept
 * {@code 'epic'} alongside {@code 'story'} and {@code 'task'}, and must still reject
 * anything else. Checked against the shared, fully migrated test database inside a rolled-back
 * transaction.
 */
@Transactional
class V12EpicTierMigrationTest extends SharedContextTest {

    @Autowired
    private JdbcTemplate jdbc;

    private void insertEdge(String blockingType, String blockedType) {
        jdbc.update(
                "INSERT INTO work_item_dependency"
                        + " (blocking_item_type, blocking_item_id, blocked_item_type, blocked_item_id)"
                        + " VALUES (?, ?, ?, ?)",
                blockingType,
                UUID.randomUUID(),
                blockedType,
                UUID.randomUUID());
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
        assertThatThrownBy(() -> insertEdge("milestone", "task")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
