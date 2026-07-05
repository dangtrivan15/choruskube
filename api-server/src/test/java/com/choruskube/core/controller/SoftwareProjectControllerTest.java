package com.choruskube.core.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.service.OrgIdentitySync;
import com.choruskube.core.service.RepoGroupService;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
class SoftwareProjectControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private RepoGroupService repoGroupService;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private OrgIdentitySync orgIdentity;

    @Test
    void list_returns_both_subtypes_with_type_discriminator() throws Exception {
        // Use unique names to avoid collisions with seeders / other tests in the same DB.
        String soloName = "solo-repo-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "my-group-" + UUID.randomUUID().toString().substring(0, 8);

        GitRepo solo = new GitRepo();
        solo.setName(soloName);
        solo.setUrl("https://github.com/owner/" + soloName + ".git");
        solo.setDefaultBranch("main");
        gitRepoRepo.saveAndFlush(solo);

        repoGroupService.create(groupName, null, null, List.of(solo.getId()));

        mockMvc.perform(get("/api/v1/software-projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='" + groupName + "')].type").value("repo_group"))
                .andExpect(jsonPath("$[?(@.name=='" + soloName + "')].type").value("git_repo"));
    }
}
