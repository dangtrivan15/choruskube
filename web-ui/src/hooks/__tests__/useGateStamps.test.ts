import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";

vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return {
    ...actual,
    api: { get: vi.fn() },
  };
});

import { api } from "@/lib/api";
import { useGateStamps } from "@/hooks/useGateStamps";

const mockApi = api as unknown as { get: ReturnType<typeof vi.fn> };

describe("useGateStamps", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("fetches the run's gate stamps", async () => {
    const stamps = [{ id: "stamp-1", gateLabel: "final_approval", status: "pending" }];
    mockApi.get.mockResolvedValueOnce(stamps);
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useGateStamps("run-1"), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.get).toHaveBeenCalledWith("/runs/run-1/gate-stamps");
    expect(result.current.data).toEqual(stamps);
  });

  it("is refreshed by an invalidation of the run's query key", async () => {
    mockApi.get.mockResolvedValue([]);
    const { wrapper, queryClient } = createTestHookWrapper();

    const { result } = renderHook(() => useGateStamps("run-1"), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.get).toHaveBeenCalledTimes(1);

    await queryClient.invalidateQueries({ queryKey: ["runs", "run-1"] });

    await waitFor(() => expect(mockApi.get).toHaveBeenCalledTimes(2));
  });

  it("does not fetch without a run id", () => {
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useGateStamps(""), { wrapper });

    expect(result.current.fetchStatus).toBe("idle");
    expect(mockApi.get).not.toHaveBeenCalled();
  });
});
