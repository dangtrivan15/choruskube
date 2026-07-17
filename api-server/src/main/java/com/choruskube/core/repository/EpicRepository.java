package com.choruskube.core.repository;

import com.choruskube.core.model.Epic;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EpicRepository extends JpaRepository<Epic, UUID>, JpaSpecificationExecutor<Epic> {
    List<Epic> findBySoftwareProjectIdOrderByCreatedAtDesc(UUID softwareProjectId);

    /**
     * Used by RepoGroupController#delete to guard against hard-deleting a RepoGroup (and its
     * backing software_project row) while an Epic still references it. Epic has no rollup status
     * of its own to filter on (unlike Task's countNonDoneBySoftwareProjectId) and its
     * software_project_id FK has no ON DELETE clause, so any existing Epic — regardless of its
     * descendants' status — would otherwise leave a dangling reference and turn the delete into an
     * unhandled DataIntegrityViolationException instead of a clean 409.
     */
    long countBySoftwareProjectId(UUID softwareProjectId);
}
