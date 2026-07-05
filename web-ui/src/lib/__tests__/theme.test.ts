import { describe, it, expect, beforeEach } from "vitest";
import { getCookie, setCookie } from "@/lib/theme";

function clearCookie(name: string) {
  document.cookie = `${name}=;path=/;max-age=0`;
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
