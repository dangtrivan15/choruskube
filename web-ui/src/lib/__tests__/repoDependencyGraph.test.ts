import { describe, it, expect } from "vitest";
import {
  addArrow,
  cycleThrough,
  layoutRepoDependencies,
  removeArrow,
  toArrows,
} from "@/lib/repoDependencyGraph";
import type { RepoDependency } from "@/lib/types";

describe("repoDependencyGraph", () => {
  it("toArrowsFlattensEachRepoEntry", () => {
    expect(
      toArrows([
        { repoId: "a", dependsOnRepoIds: ["b", "c"] },
        { repoId: "b", dependsOnRepoIds: ["c"] },
      ]),
    ).toEqual([
      { from: "a", to: "b" },
      { from: "a", to: "c" },
      { from: "b", to: "c" },
    ]);
  });

  it("addArrowCreatesTheRepoEntryOrExtendsIt", () => {
    const once = addArrow([], { from: "a", to: "b" });
    expect(once).toEqual([{ repoId: "a", dependsOnRepoIds: ["b"] }]);
    expect(addArrow(once, { from: "a", to: "c" })).toEqual([
      { repoId: "a", dependsOnRepoIds: ["b", "c"] },
    ]);
  });

  it("addArrowIgnoresAnArrowThatAlreadyExists", () => {
    const deps = [{ repoId: "a", dependsOnRepoIds: ["b"] }];
    expect(addArrow(deps, { from: "a", to: "b" })).toBe(deps);
  });

  it("removeArrowDropsTheEntryOnceTheRepoDependsOnNothing", () => {
    const deps = [
      { repoId: "a", dependsOnRepoIds: ["b", "c"] },
      { repoId: "b", dependsOnRepoIds: ["c"] },
    ];
    expect(removeArrow(deps, { from: "a", to: "b" })).toEqual([
      { repoId: "a", dependsOnRepoIds: ["c"] },
      { repoId: "b", dependsOnRepoIds: ["c"] },
    ]);
    expect(removeArrow(deps, { from: "b", to: "c" })).toEqual([
      { repoId: "a", dependsOnRepoIds: ["b", "c"] },
    ]);
  });

  it("cycleThroughReturnsTheLoopAnArrowWouldClose", () => {
    const deps = [
      { repoId: "a", dependsOnRepoIds: ["b"] },
      { repoId: "b", dependsOnRepoIds: ["c"] },
    ];
    expect(cycleThrough(deps, { from: "c", to: "a" })).toEqual(["c", "a", "b", "c"]);
    expect(cycleThrough(deps, { from: "b", to: "a" })).toEqual(["b", "a", "b"]);
    expect(cycleThrough(deps, { from: "a", to: "a" })).toEqual(["a", "a"]);
  });

  it("cycleThroughAllowsArrowsThatCloseNoLoop", () => {
    const deps = [
      { repoId: "a", dependsOnRepoIds: ["b"] },
      { repoId: "c", dependsOnRepoIds: ["b"] },
    ];
    expect(cycleThrough(deps, { from: "a", to: "c" })).toBeNull();
    expect(cycleThrough(deps, { from: "b", to: "d" })).toBeNull();
  });

  it("layoutPutsReposThatDependOnNothingInTheBottomRow", async () => {
    // top -> app -> base, top -> util; standalone stands alone.
    const deps: RepoDependency[] = [
      { repoId: "app", dependsOnRepoIds: ["base"] },
      { repoId: "top", dependsOnRepoIds: ["app", "util"] },
    ];
    const pos = await layoutRepoDependencies(["base", "app", "standalone", "top", "util"], deps);
    const y = (id: string) => pos.get(id)!.y;

    expect(y("standalone")).toBe(y("base"));
    expect(y("util")).toBe(y("base"));
    expect(y("app")).toBeLessThan(y("base"));
    expect(y("top")).toBeLessThan(y("app"));
  });

  it("layoutIgnoresArrowsToReposOutsideTheGroup", async () => {
    const pos = await layoutRepoDependencies(["a"], [{ repoId: "a", dependsOnRepoIds: ["gone"] }]);
    expect([...pos.keys()]).toEqual(["a"]);
  });
});
