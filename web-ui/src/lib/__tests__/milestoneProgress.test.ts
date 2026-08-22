import { describe, it, expect } from "vitest";
import { milestoneProgressSegments } from "../milestoneProgress";

describe("milestoneProgressSegments", () => {
  it("returns all-zero segments for a Milestone with no descendant Tasks (no NaN)", () => {
    const segments = milestoneProgressSegments({
      totalTasks: 0,
      doneTasks: 0,
      inProgressTasks: 0,
      notStartedTasks: 0,
    });

    expect(segments).toEqual({ done: 0, inProgress: 0, notStarted: 0 });
    expect(Number.isNaN(segments.done)).toBe(false);
    expect(Number.isNaN(segments.inProgress)).toBe(false);
    expect(Number.isNaN(segments.notStarted)).toBe(false);
  });

  it("splits proportionally for a mix of done/in-progress/not-started Tasks", () => {
    const segments = milestoneProgressSegments({
      totalTasks: 4,
      doneTasks: 1,
      inProgressTasks: 1,
      notStartedTasks: 2,
    });

    expect(segments).toEqual({ done: 25, inProgress: 25, notStarted: 50 });
    expect(segments.done + segments.inProgress + segments.notStarted).toBe(100);
  });

  it("sums to 100 for an all-done Milestone", () => {
    const segments = milestoneProgressSegments({
      totalTasks: 3,
      doneTasks: 3,
      inProgressTasks: 0,
      notStartedTasks: 0,
    });

    expect(segments).toEqual({ done: 100, inProgress: 0, notStarted: 0 });
  });

  it("sums to 100 for an odd task count that doesn't divide evenly", () => {
    const segments = milestoneProgressSegments({
      totalTasks: 3,
      doneTasks: 1,
      inProgressTasks: 1,
      notStartedTasks: 1,
    });

    expect(segments.done + segments.inProgress + segments.notStarted).toBeCloseTo(100);
  });
});
