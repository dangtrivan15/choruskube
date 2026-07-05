package com.choruskube.core.model;

import jakarta.persistence.CascadeType;
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

    @Override
    public RuntimeRequirements getRuntimeRequirements() {
        boolean anyDocker = members.stream().map(RepoGroupMember::getGitRepo).anyMatch(GitRepo::isEnableDocker);
        return new RuntimeRequirements(getAgentImage(), anyDocker);
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
}
