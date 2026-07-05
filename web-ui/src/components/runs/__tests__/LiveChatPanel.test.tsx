import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import LiveChatPanel from "../LiveChatPanel";

const mockStartMutate = vi.fn();
const mockCompleteMutate = vi.fn();
const mockSendMessageMutate = vi.fn();

vi.mock("@/hooks/useLiveChat", () => ({
  useLiveChatSession: vi.fn(() => ({
    data: undefined,
    isLoading: false,
  })),
  useStartLiveChat: vi.fn(() => ({
    mutate: mockStartMutate,
    isPending: false,
  })),
  useCompleteLiveChat: vi.fn(() => ({
    mutate: mockCompleteMutate,
    isPending: false,
  })),
  useSendLiveChatMessage: vi.fn(() => ({
    mutate: mockSendMessageMutate,
    isPending: false,
  })),
  useLiveChatMessages: vi.fn(() => ({
    messages: [],
    addMessage: vi.fn(),
    clearMessages: vi.fn(),
  })),
}));

describe("LiveChatPanel", () => {
  const defaultProps = {
    runId: "run-1",
    nodeExecId: "exec-1",
    nodeLabel: "Review Gate",
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders start live chat button when no session exists", () => {
    renderWithProviders(<LiveChatPanel {...defaultProps} />);
    expect(screen.getByTestId("start-live-chat-button")).toBeInTheDocument();
    expect(screen.getByText("Start Live Chat")).toBeInTheDocument();
  });

  it("calls startChat.mutate when Start Live Chat is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<LiveChatPanel {...defaultProps} />);
    await user.click(screen.getByTestId("start-live-chat-button"));
    expect(mockStartMutate).toHaveBeenCalledWith("exec-1");
  });

  it("renders chat panel when session is active", async () => {
    const { useLiveChatSession } = await import("@/hooks/useLiveChat");
    vi.mocked(useLiveChatSession).mockReturnValue({
      data: {
        id: "session-1",
        nodeExecutionId: "exec-1",
        workflowRunId: "run-1",
        sourceNodeExecutionId: null,
        status: "active",
        transcript: null,
        chatPodName: "chat-pod-1",
        startedAt: "2024-01-01T00:00:00Z",
        completedAt: null,
        createdAt: "2024-01-01T00:00:00Z",
      },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useLiveChatSession>);

    renderWithProviders(<LiveChatPanel {...defaultProps} />);
    expect(screen.getByTestId("live-chat-panel")).toBeInTheDocument();
    expect(screen.getByTestId("live-chat-status")).toHaveTextContent("active");
    // Should have "Close Live Chat" button instead of approve/reject
    expect(screen.getByTestId("live-chat-close")).toBeInTheDocument();
    expect(screen.getByText("Close Live Chat")).toBeInTheDocument();
    // Should NOT have approve/reject buttons
    expect(screen.queryByTestId("live-chat-approve")).not.toBeInTheDocument();
    expect(screen.queryByTestId("live-chat-reject")).not.toBeInTheDocument();
  });

  it("sends message via API when user types and clicks send", async () => {
    const mockAddMessage = vi.fn();
    const { useLiveChatSession, useLiveChatMessages } = await import("@/hooks/useLiveChat");
    vi.mocked(useLiveChatSession).mockReturnValue({
      data: {
        id: "session-1",
        nodeExecutionId: "exec-1",
        workflowRunId: "run-1",
        sourceNodeExecutionId: null,
        status: "active",
        transcript: null,
        chatPodName: "chat-pod-1",
        startedAt: "2024-01-01T00:00:00Z",
        completedAt: null,
        createdAt: "2024-01-01T00:00:00Z",
      },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useLiveChatSession>);
    vi.mocked(useLiveChatMessages).mockReturnValue({
      messages: [],
      addMessage: mockAddMessage,
      clearMessages: vi.fn(),
    });

    const user = userEvent.setup();
    renderWithProviders(<LiveChatPanel {...defaultProps} />);

    const input = screen.getByTestId("live-chat-input");
    await user.type(input, "Hello AI");
    await user.click(screen.getByTestId("live-chat-send"));

    // Should add message locally for optimistic display
    expect(mockAddMessage).toHaveBeenCalledWith("user", "Hello AI");
    // Should send via API
    expect(mockSendMessageMutate).toHaveBeenCalledWith("Hello AI");
  });

  it("renders prior transcript and start button when session is completed", async () => {
    const { useLiveChatSession } = await import("@/hooks/useLiveChat");
    vi.mocked(useLiveChatSession).mockReturnValue({
      data: {
        id: "session-1",
        nodeExecutionId: "exec-1",
        workflowRunId: "run-1",
        sourceNodeExecutionId: null,
        status: "completed",
        transcript: "**Human:** Hello\n\n**AI:** Hi there",
        chatPodName: "chat-pod-1",
        startedAt: "2024-01-01T00:00:00Z",
        completedAt: "2024-01-01T00:01:00Z",
        createdAt: "2024-01-01T00:00:00Z",
      },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useLiveChatSession>);

    renderWithProviders(<LiveChatPanel {...defaultProps} />);
    // Should show "Previous Chat Transcript" heading with prior transcript
    expect(screen.getByText("Previous Chat Transcript")).toBeInTheDocument();
    // Should also show "Start Live Chat" button alongside
    expect(screen.getByTestId("start-live-chat-button")).toBeInTheDocument();
    expect(screen.getByText("Start Live Chat")).toBeInTheDocument();
  });

  it("shows start button when session is completed with no transcript", async () => {
    const { useLiveChatSession } = await import("@/hooks/useLiveChat");
    vi.mocked(useLiveChatSession).mockReturnValue({
      data: {
        id: "session-1",
        nodeExecutionId: "exec-1",
        workflowRunId: "run-1",
        sourceNodeExecutionId: null,
        status: "completed",
        transcript: null,
        chatPodName: "chat-pod-1",
        startedAt: "2024-01-01T00:00:00Z",
        completedAt: "2024-01-01T00:01:00Z",
        createdAt: "2024-01-01T00:00:00Z",
      },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useLiveChatSession>);

    renderWithProviders(<LiveChatPanel {...defaultProps} />);
    // Should show "Start Live Chat" button
    expect(screen.getByTestId("start-live-chat-button")).toBeInTheDocument();
    // Should NOT show "Previous Chat Transcript" heading
    expect(screen.queryByText("Previous Chat Transcript")).not.toBeInTheDocument();
  });

  it("renders loading state when session is loading", async () => {
    const { useLiveChatSession } = await import("@/hooks/useLiveChat");
    vi.mocked(useLiveChatSession).mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
    } as ReturnType<typeof useLiveChatSession>);

    renderWithProviders(<LiveChatPanel {...defaultProps} />);
    // Should not show start button when loading
    expect(screen.queryByTestId("start-live-chat-button")).not.toBeInTheDocument();
  });

  it("renders start button when session query errors and no cached data", async () => {
    const { useLiveChatSession } = await import("@/hooks/useLiveChat");
    vi.mocked(useLiveChatSession).mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error("Not found"),
    } as ReturnType<typeof useLiveChatSession>);

    renderWithProviders(<LiveChatPanel {...defaultProps} />);
    expect(screen.getByTestId("start-live-chat-button")).toBeInTheDocument();
  });

  it("calls completeChat.mutate with transcript when Close Live Chat clicked", async () => {
    const { useLiveChatSession, useLiveChatMessages } = await import("@/hooks/useLiveChat");
    vi.mocked(useLiveChatSession).mockReturnValue({
      data: {
        id: "session-1",
        nodeExecutionId: "exec-1",
        workflowRunId: "run-1",
        sourceNodeExecutionId: null,
        status: "active",
        transcript: null,
        chatPodName: "chat-pod-1",
        startedAt: "2024-01-01T00:00:00Z",
        completedAt: null,
        createdAt: "2024-01-01T00:00:00Z",
      },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useLiveChatSession>);
    vi.mocked(useLiveChatMessages).mockReturnValue({
      messages: [
        { id: "1", role: "user", content: "Hello", timestamp: 1 },
        { id: "2", role: "assistant", content: "Hi there", timestamp: 2 },
      ],
      addMessage: vi.fn(),
      clearMessages: vi.fn(),
    });

    const user = userEvent.setup();
    renderWithProviders(<LiveChatPanel {...defaultProps} />);
    await user.click(screen.getByTestId("live-chat-close"));

    expect(mockCompleteMutate).toHaveBeenCalledWith({
      nodeExecId: "exec-1",
      transcript: "**Human:** Hello\n\n**AI:** Hi there",
    });
  });

  it("does not show input area or chat panel when session is completed", async () => {
    const { useLiveChatSession } = await import("@/hooks/useLiveChat");
    vi.mocked(useLiveChatSession).mockReturnValue({
      data: {
        id: "session-1",
        nodeExecutionId: "exec-1",
        workflowRunId: "run-1",
        sourceNodeExecutionId: null,
        status: "completed",
        transcript: "Human: done\nAI: ok",
        chatPodName: "chat-pod-1",
        startedAt: "2024-01-01T00:00:00Z",
        completedAt: "2024-01-01T00:01:00Z",
        createdAt: "2024-01-01T00:00:00Z",
      },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useLiveChatSession>);

    renderWithProviders(<LiveChatPanel {...defaultProps} />);
    // Completed sessions render the "start" block, not the active chat panel
    expect(screen.queryByTestId("live-chat-input")).not.toBeInTheDocument();
    expect(screen.queryByTestId("live-chat-send")).not.toBeInTheDocument();
    expect(screen.queryByTestId("live-chat-close")).not.toBeInTheDocument();
    expect(screen.queryByTestId("live-chat-panel")).not.toBeInTheDocument();
    // Should show start button instead
    expect(screen.getByTestId("start-live-chat-button")).toBeInTheDocument();
  });

  it("renders multiple messages in correct order", async () => {
    const { useLiveChatSession, useLiveChatMessages } = await import("@/hooks/useLiveChat");
    vi.mocked(useLiveChatSession).mockReturnValue({
      data: {
        id: "session-1",
        nodeExecutionId: "exec-1",
        workflowRunId: "run-1",
        sourceNodeExecutionId: null,
        status: "active",
        transcript: null,
        chatPodName: "chat-pod-1",
        startedAt: "2024-01-01T00:00:00Z",
        completedAt: null,
        createdAt: "2024-01-01T00:00:00Z",
      },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useLiveChatSession>);
    vi.mocked(useLiveChatMessages).mockReturnValue({
      messages: [
        { id: "1", role: "user", content: "First message", timestamp: 1 },
        { id: "2", role: "assistant", content: "Second message", timestamp: 2 },
        { id: "3", role: "user", content: "Third message", timestamp: 3 },
      ],
      addMessage: vi.fn(),
      clearMessages: vi.fn(),
    });

    renderWithProviders(<LiveChatPanel {...defaultProps} />);
    expect(screen.getByText("First message")).toBeInTheDocument();
    expect(screen.getByText("Second message")).toBeInTheDocument();
    expect(screen.getByText("Third message")).toBeInTheDocument();
  });

  it("shows user messages with different styling than assistant messages", async () => {
    const { useLiveChatSession, useLiveChatMessages } = await import("@/hooks/useLiveChat");
    vi.mocked(useLiveChatSession).mockReturnValue({
      data: {
        id: "session-1",
        nodeExecutionId: "exec-1",
        workflowRunId: "run-1",
        sourceNodeExecutionId: null,
        status: "active",
        transcript: null,
        chatPodName: "chat-pod-1",
        startedAt: "2024-01-01T00:00:00Z",
        completedAt: null,
        createdAt: "2024-01-01T00:00:00Z",
      },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useLiveChatSession>);
    vi.mocked(useLiveChatMessages).mockReturnValue({
      messages: [
        { id: "1", role: "user", content: "User msg", timestamp: 1 },
        { id: "2", role: "assistant", content: "Assistant msg", timestamp: 2 },
      ],
      addMessage: vi.fn(),
      clearMessages: vi.fn(),
    });

    renderWithProviders(<LiveChatPanel {...defaultProps} />);
    const userMsg = screen.getByText("User msg").closest("[class*='mb-3 flex']");
    const assistantMsg = screen.getByText("Assistant msg").closest("[class*='mb-3 flex']");
    expect(userMsg?.className).toContain("justify-end");
    expect(assistantMsg?.className).toContain("justify-start");
  });

  it("send button is disabled when input is empty", async () => {
    const { useLiveChatSession } = await import("@/hooks/useLiveChat");
    vi.mocked(useLiveChatSession).mockReturnValue({
      data: {
        id: "session-1",
        nodeExecutionId: "exec-1",
        workflowRunId: "run-1",
        sourceNodeExecutionId: null,
        status: "active",
        transcript: null,
        chatPodName: "chat-pod-1",
        startedAt: "2024-01-01T00:00:00Z",
        completedAt: null,
        createdAt: "2024-01-01T00:00:00Z",
      },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useLiveChatSession>);

    renderWithProviders(<LiveChatPanel {...defaultProps} />);
    expect(screen.getByTestId("live-chat-send")).toBeDisabled();
  });

  it("enter key sends message", async () => {
    const mockAddMessage = vi.fn();
    const { useLiveChatSession, useLiveChatMessages } = await import("@/hooks/useLiveChat");
    vi.mocked(useLiveChatSession).mockReturnValue({
      data: {
        id: "session-1",
        nodeExecutionId: "exec-1",
        workflowRunId: "run-1",
        sourceNodeExecutionId: null,
        status: "active",
        transcript: null,
        chatPodName: "chat-pod-1",
        startedAt: "2024-01-01T00:00:00Z",
        completedAt: null,
        createdAt: "2024-01-01T00:00:00Z",
      },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useLiveChatSession>);
    vi.mocked(useLiveChatMessages).mockReturnValue({
      messages: [],
      addMessage: mockAddMessage,
      clearMessages: vi.fn(),
    });

    const user = userEvent.setup();
    renderWithProviders(<LiveChatPanel {...defaultProps} />);

    const input = screen.getByTestId("live-chat-input");
    await user.type(input, "Hello via enter");
    await user.keyboard("{Enter}");

    expect(mockSendMessageMutate).toHaveBeenCalledWith("Hello via enter");
    expect(mockAddMessage).toHaveBeenCalledWith("user", "Hello via enter");
  });

  it("shift+enter does not send message", async () => {
    const mockAddMessage = vi.fn();
    const { useLiveChatSession, useLiveChatMessages } = await import("@/hooks/useLiveChat");
    vi.mocked(useLiveChatSession).mockReturnValue({
      data: {
        id: "session-1",
        nodeExecutionId: "exec-1",
        workflowRunId: "run-1",
        sourceNodeExecutionId: null,
        status: "active",
        transcript: null,
        chatPodName: "chat-pod-1",
        startedAt: "2024-01-01T00:00:00Z",
        completedAt: null,
        createdAt: "2024-01-01T00:00:00Z",
      },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useLiveChatSession>);
    vi.mocked(useLiveChatMessages).mockReturnValue({
      messages: [],
      addMessage: mockAddMessage,
      clearMessages: vi.fn(),
    });

    const user = userEvent.setup();
    renderWithProviders(<LiveChatPanel {...defaultProps} />);

    const input = screen.getByTestId("live-chat-input");
    await user.type(input, "Hello");
    await user.keyboard("{Shift>}{Enter}{/Shift}");

    expect(mockSendMessageMutate).not.toHaveBeenCalled();
  });
});
