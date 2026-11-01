package com.choruskube.core.repository;

import com.choruskube.core.model.Story;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

public interface StoryRepository extends JpaRepository<Story, UUID>, JpaSpecificationExecutor<Story> {
    List<Story> findByEpicIdOrderByCreatedAtDesc(UUID epicId);

    /** Batch finder used to avoid N+1 when computing an Epic list's rollup status/progress. */
    List<Story> findByEpicIdIn(Collection<UUID> epicIds);

    /**
     * {@code SELECT ... FOR UPDATE}, held until the transaction ends. Every Story Design start and
     * every spec version write takes it, so two starters cannot both pass the "no open design run"
     * guard, nor two writers claim the same version number.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Story> findWithLockById(UUID id);
}
