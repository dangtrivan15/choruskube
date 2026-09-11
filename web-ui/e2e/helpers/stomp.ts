import type { Page } from "@playwright/test";

// Arm this BEFORE the navigation that mounts a roadmap view, then await it before driving an
// out-of-band mutation whose broadcast the page must observe live. It resolves once the page has
// sent a STOMP SUBSCRIBE for the roadmap-items feed over its /ws socket. Calling it (without
// awaiting) registers the websocket listener synchronously, so the arm cannot miss the socket.
//
// Without the barrier the live-update specs race: a roadmap view paints from its REST query on
// load, but the STOMP client (src/lib/stomp.ts) sends SUBSCRIBE only from onConnect, after the
// socket opens and the CONNECTED handshake completes. A mutation whose broadcast reaches the
// broker before that SUBSCRIBE is lost for good — the views never reload — so the assertion times
// out. Matching the "roadmap-items" destination substring keeps this robust to any topic prefix.
export async function waitForRoadmapSubscription(page: Page): Promise<void> {
  const socket = await page.waitForEvent("websocket", {
    predicate: (ws) => ws.url().includes("/ws"),
    timeout: 30_000,
  });
  await socket.waitForEvent("framesent", {
    predicate: (frame) =>
      typeof frame.payload === "string" &&
      frame.payload.startsWith("SUBSCRIBE") &&
      frame.payload.includes("roadmap-items"),
    timeout: 30_000,
  });
}
