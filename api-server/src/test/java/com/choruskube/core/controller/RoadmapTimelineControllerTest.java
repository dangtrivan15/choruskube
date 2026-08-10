package com.choruskube.core.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.OrgSecurity;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.service.EpicService;
import com.choruskube.core.service.RunEventPublisher;
import com.choruskube.core.service.StoryService;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class RoadmapTimelineControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private OrgSecurity orgSecurity;

    @BeforeEach
    void setUp() {
        when(orgSecurity.canRead()).thenReturn(true);
        when(orgSecurity.canOperate()).thenReturn(true);
        when(orgSecurity.canAdmin()).thenReturn(true);
    }

    @Test
    void getTimeline_returns200WithExpectedShape() throws Exception {
        // @Transactional rolls back after every test method (same isolation
        // RoadmapTimelineServiceTest relies on), so this Epic/Story pair is the only roadmap data
        // visible to the single-tenant (NoOpScopeProvider) scope for this request.
        EpicResponse epic = makeEpic("https://github.com/test/timeline-ctrl-shape.git");
        StoryResponse story = storyService.create(epic.id(), new StoryRequest("S", "D"));

        mockMvc.perform(get("/api/v1/roadmap/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epics.length()").value(1))
                .andExpect(jsonPath("$.epics[0].id").value(epic.id().toString()))
                .andExpect(jsonPath("$.epics[0].title").value(epic.title()))
                .andExpect(jsonPath("$.epics[0].stage").value(epic.stage()))
                .andExpect(jsonPath("$.epics[0].stories.length()").value(1))
                .andExpect(jsonPath("$.epics[0].stories[0].id").value(story.id().toString()))
                .andExpect(
                        jsonPath("$.epics[0].stories[0].epicId").value(epic.id().toString()))
                .andExpect(jsonPath("$.epics[0].stories[0].title").value(story.title()))
                .andExpect(jsonPath("$.epics[0].stories[0].stage").value(story.stage()));
    }

    @Test
    void getTimeline_multipleEpics_eachAppearsExactlyOnceWithItsOwnStories() throws Exception {
        // Guards against a controller-/service-wiring bug that would call the underlying queries
        // more than once and duplicate or cross-wire results (the "delegates to the service exactly
        // once" case): epicA and epicB carry different Story counts, so a duplication or
        // cross-Epic-grouping bug shows up as a length mismatch rather than being masked by
        // coincidentally-equal counts. Ascending-createdAt ordering (asserted separately by
        // RoadmapTimelineServiceTest) is relied on here only to pick a stable index for each Epic.
        EpicResponse epicA = makeEpic("https://github.com/test/timeline-ctrl-delegate-a.git");
        storyService.create(epicA.id(), new StoryRequest("A1", "D"));

        EpicResponse epicB = makeEpic("https://github.com/test/timeline-ctrl-delegate-b.git");
        storyService.create(epicB.id(), new StoryRequest("B1", "D"));
        storyService.create(epicB.id(), new StoryRequest("B2", "D"));

        mockMvc.perform(get("/api/v1/roadmap/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epics.length()").value(2))
                .andExpect(jsonPath("$.epics[0].id").value(epicA.id().toString()))
                .andExpect(jsonPath("$.epics[0].stories.length()").value(1))
                .andExpect(jsonPath("$.epics[1].id").value(epicB.id().toString()))
                .andExpect(jsonPath("$.epics[1].stories.length()").value(2));
    }

    @Test
    void getTimeline_withoutReadPermission_returns403() throws Exception {
        when(orgSecurity.canRead()).thenReturn(false);

        mockMvc.perform(get("/api/v1/roadmap/timeline")).andExpect(status().isForbidden());
    }

    private EpicResponse makeEpic(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        r = gitRepoRepo.save(r);
        return epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
    }
}
