package com.choruskube.core.repository;

import com.choruskube.core.model.RepoGroupDependency;
import com.choruskube.core.model.RepoGroupDependencyId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface RepoGroupDependencyRepository extends JpaRepository<RepoGroupDependency, RepoGroupDependencyId> {

    List<RepoGroupDependency> findByRepoGroupId(UUID repoGroupId);

    @Modifying
    void deleteByRepoGroupId(UUID repoGroupId);
}
