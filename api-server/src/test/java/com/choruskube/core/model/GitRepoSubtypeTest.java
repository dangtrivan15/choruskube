package com.choruskube.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitRepoSubtypeTest {

    @Test
    void git_repo_is_a_software_project() {
        GitRepo repo = new GitRepo();
        assertThat(repo).isInstanceOf(SoftwareProject.class);
    }

    @Test
    void runtime_requirements_use_stored_image_and_docker_flag() {
        GitRepo repo = new GitRepo();
        repo.setAgentImage("registry/agent:v1");
        repo.setEnableDocker(true);
        RuntimeRequirements rr = repo.getRuntimeRequirements();
        assertThat(rr.agentImage()).isEqualTo("registry/agent:v1");
        assertThat(rr.enableDocker()).isTrue();
    }

    @Test
    void resolve_repos_returns_self() {
        GitRepo repo = new GitRepo();
        assertThat(repo.resolveRepos()).containsExactly(repo);
    }
}
