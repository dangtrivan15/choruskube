package com.choruskube.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RepoGroupTest {

    @Test
    void runtime_requirements_use_groups_own_image() {
        GitRepo r1 = repoWithDocker(false);
        GitRepo r2 = repoWithDocker(false);
        RepoGroup group = groupWith("registry/group-agent:v1", r1, r2);
        assertThat(group.getRuntimeRequirements().agentImage()).isEqualTo("registry/group-agent:v1");
    }

    @Test
    void enable_docker_is_or_aggregation_of_members_all_off() {
        RepoGroup group = groupWith(null, repoWithDocker(false), repoWithDocker(false));
        assertThat(group.getRuntimeRequirements().enableDocker()).isFalse();
    }

    @Test
    void enable_docker_is_or_aggregation_of_members_mixed() {
        RepoGroup group = groupWith(null, repoWithDocker(false), repoWithDocker(true));
        assertThat(group.getRuntimeRequirements().enableDocker()).isTrue();
    }

    @Test
    void enable_docker_is_or_aggregation_of_members_all_on() {
        RepoGroup group = groupWith(null, repoWithDocker(true), repoWithDocker(true));
        assertThat(group.getRuntimeRequirements().enableDocker()).isTrue();
    }

    @Test
    void resolve_repos_returns_members_in_position_order() {
        GitRepo r1 = repoWithDocker(false);
        GitRepo r2 = repoWithDocker(false);
        GitRepo r3 = repoWithDocker(false);
        RepoGroup group = new RepoGroup();
        // members supplied out of position order; resolveRepos() must sort
        group.setMembers(List.of(member(group, r2, 1), member(group, r3, 2), member(group, r1, 0)));
        assertThat(group.resolveRepos()).containsExactly(r1, r2, r3);
    }

    private static GitRepo repoWithDocker(boolean enableDocker) {
        GitRepo repo = new GitRepo();
        repo.setEnableDocker(enableDocker);
        return repo;
    }

    private static RepoGroupMember member(RepoGroup group, GitRepo repo, int position) {
        RepoGroupMember m = new RepoGroupMember();
        m.setRepoGroup(group);
        m.setGitRepo(repo);
        m.setPosition(position);
        return m;
    }

    private static RepoGroup groupWith(String agentImage, GitRepo... repos) {
        RepoGroup group = new RepoGroup();
        group.setAgentImage(agentImage);
        java.util.List<RepoGroupMember> members = new java.util.ArrayList<>();
        for (int i = 0; i < repos.length; i++) {
            members.add(member(group, repos[i], i));
        }
        group.setMembers(members);
        return group;
    }
}
