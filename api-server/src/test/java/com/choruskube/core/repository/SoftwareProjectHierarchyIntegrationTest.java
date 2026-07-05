package com.choruskube.core.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.RepoGroupMember;
import com.choruskube.core.model.SoftwareProject;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Round-trips the SoftwareProject JOINED-inheritance hierarchy against TestContainers Postgres:
 * GitRepo and RepoGroup persist correctly, load polymorphically via {@link
 * SoftwareProjectRepository}, and {@code findAll} returns both subtypes.
 */
@Transactional
class SoftwareProjectHierarchyIntegrationTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    SoftwareProjectRepository softwareProjects;

    @Autowired
    GitRepoRepository gitRepos;

    @Autowired
    RepoGroupRepository repoGroups;

    @Test
    void persists_git_repo_and_loads_via_software_project_repo_polymorphically() {
        GitRepo repo = new GitRepo();
        repo.setName("my-repo");
        repo.setUrl("https://github.com/owner/my-repo.git");
        repo.setDefaultBranch("main");
        repo.setAgentImage("registry/agent:v1");
        repo.setEnableDocker(true);
        gitRepos.save(repo);

        SoftwareProject loaded = softwareProjects.findById(repo.getId()).orElseThrow();
        assertThat(loaded).isInstanceOf(GitRepo.class);
        assertThat(loaded.getName()).isEqualTo("my-repo");
        assertThat(loaded.getRuntimeRequirements().agentImage()).isEqualTo("registry/agent:v1");
        assertThat(loaded.getRuntimeRequirements().enableDocker()).isTrue();
    }

    @Test
    void persists_repo_group_with_members_and_round_trips() {
        GitRepo r1 = saveRepo("r1", true);
        GitRepo r2 = saveRepo("r2", false);

        RepoGroup group = new RepoGroup();
        group.setName("my-project");
        group.setAgentImage("registry/group-agent:v1");
        RepoGroupMember m0 = newMember(group, r1, 0);
        RepoGroupMember m1 = newMember(group, r2, 1);
        group.setMembers(new ArrayList<>(List.of(m0, m1)));
        repoGroups.save(group);

        RepoGroup loaded = repoGroups.findById(group.getId()).orElseThrow();
        assertThat(loaded.getMembers()).hasSize(2);
        assertThat(loaded.resolveRepos()).extracting(GitRepo::getName).containsExactly("r1", "r2");
        // anyDocker aggregation: r1=true, r2=false → true
        assertThat(loaded.getRuntimeRequirements().enableDocker()).isTrue();
        assertThat(loaded.getRuntimeRequirements().agentImage()).isEqualTo("registry/group-agent:v1");
    }

    @Test
    void list_all_software_projects_returns_both_subtypes() {
        // Unique per-run names so the assertion is robust to committed seed data (no org filter to
        // isolate by — the organization_id column was dropped in V70).
        String repoName = "solo-" + UUID.randomUUID();
        String groupName = "group-" + UUID.randomUUID();
        saveRepo(repoName, false);
        RepoGroup group = new RepoGroup();
        group.setName(groupName);
        repoGroups.save(group);

        List<SoftwareProject> projects = softwareProjects.findAll();
        // Both subtypes load polymorphically through the parent repository.
        assertThat(projects)
                .filteredOn(p -> repoName.equals(p.getName()) || groupName.equals(p.getName()))
                .extracting(p -> p.getClass().getSimpleName())
                .containsExactlyInAnyOrder("GitRepo", "RepoGroup");
    }

    private GitRepo saveRepo(String name, boolean docker) {
        GitRepo r = new GitRepo();
        r.setName(name);
        r.setUrl("https://github.com/owner/" + name + ".git");
        r.setDefaultBranch("main");
        r.setEnableDocker(docker);
        return gitRepos.save(r);
    }

    private RepoGroupMember newMember(RepoGroup group, GitRepo repo, int position) {
        RepoGroupMember m = new RepoGroupMember();
        m.setRepoGroup(group);
        m.setGitRepo(repo);
        m.setPosition(position);
        return m;
    }
}
