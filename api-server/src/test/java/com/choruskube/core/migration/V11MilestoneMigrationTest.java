package com.choruskube.core.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.SharedContextTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Schema-level test for {@code V11__milestone.sql}: the {@code milestone} table, its per-project
 * case-insensitive unique name index, and {@code epic.milestone_id}'s {@code ON DELETE SET NULL}
 * FK behavior, checked against the shared, fully migrated test database inside a rolled-back
 * transaction.
 */
@Transactional
class V11MilestoneMigrationTest extends SharedContextTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void duplicateNameInSameProject_differentCase_violatesUniqueIndex() {
        UUID projectId = UUID.randomUUID();
        insertSoftwareProject(projectId);
        insertMilestone(UUID.randomUUID(), projectId, "Q3 Launch");

        assertThatThrownBy(() -> insertMilestone(UUID.randomUUID(), projectId, "q3 launch"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void sameNameDifferentProject_isAllowed() {
        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();
        insertSoftwareProject(projectA);
        insertSoftwareProject(projectB);
        insertMilestone(UUID.randomUUID(), projectA, "Q3 Launch");
        insertMilestone(UUID.randomUUID(), projectB, "Q3 Launch");

        assertThat(countWhere("milestone", "software_project_id", projectA)).isEqualTo(1);
        assertThat(countWhere("milestone", "software_project_id", projectB)).isEqualTo(1);
    }

    @Test
    void deletingMilestone_setsEpicMilestoneIdToNull_leavesEpicRowIntact() {
        UUID projectId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        UUID epicId = UUID.randomUUID();
        insertSoftwareProject(projectId);
        insertMilestone(milestoneId, projectId, "Deletable Milestone");
        insertEpic(epicId, projectId, milestoneId);

        jdbc.update("DELETE FROM public.milestone WHERE id = ?", milestoneId);

        assertThat(countWhere("epic", "id", epicId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT milestone_id FROM public.epic WHERE id = ?", UUID.class, epicId))
                .isNull();
    }

    // --- Row seeding helpers ---

    private void insertSoftwareProject(UUID id) {
        jdbc.update(
                "INSERT INTO public.software_project (id, name, type) VALUES (?, ?, 'git_repo')",
                id,
                "test-project-" + id);
    }

    private void insertMilestone(UUID id, UUID projectId, String name) {
        jdbc.update(
                "INSERT INTO public.milestone (id, software_project_id, name) VALUES (?, ?, ?)", id, projectId, name);
    }

    private void insertEpic(UUID id, UUID projectId, UUID milestoneId) {
        jdbc.update(
                "INSERT INTO public.epic (id, title, description, software_project_id, milestone_id)"
                        + " VALUES (?, ?, ?, ?, ?)",
                id,
                "Tagged epic " + id,
                "Description",
                projectId,
                milestoneId);
    }

    private int countWhere(String table, String column, Object value) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
    }
}
