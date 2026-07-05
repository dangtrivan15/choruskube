package com.choruskube.core.repository;

import com.choruskube.core.model.SoftwareProject;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SoftwareProjectRepository
        extends JpaRepository<SoftwareProject, UUID>, JpaSpecificationExecutor<SoftwareProject> {

    Optional<SoftwareProject> findByName(String name);
}
