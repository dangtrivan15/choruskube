package com.choruskube.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "git_repo")
@DiscriminatorValue("git_repo")
@SQLRestriction("deleted_at IS NULL") // inherited semantics; redundant safety net during transition
public class GitRepo extends SoftwareProject {

    @Column(nullable = false)
    private String url;

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch = "main";

    @Column(name = "test_command")
    private String testCommand;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String secrets = "[]";

    @Column(name = "enable_docker", nullable = false)
    private boolean enableDocker;

    @Override
    public RuntimeRequirements getRuntimeRequirements() {
        return new RuntimeRequirements(getAgentImage(), enableDocker);
    }

    @Override
    public List<GitRepo> resolveRepos() {
        return List.of(this);
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getTestCommand() {
        return testCommand;
    }

    public void setTestCommand(String testCommand) {
        this.testCommand = testCommand;
    }

    public String getSecrets() {
        return secrets;
    }

    public void setSecrets(String secrets) {
        this.secrets = secrets;
    }

    public boolean isEnableDocker() {
        return enableDocker;
    }

    public void setEnableDocker(boolean enableDocker) {
        this.enableDocker = enableDocker;
    }
}
