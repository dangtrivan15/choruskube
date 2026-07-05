package com.choruskube.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.executor.ExecutionParams;
import com.choruskube.core.executor.ExecutionResult;
import com.choruskube.core.executor.WorkloadExecutor;
import com.choruskube.core.model.*;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.LiveChatStatus;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.*;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class LiveChatControllerTest extends BaseTest {

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
    private GitRepoRepository gitRepoRepo;

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

    @BeforeEach
    void setUp() {
        when(workloadExecutor.execute(any(ExecutionParams.class)))
                .thenReturn(new ExecutionResult("agent-chat1234", "fakesecretsha256hash"));
    }

    private record TestData(WorkflowRun run, NodeExecution gateExec, TemplateNode gateNode) {}

    private TestData createTestData() {
        return createTestDataInternal(null);
    }

    @Test
    void startLiveChat_createsSessionSpawnsPodAndUpdatesNodeStatus() throws Exception {
        TestData data = createTestData();

        mockMvc.perform(post(
                                "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat",
                                data.run.getId(),
                                data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nodeExecutionId")
                        .value(data.gateExec.getId().toString()))
                .andExpect(jsonPath("$.workflowRunId").value(data.run.getId().toString()))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.chatPodName").value("agent-chat1234"));

        // Verify node execution status was updated to live_chat with jobSecretHash
        NodeExecution updated = execRepo.findById(data.gateExec.getId()).orElseThrow();
        assertEquals(NodeExecutionStatus.live_chat, updated.getStatus());
        assertNotNull(updated.getJobSecretHash());
    }

    @Test
    void startLiveChat_failsWhenNotAwaitingHuman() throws Exception {
        TestData data = createTestData();

        // Change status to completed
        data.gateExec.setStatus(NodeExecutionStatus.completed);
        execRepo.save(data.gateExec);

        mockMvc.perform(post(
                                "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat",
                                data.run.getId(),
                                data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startLiveChat_failsWhenSessionAlreadyExists() throws Exception {
        TestData data = createTestData();

        // Create an existing active session
        LiveChatSession existingSession = new LiveChatSession();
        existingSession.setNodeExecutionId(data.gateExec.getId());
        existingSession.setWorkflowRunId(data.run.getId());
        existingSession.setStatus(LiveChatStatus.active);
        sessionRepo.save(existingSession);

        mockMvc.perform(post(
                                "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat",
                                data.run.getId(),
                                data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void getActiveSession_returnsSessionWhenExists() throws Exception {
        TestData data = createTestData();

        // Start a session first (this now also spawns a chat pod)
        mockMvc.perform(
                post("/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));

        // Get the active session
        mockMvc.perform(get(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeExecutionId")
                        .value(data.gateExec.getId().toString()))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.chatPodName").value("agent-chat1234"));
    }

    @Test
    void getActiveSession_returns404WhenNoSession() throws Exception {
        TestData data = createTestData();

        mockMvc.perform(get(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sendMessage_persistsUserMessageAndReturnsCreated() throws Exception {
        TestData data = createTestData();

        // Start a session first
        mockMvc.perform(
                post("/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));

        // Send a user message
        mockMvc.perform(post(
                                "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/messages",
                                data.run.getId(),
                                data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Can you explain the changes?\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("user"))
                .andExpect(jsonPath("$.content").value("Can you explain the changes?"))
                .andExpect(jsonPath("$.sessionId").isNotEmpty());
    }

    @Test
    void sendMessage_failsWhenNoActiveSession() throws Exception {
        TestData data = createTestData();

        mockMvc.perform(post(
                                "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/messages",
                                data.run.getId(),
                                data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Hello\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void completeLiveChat_buildsTranscriptFromStoredMessages() throws Exception {
        TestData data = createTestData();

        // Start a session
        mockMvc.perform(
                post("/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));

        // Send some messages
        mockMvc.perform(post(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/messages",
                        data.run.getId(),
                        data.gateExec.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"Can you explain?\"}"));

        // Complete the session (no decision, just transcript fallback)
        mockMvc.perform(post(
                                "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/complete",
                                data.run.getId(),
                                data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transcript\": \"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.transcript").value("**Human:** Can you explain?"));

        // Verify node execution went back to awaiting_human with jobSecretHash cleared
        NodeExecution updated = execRepo.findById(data.gateExec.getId()).orElseThrow();
        assertEquals(NodeExecutionStatus.awaiting_human, updated.getStatus());
        assertEquals("**Human:** Can you explain?", updated.getResult());
        assertNull(updated.getJobSecretHash());
    }

    @Test
    void completeLiveChat_setsTranscriptAndReturnsToAwaitingHuman() throws Exception {
        TestData data = createTestData();

        // Start a session first
        mockMvc.perform(
                post("/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));

        // Complete the session (with fallback transcript)
        mockMvc.perform(post(
                                "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/complete",
                                data.run.getId(),
                                data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transcript\": \"Human: looks good\\nAI: thanks\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.transcript").value("Human: looks good\nAI: thanks"));

        // Verify node execution went back to awaiting_human with jobSecretHash cleared
        NodeExecution updated = execRepo.findById(data.gateExec.getId()).orElseThrow();
        assertEquals(NodeExecutionStatus.awaiting_human, updated.getStatus());
        assertEquals("Human: looks good\nAI: thanks", updated.getResult());
        assertNull(updated.getJobSecretHash());
    }

    @Test
    void listSessions_returnsAllSessionsForRun() throws Exception {
        TestData data = createTestData();

        // Create a session
        LiveChatSession session = new LiveChatSession();
        session.setNodeExecutionId(data.gateExec.getId());
        session.setWorkflowRunId(data.run.getId());
        session.setStatus(LiveChatStatus.completed);
        session.setTranscript("test transcript");
        sessionRepo.save(session);

        mockMvc.perform(get("/api/v1/runs/{runId}/live-chat-sessions", data.run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].transcript").value("test transcript"));
    }

    @Test
    void completeLiveChat_returns404WhenNoActiveSession() throws Exception {
        TestData data = createTestData();

        mockMvc.perform(post(
                                "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/complete",
                                data.run.getId(),
                                data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transcript\": \"test\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listSessions_returnsEmptyListForRunWithNoSessions() throws Exception {
        TestData data = createTestData();

        mockMvc.perform(get("/api/v1/runs/{runId}/live-chat-sessions", data.run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getSession_returnsSpecificSessionById() throws Exception {
        TestData data = createTestData();

        LiveChatSession session = new LiveChatSession();
        session.setNodeExecutionId(data.gateExec.getId());
        session.setWorkflowRunId(data.run.getId());
        session.setStatus(LiveChatStatus.active);
        session = sessionRepo.save(session);

        mockMvc.perform(get("/api/v1/runs/{runId}/live-chat-sessions/{sessionId}", data.run.getId(), session.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(session.getId().toString()))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void getSession_returns404ForNonExistentSessionId() throws Exception {
        TestData data = createTestData();

        mockMvc.perform(get(
                        "/api/v1/runs/{runId}/live-chat-sessions/{sessionId}",
                        data.run.getId(),
                        java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void startLiveChat_afterCompletingPreviousSessionWorks() throws Exception {
        TestData data = createTestData();

        // Start first session
        mockMvc.perform(
                post("/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));

        // Complete first session
        mockMvc.perform(post(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/complete",
                        data.run.getId(),
                        data.gateExec.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transcript\": \"first chat\"}"));

        // Start second session
        mockMvc.perform(post(
                                "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat",
                                data.run.getId(),
                                data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"));

        // Verify there are now 2 sessions
        mockMvc.perform(get("/api/v1/runs/{runId}/live-chat-sessions", data.run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void fullLifecycle_startSendComplete_verifyTranscript() throws Exception {
        TestData data = createTestData();

        // Start session
        mockMvc.perform(
                post("/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));

        // Verify run status stays as awaiting_human — the orchestrator owns run-level status
        WorkflowRun updatedRun = runRepo.findById(data.run.getId()).orElseThrow();
        assertEquals(WorkflowRunStatus.awaiting_human, updatedRun.getStatus());

        // Send human message
        mockMvc.perform(post(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/messages",
                        data.run.getId(),
                        data.gateExec.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"Please explain the changes\"}"));

        // Complete session
        mockMvc.perform(post(
                                "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/complete",
                                data.run.getId(),
                                data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transcript\": \"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.transcript").value("**Human:** Please explain the changes"));

        // Verify node execution went back to awaiting_human
        NodeExecution updated = execRepo.findById(data.gateExec.getId()).orElseThrow();
        assertEquals(NodeExecutionStatus.awaiting_human, updated.getStatus());
        assertEquals("**Human:** Please explain the changes", updated.getResult());
        assertNull(updated.getJobSecretHash());

        // Verify run status remained awaiting_human throughout — the orchestrator owns run-level status
        WorkflowRun finalRun = runRepo.findById(data.run.getId()).orElseThrow();
        assertEquals(WorkflowRunStatus.awaiting_human, finalRun.getStatus());
    }

    @Test
    void getSessionMessages_returnsMessagesForSession() throws Exception {
        TestData data = createTestData();

        // Start session
        mockMvc.perform(
                post("/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));

        // Send some messages
        mockMvc.perform(post(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/messages",
                        data.run.getId(),
                        data.gateExec.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"First message\"}"));

        mockMvc.perform(post(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/messages",
                        data.run.getId(),
                        data.gateExec.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"Second message\"}"));

        // Get session ID from list
        LiveChatSession session = sessionRepo
                .findByNodeExecutionIdAndStatusIn(
                        data.gateExec.getId(), java.util.List.of(LiveChatStatus.pending, LiveChatStatus.active))
                .orElseThrow();

        // Get messages via the new endpoint
        mockMvc.perform(get(
                        "/api/v1/runs/{runId}/live-chat-sessions/{sessionId}/messages",
                        data.run.getId(),
                        session.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content").value("First message"))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[1].content").value("Second message"));
    }

    @Test
    void getLatestSession_returnsSessionRegardlessOfStatus() throws Exception {
        TestData data = createTestData();

        // Create a completed session
        LiveChatSession session = new LiveChatSession();
        session.setNodeExecutionId(data.gateExec.getId());
        session.setWorkflowRunId(data.run.getId());
        session.setStatus(LiveChatStatus.completed);
        session.setTranscript("done");
        session = sessionRepo.save(session);

        // getActiveSession now falls back to completed sessions
        mockMvc.perform(get(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.transcript").value("done"));

        // getLatestSession should also succeed
        mockMvc.perform(get(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/latest",
                        data.run.getId(),
                        data.gateExec.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(session.getId().toString()))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.transcript").value("done"));
    }

    @Test
    void getLatestSession_returns404WhenNoSessionsExist() throws Exception {
        TestData data = createTestData();

        mockMvc.perform(get(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/latest",
                        data.run.getId(),
                        data.gateExec.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void completeLiveChat_accumulatesTranscriptFromPreviousSessions() throws Exception {
        TestData data = createTestData();

        // Start session 1
        mockMvc.perform(
                post("/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));

        // Send a message in session 1
        mockMvc.perform(post(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/messages",
                        data.run.getId(),
                        data.gateExec.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"Hello from session 1\"}"));

        // Complete session 1
        mockMvc.perform(post(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/complete",
                        data.run.getId(),
                        data.gateExec.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transcript\": \"\"}"));

        // Verify session 1 transcript is set
        NodeExecution afterSession1 = execRepo.findById(data.gateExec.getId()).orElseThrow();
        assertEquals("**Human:** Hello from session 1", afterSession1.getResult());

        // Start session 2
        mockMvc.perform(
                post("/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));

        // Send a message in session 2
        mockMvc.perform(post(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/messages",
                        data.run.getId(),
                        data.gateExec.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"Hello from session 2\"}"));

        // Complete session 2
        mockMvc.perform(post(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/complete",
                        data.run.getId(),
                        data.gateExec.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transcript\": \"\"}"));

        // Verify both transcripts are accumulated with --- separator
        NodeExecution afterSession2 = execRepo.findById(data.gateExec.getId()).orElseThrow();
        assertEquals(
                "**Human:** Hello from session 1\n\n---\n\n**Human:** Hello from session 2", afterSession2.getResult());
    }

    @Test
    void completeLiveChat_emptyNewSession_preservesExistingResult() throws Exception {
        TestData data = createTestData();

        // Set an existing result on the node execution
        data.gateExec.setResult("Previous transcript content");
        execRepo.save(data.gateExec);

        // Start a session
        mockMvc.perform(
                post("/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));

        // Complete immediately with no messages and empty transcript
        mockMvc.perform(post(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/complete",
                        data.run.getId(),
                        data.gateExec.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transcript\": \"\"}"));

        // Verify existing result is preserved unchanged
        NodeExecution updated = execRepo.findById(data.gateExec.getId()).orElseThrow();
        assertEquals("Previous transcript content", updated.getResult());
    }

    @Test
    void getActiveSession_returnsCompletedSessionWhenNoActiveExists() throws Exception {
        TestData data = createTestData();

        // Start and complete a session
        mockMvc.perform(
                post("/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));

        mockMvc.perform(post(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/complete",
                        data.run.getId(),
                        data.gateExec.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transcript\": \"test transcript\"}"));

        // getActiveSession should now return the completed session (not 404)
        mockMvc.perform(get(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.transcript").value("test transcript"));
    }

    @Test
    void getActiveSession_prefersActiveOverCompleted() throws Exception {
        TestData data = createTestData();

        // Start and complete session 1
        mockMvc.perform(
                post("/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));

        mockMvc.perform(post(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat/complete",
                        data.run.getId(),
                        data.gateExec.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transcript\": \"session 1 transcript\"}"));

        // Start session 2 (now active)
        mockMvc.perform(
                post("/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));

        // getActiveSession should return the active session 2, not the completed session 1
        mockMvc.perform(get(
                        "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat", data.run.getId(), data.gateExec.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void startLiveChat_doesNotChangeRunStatus() throws Exception {
        TestData data = createTestData();

        mockMvc.perform(post(
                                "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat",
                                data.run.getId(),
                                data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());

        // Verify run status was NOT changed — the orchestrator owns run-level status
        WorkflowRun updatedRun = runRepo.findById(data.run.getId()).orElseThrow();
        assertEquals(WorkflowRunStatus.awaiting_human, updatedRun.getStatus());
    }

    private TestData createTestDataWithGitRepo() {
        GitRepo repo = new GitRepo();
        repo.setUrl("https://github.com/test-owner/test-repo.git");
        repo.setName(RepoNameUtil.deriveOwnerRepoName("https://github.com/test-owner/test-repo.git"));
        repo = gitRepoRepo.save(repo);
        return createTestDataInternal(repo);
    }

    private TestData createTestDataInternal(GitRepo repo) {
        GraphTemplate template = new GraphTemplate();
        template.setName(repo != null ? "Test Workflow With Repo" : "Test Workflow");
        template.setGraphId(repo != null ? "test-live-chat-repo" : "test-live-chat");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("human-review");
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

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setName(repo != null ? "Live Chat Repo Test Run" : "Live Chat Test Run");
        run.setStatus(WorkflowRunStatus.awaiting_human);
        if (repo != null) {
            run.setInputs("{\"git_repo_id\": \"" + repo.getId() + "\"}");
        }
        run = runRepo.save(run);

        NodeExecution gateExec = new NodeExecution();
        gateExec.setWorkflowRunId(run.getId());
        gateExec.setTemplateNodeId(gateNode.getId());
        gateExec.setStatus(NodeExecutionStatus.awaiting_human);
        gateExec.setGraphVersion(1);
        gateExec = execRepo.save(gateExec);

        return new TestData(run, gateExec, gateNode);
    }

    @Test
    void startLiveChat_populatesConfigJsonWorkspaceFields() throws Exception {
        TestData data = createTestDataWithGitRepo();

        mockMvc.perform(post(
                                "/api/v1/runs/{runId}/nodes/{nodeExecId}/live-chat",
                                data.run.getId(),
                                data.gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());

        // Verify config.json includes workspace fields resolved from the git repo.
        // (Namespace is no longer part of core's execution path / response.)
        ArgumentCaptor<ExecutionParams> paramsCaptor = ArgumentCaptor.forClass(ExecutionParams.class);
        verify(workloadExecutor).execute(paramsCaptor.capture());
        ExecutionParams capturedParams = paramsCaptor.getValue();

        @SuppressWarnings("unchecked")
        Map<String, Object> configJson = (Map<String, Object>) capturedParams.configJson();
        assertEquals("https://github.com/test-owner/test-repo.git", configJson.get("repo_url"));
        assertNotNull(configJson.get("working_branch"));
        assertNotNull(configJson.get("github_token_url"));
        assertNotNull(configJson.get("run_log_path"));
    }

    // Deleted startLiveChat_resolvesNamespaceFromRunOrg_whenNoGitRepo and
    // startLiveChat_storesNamespaceOnSession: namespace is no longer carried on the
    // live-chat session, the response, or ExecutionParams in core.
}
