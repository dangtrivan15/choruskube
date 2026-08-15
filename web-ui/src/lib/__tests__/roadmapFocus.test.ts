import { describe, it, expect } from "vitest";
import {
  buildFocusedUrl,
  parseFocusParams,
  clampFocusToStory,
  focusToSearchParamsInit,
} from "../roadmapFocus";
import type { EpicResponse, StoryResponse, TaskResponse } from "../types";

function makeEpic(overrides: Partial<EpicResponse> = {}): EpicResponse {
  return {
    id: "epic-1",
    title: "Add dark mode",
    description: "desc",
    motivation: null,
    status: "in_progress",
    stage: "in_progress",
    priority: "medium",
    targetDate: null,
    progress: { totalTasks: 1, doneTasks: 0 },
    softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
    repos: [],
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    readyItemCount: 0,
    milestone: null,
    ...overrides,
  };
}

function makeStory(overrides: Partial<StoryResponse> = {}): StoryResponse {
  return {
    id: "story-1",
    epicId: "epic-1",
    title: "Dark theme toggle",
    description: "desc",
    status: "in_progress",
    stage: "backlog",
    priority: "medium",
    targetDate: null,
    readiness: null,
    progress: { totalTasks: 1, doneTasks: 0 },
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    ...overrides,
  };
}

function makeTask(overrides: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: "task-1",
    storyId: "story-1",
    title: "Wire up the toggle",
    description: "desc",
    status: "backlog",
    softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
    repos: [],
    latestRunId: null,
    latestRunStatus: null,
    readiness: null,
    recentRuns: [],
    totalRunCount: 0,
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    ...overrides,
  };
}

describe("buildFocusedUrl", () => {
  it("returns the epic-scoped graph path when epicId is set", () => {
    expect(buildFocusedUrl("graph", { epicId: "epic-1" })).toBe("/roadmap/epics/epic-1/graph");
  });

  it("returns null for graph when there's no focused epic", () => {
    expect(buildFocusedUrl("graph", {})).toBeNull();
  });

  it("includes both query params for board/timeline when epicId and storyId are set", () => {
    expect(buildFocusedUrl("board", { epicId: "epic-1", storyId: "story-1" })).toBe(
      "/roadmap/board?epic=epic-1&story=story-1",
    );
    expect(buildFocusedUrl("timeline", { epicId: "epic-1", storyId: "story-1" })).toBe(
      "/roadmap/timeline?epic=epic-1&story=story-1",
    );
  });

  it("includes just the epic param for board/timeline when only epicId is set", () => {
    expect(buildFocusedUrl("board", { epicId: "epic-1" })).toBe("/roadmap/board?epic=epic-1");
    expect(buildFocusedUrl("timeline", { epicId: "epic-1" })).toBe("/roadmap/timeline?epic=epic-1");
  });

  it("returns the bare path for board/timeline when nothing is focused", () => {
    expect(buildFocusedUrl("board", {})).toBe("/roadmap/board");
    expect(buildFocusedUrl("timeline", {})).toBe("/roadmap/timeline");
  });
});

describe("parseFocusParams", () => {
  it("round-trips values produced by buildFocusedUrl", () => {
    const url = buildFocusedUrl("board", { epicId: "epic-1", storyId: "story-1" })!;
    const [, query] = url.split("?");
    expect(parseFocusParams(new URLSearchParams(query))).toEqual({
      epicId: "epic-1",
      storyId: "story-1",
    });
  });

  it("returns {} for an empty query string rather than throwing", () => {
    expect(parseFocusParams(new URLSearchParams(""))).toEqual({});
  });

  it("returns {} for a garbage query string rather than throwing", () => {
    expect(() => parseFocusParams(new URLSearchParams("???not=a=real&query"))).not.toThrow();
    expect(parseFocusParams(new URLSearchParams("???not=a=real&query"))).toEqual({});
  });
});

describe("clampFocusToStory", () => {
  it("maps a task detail to its parent storyId", () => {
    expect(clampFocusToStory({ itemType: "task", item: makeTask({ storyId: "story-42" }) })).toEqual({
      storyId: "story-42",
    });
  });

  it("maps a story detail to its own id", () => {
    expect(clampFocusToStory({ itemType: "story", item: makeStory({ id: "story-7" }) })).toEqual({
      storyId: "story-7",
    });
  });

  it("maps an epic detail to {}", () => {
    expect(clampFocusToStory({ itemType: "epic", item: makeEpic() })).toEqual({});
  });

  it("returns {} for null without throwing", () => {
    expect(() => clampFocusToStory(null)).not.toThrow();
    expect(clampFocusToStory(null)).toEqual({});
  });

  it("returns {} for undefined without throwing", () => {
    expect(() => clampFocusToStory(undefined)).not.toThrow();
    expect(clampFocusToStory(undefined)).toEqual({});
  });
});

describe("focusToSearchParamsInit", () => {
  it("returns only an epic key when there's no storyId — no literal story: undefined entry", () => {
    const init = focusToSearchParamsInit({ epicId: "epic-1" });
    expect(init).toEqual({ epic: "epic-1" });
    expect("story" in init).toBe(false);
  });

  it("returns both keys when epicId and storyId are set", () => {
    expect(focusToSearchParamsInit({ epicId: "epic-1", storyId: "story-1" })).toEqual({
      epic: "epic-1",
      story: "story-1",
    });
  });

  it("returns {} when nothing is focused", () => {
    expect(focusToSearchParamsInit({})).toEqual({});
  });
});
