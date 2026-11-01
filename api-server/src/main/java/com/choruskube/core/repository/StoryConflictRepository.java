package com.choruskube.core.repository;

import com.choruskube.core.model.StoryConflict;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryConflictRepository extends JpaRepository<StoryConflict, UUID> {
    List<StoryConflict> findByReleasedAtIsNull();

    List<StoryConflict> findByStoryIdAndReleasedAtIsNullOrderByCreatedAtAsc(UUID storyId);

    boolean existsByAnalyzeNodeExecutionId(UUID analyzeNodeExecutionId);
}
