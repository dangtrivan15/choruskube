import { useState, useRef, useEffect } from "react";
import {
  MessageCircle,
  Send,
  Loader2,
  X,
  Bot,
  User,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import {
  useStartLiveChat,
  useCompleteLiveChat,
  useSendLiveChatMessage,
  useLiveChatMessages,
  useLiveChatSession,
} from "@/hooks/useLiveChat";
import type { ChatMessage } from "@/lib/types";

interface LiveChatPanelProps {
  runId: string;
  nodeExecId: string;
  nodeLabel: string;
}

export default function LiveChatPanel({
  runId,
  nodeExecId,
}: LiveChatPanelProps) {
  const { data: session, isLoading: sessionLoading } = useLiveChatSession(runId, nodeExecId);
  const startChat = useStartLiveChat(runId);
  const completeChat = useCompleteLiveChat(runId);
  const sendMessage = useSendLiveChatMessage(runId, nodeExecId);
  const { messages, addMessage } = useLiveChatMessages(
    runId,
    session?.status === "active" || session?.status === "pending" ? session.id : null
  );
  const [input, setInput] = useState("");
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    if (typeof messagesEndRef.current?.scrollIntoView === "function") {
      messagesEndRef.current.scrollIntoView({ behavior: "smooth" });
    }
  }, [messages]);

  const isActive = session?.status === "active" || session?.status === "pending";
  const isCompleted = session?.status === "completed";

  function handleSendMessage() {
    if (!input.trim()) return;
    const content = input.trim();
    // Optimistic local display
    addMessage("user", content);
    // Send to API server for persistence and relay to chat pod
    sendMessage.mutate(content);
    setInput("");
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  }

  function handleCloseLiveChat() {
    const transcript = messages
      .map((m) => `**${m.role === "user" ? "Human" : "AI"}:** ${m.content}`)
      .join("\n\n");

    completeChat.mutate({
      nodeExecId,
      transcript,
    });
  }

  // Show start button if no active session (either no session at all, or completed)
  if ((!session || session.status === "completed") && !sessionLoading) {
    return (
      <div className="space-y-3">
        <Separator />
        {session?.status === "completed" && session.transcript && (
          <div className="space-y-1.5">
            <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              Previous Chat Transcript
            </h4>
            <MarkdownViewer content={session.transcript} maxHeight="max-h-48" />
          </div>
        )}
        <Button
          data-testid="start-live-chat-button"
          variant="outline"
          className="w-full"
          onClick={() => startChat.mutate(nodeExecId)}
          disabled={startChat.isPending}
        >
          {startChat.isPending ? (
            <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
          ) : (
            <MessageCircle className="mr-1.5 h-4 w-4" />
          )}
          Start Live Chat
        </Button>
      </div>
    );
  }

  if (sessionLoading) {
    return (
      <div className="flex items-center justify-center py-8">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div data-testid="live-chat-panel" className="flex flex-col space-y-3">
      {/* Session header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <MessageCircle className="h-4 w-4 text-status-accent" />
          <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Live Chat
          </span>
        </div>
        <Badge
          data-testid="live-chat-status"
          className={
            isActive
              ? "bg-status-accent/15 text-status-accent"
              : isCompleted
                ? "bg-status-success/15 text-status-success"
                : "bg-muted text-muted-foreground"
          }
        >
          {session?.status}
        </Badge>
      </div>

      <Separator />

      {/* Messages area */}
      <div className="max-h-[400px] min-h-[200px] overflow-y-auto rounded-md border bg-muted/20 p-3">
        {messages.length === 0 && (
          <p className="text-center text-xs italic text-muted-foreground py-8">
            {isActive
              ? "Chat session is starting. Messages will appear here..."
              : "No messages yet."}
          </p>
        )}
        {messages.map((msg) => (
          <ChatBubble key={msg.id} message={msg} />
        ))}
        <div ref={messagesEndRef} />
      </div>

      {/* Input area (only when active) */}
      {isActive && (
        <div className="flex gap-2">
          <Textarea
            data-testid="live-chat-input"
            placeholder="Type a message..."
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            className="min-h-[60px] resize-none"
          />
          <Button
            data-testid="live-chat-send"
            size="icon"
            onClick={handleSendMessage}
            disabled={!input.trim()}
            className="shrink-0"
          >
            <Send className="h-4 w-4" />
          </Button>
        </div>
      )}

      {/* Close Live Chat button (only when active) */}
      {isActive && (
        <Button
          data-testid="live-chat-close"
          variant="outline"
          className="w-full"
          disabled={completeChat.isPending}
          onClick={handleCloseLiveChat}
        >
          {completeChat.isPending ? (
            <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
          ) : (
            <X className="mr-1.5 h-4 w-4" />
          )}
          Close Live Chat
        </Button>
      )}

      {/* Completed sessions are now handled by the "start" block above */}
    </div>
  );
}

function ChatBubble({ message }: { message: ChatMessage }) {
  const isUser = message.role === "user";
  return (
    <div
      className={`mb-3 flex gap-2 ${isUser ? "justify-end" : "justify-start"}`}
    >
      {!isUser && (
        <div className="mt-0.5 shrink-0 rounded-full bg-status-accent/15 p-1.5">
          <Bot className="h-3 w-3 text-status-accent" />
        </div>
      )}
      <div
        className={`max-w-[80%] rounded-lg px-3 py-2 text-sm ${
          isUser
            ? "bg-primary text-primary-foreground"
            : "bg-muted"
        }`}
      >
        <MarkdownViewer content={message.content} />
      </div>
      {isUser && (
        <div className="mt-0.5 shrink-0 rounded-full bg-status-info/15 p-1.5">
          <User className="h-3 w-3 text-status-info" />
        </div>
      )}
    </div>
  );
}
