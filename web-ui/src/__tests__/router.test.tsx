import { describe, it, expect } from "vitest";
import type { ReactNode } from "react";
import type { RouteObject } from "react-router";
import { buildRouter } from "@/router";

function childPaths(): string[] {
  const router = buildRouter([
    { path: "admin/organizations", element: null },
    { path: "organizations/:id", element: null },
  ]);
  const layout = router.routes[0];
  return (layout.children ?? []).map((c: RouteObject) => c.path ?? (c.index ? "(index)" : "?"));
}

describe("buildRouter", () => {
  it("appends injected routes and keeps the catch-all last", () => {
    const paths = childPaths();
    expect(paths).toContain("admin/organizations");
    expect(paths).toContain("organizations/:id");
    // Core never names extension routes itself — they only appear when injected.
    expect(paths[paths.length - 1]).toBe("*");
  });

  it("includes only core routes when nothing is injected", () => {
    const router = buildRouter();
    const paths = (router.routes[0].children ?? []).map((c: RouteObject) => c.path);
    expect(paths).toContain("runs");
    expect(paths).not.toContain("admin/organizations");
    expect(paths).not.toContain("organizations/:id");
  });

  it("resolves the Roadmap Graph View route to RoadmapGraphPage", async () => {
    const RoadmapGraphPage = (await import("@/pages/RoadmapGraphPage")).default;
    const router = buildRouter();
    const route = (router.routes[0].children ?? []).find(
      (c: RouteObject) => c.path === "roadmap/epics/:epicId/graph",
    ) as { path?: string; element?: ReactNode } | undefined;
    expect(route).toBeDefined();
    expect(route?.element).toEqual(<RoadmapGraphPage />);
  });
});
