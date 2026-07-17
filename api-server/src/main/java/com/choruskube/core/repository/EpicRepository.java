package com.choruskube.core.repository;

import com.choruskube.core.model.Epic;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EpicRepository extends JpaRepository<Epic, UUID>, JpaSpecificationExecutor<Epic> {
    List<Epic> findBySoftwareProjectIdOrderByCreatedAtDesc(UUID softwareProjectId);
}
