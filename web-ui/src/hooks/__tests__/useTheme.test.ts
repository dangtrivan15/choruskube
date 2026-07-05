import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useTheme } from "@/hooks/useTheme";

function setCookieForTest(name: string, value: string) {
  document.cookie = `${name}=${value};path=/`;
}

function clearCookie(name: string) {
  document.cookie = `${name}=;path=/;max-age=0`;
}

describe("useTheme", () => {
  beforeEach(() => {
    clearCookie("theme");
    document.documentElement.classList.remove("dark");
  });

  it("defaults to light theme when no cookie exists", () => {
    const { result } = renderHook(() => useTheme());
    expect(result.current.theme).toBe("light");
  });

  it("reads theme=dark cookie", () => {
    setCookieForTest("theme", "dark");
    const { result } = renderHook(() => useTheme());
    expect(result.current.theme).toBe("dark");
  });

  it("reads theme=light cookie", () => {
    setCookieForTest("theme", "light");
    const { result } = renderHook(() => useTheme());
    expect(result.current.theme).toBe("light");
  });

  it("ignores invalid cookie value and defaults to light", () => {
    setCookieForTest("theme", "ocean");
    const { result } = renderHook(() => useTheme());
    expect(result.current.theme).toBe("light");
  });

  it("toggles from light to dark", () => {
    const { result } = renderHook(() => useTheme());
    expect(result.current.theme).toBe("light");

    act(() => {
      result.current.toggle();
    });

    expect(result.current.theme).toBe("dark");
  });

  it("toggles from dark to light", () => {
    setCookieForTest("theme", "dark");
    const { result } = renderHook(() => useTheme());
    expect(result.current.theme).toBe("dark");

    act(() => {
      result.current.toggle();
    });

    expect(result.current.theme).toBe("light");
  });

  it("persists theme to cookie on change", () => {
    const { result } = renderHook(() => useTheme());

    act(() => {
      result.current.toggle();
    });

    expect(document.cookie).toContain("theme=dark");
  });

  it("adds .dark class to documentElement when theme is dark", () => {
    setCookieForTest("theme", "dark");
    renderHook(() => useTheme());
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });

  it("removes .dark class from documentElement when theme is light", () => {
    document.documentElement.classList.add("dark");
    setCookieForTest("theme", "light");
    renderHook(() => useTheme());
    expect(document.documentElement.classList.contains("dark")).toBe(false);
  });
});
