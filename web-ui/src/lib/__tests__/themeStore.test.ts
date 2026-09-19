import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { themeStore } from "@/lib/themeStore";

function setCookieForTest(name: string, value: string) {
  document.cookie = `${name}=${value};path=/`;
}

function clearCookie(name: string) {
  document.cookie = `${name}=;path=/;max-age=0`;
}

function stubMatchMedia(matches: boolean, addEventListener = vi.fn()) {
  vi.stubGlobal(
    "matchMedia",
    vi.fn().mockReturnValue({
      matches,
      media: "(prefers-color-scheme: dark)",
      addEventListener,
    }),
  );
}

describe("themeStore", () => {
  let unsubscribe: (() => void) | null = null;

  beforeEach(() => {
    clearCookie("theme");
    document.documentElement.classList.remove("dark");
  });

  afterEach(() => {
    unsubscribe?.();
    unsubscribe = null;
    vi.unstubAllGlobals();
  });

  it("init reads a dark cookie and applies .dark", () => {
    setCookieForTest("theme", "dark");
    unsubscribe = themeStore.subscribe(() => {});
    expect(themeStore.getSnapshot()).toBe("dark");
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });

  it("init reads a light cookie and removes .dark", () => {
    document.documentElement.classList.add("dark");
    setCookieForTest("theme", "light");
    unsubscribe = themeStore.subscribe(() => {});
    expect(themeStore.getSnapshot()).toBe("light");
    expect(document.documentElement.classList.contains("dark")).toBe(false);
  });

  it("init with no cookie resolves from the OS and seed-writes the cookie", () => {
    stubMatchMedia(true);
    unsubscribe = themeStore.subscribe(() => {});
    expect(themeStore.getSnapshot()).toBe("dark");
    expect(document.cookie).toContain("theme=dark");
  });

  it("toggle flips the theme, writes the cookie, and toggles .dark", () => {
    setCookieForTest("theme", "light");
    unsubscribe = themeStore.subscribe(() => {});

    themeStore.toggle();

    expect(themeStore.getSnapshot()).toBe("dark");
    expect(document.cookie).toContain("theme=dark");
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });

  it("a BroadcastChannel message from another tab updates the snapshot and class", async () => {
    setCookieForTest("theme", "light");
    const listener = vi.fn();
    unsubscribe = themeStore.subscribe(listener);
    expect(themeStore.getSnapshot()).toBe("light");

    const otherTab = new BroadcastChannel("theme");
    otherTab.postMessage("dark");
    try {
      await vi.waitFor(() => {
        expect(themeStore.getSnapshot()).toBe("dark");
      });
      expect(document.documentElement.classList.contains("dark")).toBe(true);
      expect(listener).toHaveBeenCalled();
    } finally {
      otherTab.close();
    }
  });

  it("toggle broadcasts the new theme so other same-origin tabs receive it", async () => {
    setCookieForTest("theme", "light");
    unsubscribe = themeStore.subscribe(() => {});

    const otherTab = new BroadcastChannel("theme");
    const received: unknown[] = [];
    otherTab.onmessage = (event) => received.push(event.data);
    try {
      themeStore.toggle();
      await vi.waitFor(() => {
        expect(received).toContain("dark");
      });
    } finally {
      otherTab.close();
    }
  });

  it("ignores a BroadcastChannel message that is not a valid theme", async () => {
    setCookieForTest("theme", "light");
    const listener = vi.fn();
    unsubscribe = themeStore.subscribe(listener);
    expect(themeStore.getSnapshot()).toBe("light");

    const otherTab = new BroadcastChannel("theme");
    try {
      otherTab.postMessage("ocean");
      // Give any (mis)handling a full macrotask to run before asserting nothing changed.
      await new Promise((resolve) => setTimeout(resolve, 0));
      expect(themeStore.getSnapshot()).toBe("light");
      expect(listener).not.toHaveBeenCalled();
    } finally {
      otherTab.close();
    }
  });

  it("a visibilitychange after the cookie is changed externally re-reads and applies it", () => {
    setCookieForTest("theme", "light");
    unsubscribe = themeStore.subscribe(() => {});
    expect(themeStore.getSnapshot()).toBe("light");

    setCookieForTest("theme", "dark");
    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      value: "visible",
    });
    document.dispatchEvent(new Event("visibilitychange"));

    expect(themeStore.getSnapshot()).toBe("dark");
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });

  it("does not register a prefers-color-scheme change listener (no OS live-tracking)", () => {
    const addEventListener = vi.fn();
    stubMatchMedia(false, addEventListener);
    unsubscribe = themeStore.subscribe(() => {});

    themeStore.toggle();

    expect(addEventListener).not.toHaveBeenCalled();
  });

  it("a blocked document.cookie getter does not throw and yields a consistent snapshot", () => {
    Object.defineProperty(document, "cookie", {
      configurable: true,
      get() {
        throw new Error("blocked");
      },
      set() {
        /* no-op */
      },
    });
    try {
      expect(() => {
        unsubscribe = themeStore.subscribe(() => {});
      }).not.toThrow();
      expect(themeStore.getSnapshot()).toBe("light");
    } finally {
      delete (document as unknown as Record<string, unknown>).cookie;
    }
  });
});
