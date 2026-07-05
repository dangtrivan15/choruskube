package com.choruskube.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "template_node")
public class TemplateNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "graph_template_id", nullable = false)
    private UUID graphTemplateId;

    @Column(name = "node_definition_id", nullable = false)
    private UUID nodeDefinitionId;

    @Column(nullable = false)
    private String label;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_overrides", columnDefinition = "jsonb", nullable = false)
    private String configOverrides;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_input_artifacts", columnDefinition = "jsonb")
    private String requiredInputArtifacts;

    @Column(name = "is_entrypoint", nullable = false)
    private boolean entrypoint;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getGraphTemplateId() {
        return graphTemplateId;
    }

    public void setGraphTemplateId(UUID graphTemplateId) {
        this.graphTemplateId = graphTemplateId;
    }

    public UUID getNodeDefinitionId() {
        return nodeDefinitionId;
    }

    public void setNodeDefinitionId(UUID nodeDefinitionId) {
        this.nodeDefinitionId = nodeDefinitionId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getConfigOverrides() {
        return configOverrides;
    }

    public void setConfigOverrides(String configOverrides) {
        this.configOverrides = configOverrides;
    }

    public String getRequiredInputArtifacts() {
        return requiredInputArtifacts;
    }

    public void setRequiredInputArtifacts(String requiredInputArtifacts) {
        this.requiredInputArtifacts = requiredInputArtifacts;
    }

    public boolean isEntrypoint() {
        return entrypoint;
    }

    public void setEntrypoint(boolean entrypoint) {
        this.entrypoint = entrypoint;
    }
}
