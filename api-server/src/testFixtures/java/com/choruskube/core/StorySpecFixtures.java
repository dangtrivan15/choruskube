package com.choruskube.core;

import com.choruskube.core.model.StorySpecVersion;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Seeds a Story's approved spec without running a Story Design run. */
public final class StorySpecFixtures {

    private StorySpecFixtures() {}

    /** Inserts the Story's next spec version and returns its number. */
    public static int approve(JdbcTemplate jdbc, UUID storyId) {
        Integer version = jdbc.queryForObject("""
                INSERT INTO story_spec_version (story_id, version, content)
                SELECT ?, COALESCE(MAX(version), 0) + 1, '# Story spec (test fixture)'
                FROM story_spec_version WHERE story_id = ?
                RETURNING version
                """, Integer.class, storyId, storyId);
        return version;
    }

    /**
     * Inserts the Story's next spec version tied to {@code runId}, as if recorded before review
     * capture existed: no {@code final_review}, no {@code review_captured_at}. Simulates the state
     * the backfill, and a revision's refusal, must handle.
     */
    public static StorySpecVersion insertUncaptured(JdbcTemplate jdbc, UUID storyId, UUID runId) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO story_spec_version (story_id, version, content, run_id)
                SELECT ?, COALESCE(MAX(version), 0) + 1, '# Story spec (test fixture)', ?
                FROM story_spec_version WHERE story_id = ?
                RETURNING id
                """, UUID.class, storyId, runId, storyId);
        StorySpecVersion version = new StorySpecVersion();
        version.setId(id);
        version.setStoryId(storyId);
        version.setRunId(runId);
        Integer v = jdbc.queryForObject("SELECT version FROM story_spec_version WHERE id = ?", Integer.class, id);
        version.setVersion(v);
        return version;
    }
}
