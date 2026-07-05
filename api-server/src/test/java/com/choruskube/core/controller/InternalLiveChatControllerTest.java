package com.choruskube.core.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.InternalAuthFilter;
import com.choruskube.core.executor.WorkloadExecutor;
import com.choruskube.core.model.*;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.LiveChatStatus;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.*;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            "internal.auth.orchestrator-secret-hash=d6c5f99f36089f6757e4a7946de9dd0ef1d69983ab5920d40ce5ee1d5066159d",
            "internal.auth.mode=enforce"
        })
class InternalLiveChatControllerTest extends BaseTest {

    private static final String ORCHESTRATOR_SECRET = "test-orchestrator-secret";
    private static final String JOB_SECRET = "test-live-chat-job-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @Autowired
    private NodeDefinitionRepository nodeDefRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private LiveChatSessionRepository sessionRepo;

    @Autowired
    private LiveChatMessageRepository messageRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private WorkloadExecutor workloadExecutor;

    private WorkflowRun run;
    private NodeExecution gateExec;
    private LiveChatSession session;

    @BeforeEach
    void setUp() {
        GraphTemplate template = new GraphTemplate();
        template.setName("Internal Live Chat Test");
        template.setGraphId("internal-live-chat-test");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("human-gate");
        nodeDef.setExecutorType(ExecutorType.human);
        nodeDef.setImage("placeholder");
        nodeDef.setPromptTemplate("");
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef.setTimeoutSeconds(1800);
        nodeDef = nodeDefRepo.save(nodeDef);

        TemplateNode gateNode = new TemplateNode();
        gateNode.setGraphTemplateId(template.getId());
        gateNode.setNodeDefinitionId(nodeDef.getId());
        gateNode.setLabel("review_gate");
        gateNode.setConfigOverrides("{}");
        gateNode.setEntrypoint(true);
        gateNode = templateNodeRepo.save(gateNode);

        run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setName("Internal Live Chat Test Run");
        run.setStatus(WorkflowRunStatus.live_chat);
        run = runRepo.save(run);

        gateExec = new NodeExecution();
        gateExec.setWorkflowRunId(run.getId());
        gateExec.setTemplateNodeId(gateNode.getId());
        gateExec.setStatus(NodeExecutionStatus.live_chat);
        gateExec.setGraphVersion(1);
        gateExec.setJobSecretHash(InternalAuthFilter.sha256Hex(JOB_SECRET));
        gateExec = execRepo.save(gateExec);

        // Create an active session for the gate node
        session = new LiveChatSession();
        session.setNodeExecutionId(gateExec.getId());
        session.setWorkflowRunId(run.getId());
        session.setStatus(LiveChatStatus.active);
        session = sessionRepo.save(session);
    }

    private String basePath() {
        return "/internal/runs/" + run.getId() + "/node-executions/" + gateExec.getId() + "/live-chat";
    }

    @Test
    void updateSession_setsStatusToActive() throws Exception {
        // Create a pending session instead
        session.setStatus(LiveChatStatus.pending);
        sessionRepo.save(session);

        mockMvc.perform(put(basePath() + "/session")
                        .header("Authorization", "Bearer " + JOB_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"active\", \"chatPodName\": \"chat-pod-xyz\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.chatPodName").value("chat-pod-xyz"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty());
    }

    @Test
    void updateSession_setsTranscript() throws Exception {
        mockMvc.perform(put(basePath() + "/session")
                        .header("Authorization", "Bearer " + JOB_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transcript\": \"Full chat transcript here\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transcript").value("Full chat transcript here"));
    }

    @Test
    void getSession_returnsActiveSessionDetails() throws Exception {
        mockMvc.perform(get(basePath() + "/session").header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(session.getId().toString()))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.nodeExecutionId").value(gateExec.getId().toString()));
    }

    @Test
    void getSession_returns404WhenNoActiveSession() throws Exception {
        // Complete the session so there's no active one
        session.setStatus(LiveChatStatus.completed);
        sessionRepo.save(session);

        mockMvc.perform(get(basePath() + "/session").header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isNotFound());
    }

    @Test
    void relayMessage_persistsAssistantMessage() throws Exception {
        mockMvc.perform(
                        post(basePath() + "/messages")
                                .header("Authorization", "Bearer " + JOB_SECRET)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"type\": \"live_chat_message\", \"role\": \"assistant\", \"content\": \"Here is my response\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("assistant"))
                .andExpect(jsonPath("$.content").value("Here is my response"))
                .andExpect(jsonPath("$.sessionId").value(session.getId().toString()));
    }

    @Test
    void getMessages_returnsAllMessagesForSession() throws Exception {
        // Add some messages
        LiveChatMessage msg1 = new LiveChatMessage();
        msg1.setSessionId(session.getId());
        msg1.setRole("user");
        msg1.setContent("Hello AI");
        messageRepo.save(msg1);

        LiveChatMessage msg2 = new LiveChatMessage();
        msg2.setSessionId(session.getId());
        msg2.setRole("assistant");
        msg2.setContent("Hello Human");
        messageRepo.save(msg2);

        mockMvc.perform(get(basePath() + "/messages").header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].content").value("Hello AI"))
                .andExpect(jsonPath("$[1].role").value("assistant"))
                .andExpect(jsonPath("$[1].content").value("Hello Human"));
    }

    @Test
    void getMessages_withSinceParam_returnsOnlyNewerMessages() throws Exception {
        // Add a message in the past
        LiveChatMessage oldMsg = new LiveChatMessage();
        oldMsg.setSessionId(session.getId());
        oldMsg.setRole("user");
        oldMsg.setContent("Old message");
        oldMsg = messageRepo.save(oldMsg);

        // Record the timestamp after the first message
        String sinceTimestamp = oldMsg.getCreatedAt().toString();

        // Add a message after a brief pause
        // (use relay endpoint to ensure a different createdAt)
        mockMvc.perform(post(basePath() + "/messages")
                .header("Authorization", "Bearer " + JOB_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"live_chat_message\", \"role\": \"assistant\", \"content\": \"New message\"}"));

        // Get messages since the first message timestamp
        mockMvc.perform(get(basePath() + "/messages")
                        .param("since", sinceTimestamp)
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("New message"));
    }

    @Test
    void allEndpoints_requireValidAuth() throws Exception {
        // No auth header at all
        mockMvc.perform(get(basePath() + "/session")).andExpect(status().isUnauthorized());

        mockMvc.perform(put(basePath() + "/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"active\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(basePath() + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"live_chat_message\", \"role\": \"assistant\", \"content\": \"test\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(basePath() + "/messages")).andExpect(status().isUnauthorized());

        // Wrong secret
        mockMvc.perform(get(basePath() + "/session").header("Authorization", "Bearer wrong-secret"))
                .andExpect(status().isUnauthorized());
    }
}
