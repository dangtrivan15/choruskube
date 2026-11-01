package com.choruskube.core;

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
}
