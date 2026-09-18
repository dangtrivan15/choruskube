import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
  getCookie,
  setCookie,
  systemPrefersDark,
  resolveInitialTheme,
} from "@/lib/theme";

function clearCookie(name: string) {
  document.cookie = `${name}=;path=/;max-age=0`;
}

function stubMatchMedia(matches: boolean) {
  vi.stubGlobal(
    "matchMedia",
    vi.fn().mockReturnValue({ matches, media: "(prefers-color-scheme: dark)" }),
  );
}

describe("getCookie", () => {
  beforeEach(() => {
    clearCookie("theme");
    clearCookie("test-cookie");
  });

  it("returns value for existing cookie", () => {
    document.cookie = "test-cookie=hello;path=/";
    expect(getCookie("test-cookie")).toBe("hello");
  });

  it("returns null for missing cookie", () => {
    expect(getCookie("nonexistent")).toBeNull();
  });

  it("handles encoded values", () => {
    document.cookie = "test-cookie=hello%20world;path=/";
    expect(getCookie("test-cookie")).toBe("hello world");
  });
});

describe("setCookie", () => {
  beforeEach(() => {
    clearCookie("test-cookie");
  });

  it("writes cookie string", () => {
    setCookie("test-cookie", "myvalue");
    expect(document.cookie).toContain("test-cookie=myvalue");
  });
});

describe("systemPrefersDark", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("returns true when the OS query matches", () => {
    stubMatchMedia(true);
    expect(systemPrefersDark()).toBe(true);
  });

  it("returns false when the OS query does not match", () => {
    stubMatchMedia(false);
    expect(systemPrefersDark()).toBe(false);
  });

  it("returns false when matchMedia is unavailable", () => {
    vi.stubGlobal("matchMedia", undefined);
    expect(systemPrefersDark()).toBe(false);
  });

  it("returns false when matchMedia throws", () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn().mockImplementation(() => {
        throw new Error("blocked");
      }),
    );
    expect(systemPrefersDark()).toBe(false);
  });
});

describe("resolveInitialTheme", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("resolves to dark when the OS prefers dark", () => {
    stubMatchMedia(true);
    expect(resolveInitialTheme()).toBe("dark");
  });

  it("resolves to light when the OS prefers light", () => {
    stubMatchMedia(false);
    expect(resolveInitialTheme()).toBe("light");
  });

  it("falls back to light when matchMedia is unavailable", () => {
    vi.stubGlobal("matchMedia", undefined);
    expect(resolveInitialTheme()).toBe("light");
  });
});
