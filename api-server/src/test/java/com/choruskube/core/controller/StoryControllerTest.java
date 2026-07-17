package com.choruskube.core.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.service.EpicService;
import com.choruskube.core.service.RunEventPublisher;
import com.choruskube.core.service.TaskService;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class StoryControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.choruskube.core.repository.GitRepoRepository gitRepoRepo;

    @Autowired
    private EpicService epicService;

    @Autowired
    private com.choruskube.core.service.StoryService storyService;

    @Autowired
    private TaskService taskService;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @Test
    void createStory_returns201() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-create.git");

        var body = Map.of("title", "Story title", "description", "Story desc");

        mockMvc.perform(post("/api/v1/epics/" + epic.id() + "/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.epicId").value(epic.id().toString()))
                .andExpect(jsonPath("$.title").value("Story title"))
                .andExpect(jsonPath("$.status").value("backlog"));
    }

    @Test
    void createStory_underUnknownEpic_returns404() throws Exception {
        var body = Map.of("title", "T", "description", "D");

        mockMvc.perform(post("/api/v1/epics/" + UUID.randomUUID() + "/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listStories_returnsStoriesForEpic() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-list.git");
        makeStory(epic.id(), "S1");
        makeStory(epic.id(), "S2");

        mockMvc.perform(get("/api/v1/epics/" + epic.id() + "/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getStory_returnsStory() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-get.git");
        StoryResponse story = makeStory(epic.id(), "My Story");

        mockMvc.perform(get("/api/v1/stories/" + story.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("My Story"));
    }

    @Test
    void getStory_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/stories/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void updateStory_returns200() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-update.git");
        StoryResponse story = makeStory(epic.id(), "Old Title");

        var body = Map.of("title", "New Title", "description", "New Desc");

        mockMvc.perform(put("/api/v1/stories/" + story.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New Title"));
    }

    @Test
    void updateStory_withStartedTask_returns409() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-update-conflict.git");
        StoryResponse story = makeStory(epic.id(), "S");
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        taskService.start(task.id());

        var body = Map.of("title", "New Title", "description", "New Desc");

        mockMvc.perform(put("/api/v1/stories/" + story.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteStory_returns204() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-delete.git");
        StoryResponse story = makeStory(epic.id(), "To Delete");

        mockMvc.perform(delete("/api/v1/stories/" + story.id())).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/stories/" + story.id())).andExpect(status().isNotFound());
    }

    @Test
    void deleteStory_withStartedTask_returns409() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-delete-conflict.git");
        StoryResponse story = makeStory(epic.id(), "S");
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        taskService.start(task.id());

        mockMvc.perform(delete("/api/v1/stories/" + story.id())).andExpect(status().isConflict());
    }

    // --- helpers ---

    private EpicResponse makeEpic(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        r = gitRepoRepo.save(r);
        return epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
    }

    private StoryResponse makeStory(UUID epicId, String title) {
        return storyService.create(epicId, new StoryRequest(title, "Desc for " + title));
    }
}
