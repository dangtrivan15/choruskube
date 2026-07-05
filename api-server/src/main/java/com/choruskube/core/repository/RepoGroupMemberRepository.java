package com.choruskube.core.repository;

import com.choruskube.core.model.RepoGroupMember;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepoGroupMemberRepository extends JpaRepository<RepoGroupMember, RepoGroupMember.PK> {

    @Query("SELECT m FROM RepoGroupMember m WHERE m.gitRepo.id = :gitRepoId")
    List<RepoGroupMember> findByGitRepoId(@Param("gitRepoId") UUID gitRepoId);

    @Query("SELECT COUNT(m) FROM RepoGroupMember m WHERE m.repoGroup.id = :groupId")
    long countByRepoGroupId(@Param("groupId") UUID groupId);
}
