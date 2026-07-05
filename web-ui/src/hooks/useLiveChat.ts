import { useCallback, useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import { useStompSubscription } from "./useStompSubscription";
import type {
  LiveChatSessionResponse,
  ChatMessage,
  LiveChatMessageEvent,
  LiveChatMessageResponse,
} from "@/lib/types";

export function useLiveChatSession(runId: string, nodeExecId: string | null) {
  return useQuery({
    queryKey: ["live-chat", runId, nodeExecId],
    queryFn: () =>
      api.get<LiveChatSessionResponse>(`/runs/${runId}/nodes/${nodeExecId}/live-chat`),
    enabled: !!nodeExecId,
    retry: false,
  });
}

export function useStartLiveChat(runId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (nodeExecId: string) =>
      api.post<LiveChatSessionResponse>(`/runs/${runId}/nodes/${nodeExecId}/live-chat`, {}),
    onSuccess: (_data, nodeExecId) => {
      queryClient.invalidateQueries({ queryKey: ["live-chat", runId, nodeExecId] });
      queryClient.invalidateQueries({ queryKey: ["runs", runId] });
      queryClient.invalidateQueries({ queryKey: ["pending-gates"] });
      addEntry(showMutationToast("Live chat started", "info"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to start live chat", "error"));
    },
  });
}

export function useSendLiveChatMessage(runId: string, nodeExecId: string) {
  return useMutation({
    mutationFn: (content: string) =>
      api.post<unknown>(`/runs/${runId}/nodes/${nodeExecId}/live-chat/messages`, { content }),
  });
}

export function useCompleteLiveChat(runId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: ({
      nodeExecId,
      transcript,
    }: {
      nodeExecId: string;
      transcript: string;
    }) =>
      api.post<LiveChatSessionResponse>(
        `/runs/${runId}/nodes/${nodeExecId}/live-chat/complete`,
        { transcript }
      ),
    onSuccess: (_data, vars) => {
      queryClient.invalidateQueries({ queryKey: ["live-chat", runId, vars.nodeExecId] });
      queryClient.invalidateQueries({ queryKey: ["runs", runId] });
      queryClient.invalidateQueries({ queryKey: ["pending-gates"] });
      addEntry(showMutationToast("Live chat ended", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to complete live chat", "error"));
    },
  });
}

/**
 * Subscribe to live chat messages via STOMP for a specific session.
 * Returns streaming messages as they arrive.
 *
 * User messages are added optimistically via `addMessage` from the UI.
 * The STOMP subscription only processes assistant messages to avoid duplicates,
 * since user messages are already displayed when sent.
 */
export function useLiveChatMessages(runId: string, sessionId: string | null) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const initialLoadedRef = useRef<string | null>(null);

  const addMessage = useCallback((role: "user" | "assistant", content: string) => {
    setMessages((prev) => [
      ...prev,
      {
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        role,
        content,
        timestamp: Date.now(),
      },
    ]);
  }, []);

  // Load existing messages when session becomes available (e.g. after browser refresh)
  useEffect(() => {
    if (!sessionId || initialLoadedRef.current === sessionId) return;
    initialLoadedRef.current = sessionId;

    api
      .get<LiveChatMessageResponse[]>(
        `/runs/${runId}/live-chat-sessions/${sessionId}/messages`
      )
      .then((msgs) => {
        if (msgs.length > 0) {
          const httpMessages: ChatMessage[] = msgs.map((m) => ({
            id: m.id,
            role: m.role,
            content: m.content,
            timestamp: new Date(m.createdAt).getTime(),
          }));
          // Merge with any STOMP messages that arrived during the HTTP round-trip
          // to avoid dropping real-time messages
          setMessages((prev) => {
            const httpIds = new Set(httpMessages.map((m) => m.id));
            const stompOnly = prev.filter((m) => !httpIds.has(m.id));
            return [...httpMessages, ...stompOnly];
          });
        }
      })
      .catch(() => {
        // Don't block on error — the STOMP subscription will still work
      });
  }, [sessionId, runId]);

  // Reset when sessionId changes to a different session
  useEffect(() => {
    if (!sessionId) {
      setMessages([]);
      initialLoadedRef.current = null;
    }
  }, [sessionId]);

  useStompSubscription(
    sessionId ? `/topic/live-chat/${sessionId}` : null,
    (message) => {
      try {
        const event = JSON.parse(message.body) as LiveChatMessageEvent;
        // Only process assistant messages from STOMP — user messages
        // are already displayed optimistically when sent from the UI
        if (event.type === "live_chat_message" && event.role === "assistant") {
          addMessage(event.role, event.content);
        }
      } catch {
        // ignore malformed messages
      }
    },
  );

  const clearMessages = useCallback(() => setMessages([]), []);

  return { messages, addMessage, clearMessages };
}
