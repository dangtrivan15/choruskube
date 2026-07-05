package com.choruskube.core.repository;

import com.choruskube.core.model.RepoGroup;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RepoGroupRepository extends JpaRepository<RepoGroup, UUID>, JpaSpecificationExecutor<RepoGroup> {

    // open-in-view is disabled, so any read path that serializes `members` (or
    // `members.gitRepo`) into a response DTO must fetch the collection eagerly
    // — otherwise we hit LazyInitializationException at controller-render time.
    @Override
    @EntityGraph(attributePaths = {"members", "members.gitRepo"})
    Optional<RepoGroup> findById(UUID id);
}
