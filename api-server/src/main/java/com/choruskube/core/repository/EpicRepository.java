package com.choruskube.core.repository;

import com.choruskube.core.model.Epic;
import java.util.Collection;
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

    /**
     * Backs a single Milestone's {@code epicCount} on {@code DefaultMilestoneService}'s
     * {@code get()}/{@code create()}/{@code update()} — one Milestone, one query, nothing to
     * batch.
     */
    long countByMilestoneId(UUID milestoneId);

    /**
     * Backs the batched {@code epicCount} computation {@code DefaultMilestoneService.list()}
     * needs: loads every Epic tagged with any Milestone on the page in one query, then groups and
     * counts in memory, rather than issuing a per-Milestone {@link #countByMilestoneId} call
     * inside a loop (the same N+1 shape already avoided once on the Epic→Milestone side of
     * {@code DefaultEpicService#toResponses}, mirrored onto the Milestone→Epic direction here).
     */
    List<Epic> findByMilestoneIdIn(Collection<UUID> milestoneIds);
}
