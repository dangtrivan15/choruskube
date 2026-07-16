package com.choruskube.core.repository;

import com.choruskube.core.model.Story;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface StoryRepository extends JpaRepository<Story, UUID>, JpaSpecificationExecutor<Story> {
    List<Story> findByEpicIdOrderByCreatedAtDesc(UUID epicId);

    /** Batch finder used to avoid N+1 when computing an Epic list's rollup status/progress. */
    List<Story> findByEpicIdIn(Collection<UUID> epicIds);
}
