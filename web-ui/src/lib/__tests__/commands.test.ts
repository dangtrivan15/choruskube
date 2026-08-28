import { describe, it, expect } from "vitest";
import {
  staticCommands,
  buildRunCommands,
  scoreCommand,
  searchCommands,
  groupCommands,
  type Command,
} from "@/lib/commands";
import type { RunSummary } from "@/lib/types";

// ---------------------------------------------------------------------------
// staticCommands
// ---------------------------------------------------------------------------

describe("staticCommands", () => {
  it("contains the expected number of commands", () => {
    expect(staticCommands.length).toBe(9);
  });

  it("has unique IDs", () => {
    const ids = staticCommands.map((c) => c.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("assigns categories correctly", () => {
    const navCmds = staticCommands.filter((c) => c.category === "navigation");
    const actionCmds = staticCommands.filter((c) => c.category === "actions");
    expect(navCmds.length).toBe(6);
    expect(actionCmds.length).toBe(3);
  });

  it("contains no extension commands — those are injected via AppExtensions", () => {
    expect(staticCommands.find((c) => c.id === "nav:my-organization")).toBeUndefined();
    expect(staticCommands.find((c) => c.id === "nav:admin-organizations")).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// buildRunCommands
// ---------------------------------------------------------------------------

describe("buildRunCommands", () => {
  const makeRun = (id: string, name: string | null = null): RunSummary => ({
    id,
    name,
    graphTemplateId: "tpl-1",
    templateName: "Test Template",
    status: "running",
    startedAt: null,
    completedAt: null,
    createdAt: "2025-01-01T00:00:00Z",
    autopilotId: null,
    softwareProject: null,
  });

  it("returns empty array for undefined input", () => {
    expect(buildRunCommands(undefined)).toEqual([]);
  });

  it("returns empty array for empty array", () => {
    expect(buildRunCommands([])).toEqual([]);
  });

  it("builds commands from runs", () => {
    const runs = [makeRun("abc-123", "My Run"), makeRun("def-456")];
    const cmds = buildRunCommands(runs);

    expect(cmds).toHaveLength(2);
    expect(cmds[0].id).toBe("run:abc-123");
    expect(cmds[0].label).toBe("My Run");
    expect(cmds[0].category).toBe("runs");
    expect(cmds[1].label).toBe("Run def-456");
  });

  it("caps at 50 commands", () => {
    const runs = Array.from({ length: 60 }, (_, i) =>
      makeRun(`run-${i}`, `Run ${i}`)
    );
    const cmds = buildRunCommands(runs);
    expect(cmds.length).toBe(50);
  });

  it("includes template name and status in keywords", () => {
    const runs = [makeRun("abc-123", "My Run")];
    const cmds = buildRunCommands(runs);
    expect(cmds[0].keywords).toContain("Test Template");
    expect(cmds[0].keywords).toContain("running");
  });
});

// ---------------------------------------------------------------------------
// scoreCommand
// ---------------------------------------------------------------------------

describe("scoreCommand", () => {
  const cmd: Command = {
    id: "test",
    label: "Go to Runs",
    category: "navigation",
    keywords: ["workflow", "pipeline"],
  };

  it("returns 1 for empty query (everything matches)", () => {
    expect(scoreCommand(cmd, "")).toBe(1);
  });

  it("scores 100 for exact prefix match", () => {
    expect(scoreCommand(cmd, "Go to")).toBe(100);
  });

  it("scores 80 for contains match", () => {
    expect(scoreCommand(cmd, "Runs")).toBe(80);
  });

  it("scores 70 for word-initial match", () => {
    expect(scoreCommand(cmd, "gtr")).toBe(70);
  });

  it("scores 50 for keyword match", () => {
    expect(scoreCommand(cmd, "pipe")).toBe(50);
  });

  it("scores 30 for subsequence match", () => {
    expect(scoreCommand(cmd, "gorns")).toBe(30);
  });

  it("scores 0 for no match", () => {
    expect(scoreCommand(cmd, "zzzzz")).toBe(0);
  });
});

// ---------------------------------------------------------------------------
// searchCommands
// ---------------------------------------------------------------------------

describe("searchCommands", () => {
  it("returns all commands for empty query", () => {
    const result = searchCommands(staticCommands, "");
    expect(result.length).toBe(staticCommands.length);
  });

  it("filters and sorts by score", () => {
    const result = searchCommands(staticCommands, "toggle");
    expect(result.length).toBeGreaterThan(0);
    expect(result[0].id).toBe("action:toggle-theme");
  });

  it("returns empty for unmatched query", () => {
    const result = searchCommands(staticCommands, "xyznonexistent");
    expect(result.length).toBe(0);
  });

  it("matches keyboard shortcuts help via keyword", () => {
    const result = searchCommands(staticCommands, "hotkeys");
    expect(result.some((c) => c.id === "action:shortcuts-help")).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// groupCommands
// ---------------------------------------------------------------------------

describe("groupCommands", () => {
  it("groups commands by category in order", () => {
    const groups = groupCommands(staticCommands);
    expect(groups[0].category).toBe("navigation");
    expect(groups[0].label).toBe("Navigation");
    expect(groups[1].category).toBe("actions");
    expect(groups[1].label).toBe("Actions");
  });

  it("omits empty categories", () => {
    const navOnly = staticCommands.filter((c) => c.category === "navigation");
    const groups = groupCommands(navOnly);
    expect(groups.length).toBe(1);
    expect(groups[0].category).toBe("navigation");
  });

  it("includes runs category when run commands are present", () => {
    const withRuns: Command[] = [
      ...staticCommands,
      { id: "run:123", label: "Test Run", category: "runs" },
    ];
    const groups = groupCommands(withRuns);
    const runsGroup = groups.find((g) => g.category === "runs");
    expect(runsGroup).toBeDefined();
    expect(runsGroup!.label).toBe("Recent Runs");
  });
});
