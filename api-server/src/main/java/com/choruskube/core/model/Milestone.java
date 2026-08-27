package com.choruskube.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A named release/grouping label (e.g. "Q3 Launch") scoped to a single {@code software_project}
 * (the "Group Epics under a named Milestone / Release" feature). Epics reference a
 * Milestone via a nullable {@code milestone_id} FK with {@code ON DELETE SET NULL},
 * so deleting a Milestone un-tags its Epics rather than deleting them.
 *
 * <p>{@code targetDate} drives the rollup progress bar and at-risk verdict on {@code
 * MilestoneResponse} ({@code DefaultMilestoneService}): a Milestone is "at risk" when this date is
 * strictly in the past (per the injected {@code Clock}) and at least one tagged Epic is still
 * incomplete. It is not yet used to position Milestones on the timeline.
 */
@Entity
@Table(name = "milestone")
public class Milestone extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "software_project_id", nullable = false)
    private UUID softwareProjectId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "target_date")
    private LocalDate targetDate;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSoftwareProjectId() {
        return softwareProjectId;
    }

    public void setSoftwareProjectId(UUID softwareProjectId) {
        this.softwareProjectId = softwareProjectId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public void setTargetDate(LocalDate targetDate) {
        this.targetDate = targetDate;
    }
}
