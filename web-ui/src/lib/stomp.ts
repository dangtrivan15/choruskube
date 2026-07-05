import { Client } from "@stomp/stompjs";
import type { IMessage, StompSubscription } from "@stomp/stompjs";
import { config } from "@/config";
import { getToken, refreshToken } from "@/lib/oidc";

// ---------------------------------------------------------------------------
// Shared, reference-counted STOMP client
//
// Instead of each hook creating its own Client (and its own WebSocket),
// all subscriptions go through a single connection.  The connection is
// activated when the first subscriber appears and deactivated when the
// last one leaves.
// ---------------------------------------------------------------------------

interface SubscriptionEntry {
  topic: string;
  callback: (msg: IMessage) => void;
  stompSub: StompSubscription | null;
}

let client: Client | null = null;
let connected = false;
const entries: SubscriptionEntry[] = [];

function getWsUrl(): string {
  const apiBase = config.apiBaseUrl;
  if (apiBase.startsWith("http")) {
    const url = new URL(apiBase);
    const protocol = url.protocol === "https:" ? "wss:" : "ws:";
    return `${protocol}//${url.host}/ws`;
  }
  // Dev mode — apiBaseUrl is relative (e.g. "/api/v1"), use current host
  return `${window.location.protocol === "https:" ? "wss:" : "ws:"}//${window.location.host}/ws`;
}

function buildAuthHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function ensureClient(): Client {
  if (!client) {
    client = new Client({
      brokerURL: getWsUrl(),
      reconnectDelay: 1000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      connectHeaders: buildAuthHeaders(),
      beforeConnect: async () => {
        await refreshToken(30).catch(() => {});
        client!.connectHeaders = buildAuthHeaders();
      },
      onConnect: () => {
        connected = true;
        // (Re-)subscribe every entry — previous STOMP subscriptions are
        // invalid after a reconnect because the server-side session is new.
        for (const entry of entries) {
          entry.stompSub = client!.subscribe(entry.topic, entry.callback);
        }
      },
      onWebSocketClose: () => {
        connected = false;
        for (const entry of entries) {
          entry.stompSub = null;
        }
      },
    });
  }
  return client;
}

/**
 * Subscribe to a STOMP topic on the shared client.
 * Returns an unsubscribe function — call it to remove the subscription.
 */
export function subscribe(
  topic: string,
  callback: (msg: IMessage) => void,
): () => void {
  const entry: SubscriptionEntry = { topic, callback, stompSub: null };
  entries.push(entry);

  const c = ensureClient();

  // First subscriber — activate the connection.
  if (entries.length === 1) {
    c.activate();
  }

  // Already connected — subscribe immediately.
  if (connected) {
    entry.stompSub = c.subscribe(topic, callback);
  }

  return () => {
    entry.stompSub?.unsubscribe();
    const idx = entries.indexOf(entry);
    if (idx !== -1) entries.splice(idx, 1);

    // Last subscriber gone — tear down the connection.
    if (entries.length === 0 && client) {
      client.deactivate();
      client = null;
      connected = false;
    }
  };
}
