package com.choruskube.core.model;

import com.choruskube.core.model.enums.WorkItemStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "epic")
public class Epic extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String motivation;

    @Column(name = "software_project_id", nullable = false)
    private UUID softwareProjectId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "work_item_status")
    private WorkItemStatus stage = WorkItemStatus.backlog;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMotivation() {
        return motivation;
    }

    public void setMotivation(String motivation) {
        this.motivation = motivation;
    }

    public UUID getSoftwareProjectId() {
        return softwareProjectId;
    }

    public void setSoftwareProjectId(UUID softwareProjectId) {
        this.softwareProjectId = softwareProjectId;
    }

    public WorkItemStatus getStage() {
        return stage;
    }

    public void setStage(WorkItemStatus stage) {
        this.stage = stage;
    }
}
