package com.choruskube.core.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Entity
@Table(name = "repo_group")
@DiscriminatorValue("repo_group")
public class RepoGroup extends SoftwareProject {

    @OneToMany(mappedBy = "repoGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<RepoGroupMember> members = new ArrayList<>();

    @Column(name = "enable_docker", nullable = false)
    private boolean enableDocker;

    @Column(name = "dind_image")
    private String dindImage;

    @Override
    public RuntimeRequirements getRuntimeRequirements() {
        return new RuntimeRequirements(getAgentImage(), enableDocker, dindImage);
    }

    @Override
    public List<GitRepo> resolveRepos() {
        return members.stream()
                .sorted(Comparator.comparingInt(RepoGroupMember::getPosition))
                .map(RepoGroupMember::getGitRepo)
                .toList();
    }

    public List<RepoGroupMember> getMembers() {
        return members;
    }

    public void setMembers(List<RepoGroupMember> members) {
        this.members = members;
    }

    public boolean isEnableDocker() {
        return enableDocker;
    }

    public void setEnableDocker(boolean enableDocker) {
        this.enableDocker = enableDocker;
    }

    public String getDindImage() {
        return dindImage;
    }

    public void setDindImage(String dindImage) {
        this.dindImage = dindImage;
    }
}
