import { describe, it, expect } from "vitest";
import { uniqueName } from "./api-client";

// `uniqueName` normally derives its worker index from Playwright's own
// `test.info().parallelIndex`, which is unavailable here — this suite runs under
// Vitest, not a live Playwright test run. Every call below passes the index
// explicitly, exercising the same code path `uniqueName` uses internally once the
// index is known, without needing a running Playwright test.
describe("uniqueName", () => {
  it("puts each worker's index in the name, so two workers can never produce the same one", () => {
    // Asserted on the worker segment rather than on Set size: the module-global
    // per-call counter alone makes every name in a loop distinct, so a size check
    // would still pass if the worker index were dropped from the format entirely.
    // The worker segment is the part that actually separates concurrent workers.
    const segments = [0, 1, 2, 3, 4, 5, 6, 7].map((worker) => {
      const name = uniqueName("epic", worker);
      expect(name).toContain(`-w${worker}-`);
      return `-w${worker}-`;
    });
    expect(new Set(segments).size).toBe(8);
  });

  it("repeated calls from the same worker are distinguishable, not colliding", () => {
    // A spec may call uniqueName more than once from the same worker to mint
    // several distinct resource names — those calls must not collide.
    const first = uniqueName("run", 2);
    const second = uniqueName("run", 2);
    expect(first).not.toBe(second);
  });

  it("keeps the worker component stable across repeated calls from the same worker", () => {
    // Only the trailing per-call suffix varies, so multiple names minted by one
    // worker stay correlatable back to it when reading logs or the database.
    const a = uniqueName("story", 3);
    const b = uniqueName("story", 3);
    expect(a).toContain("-w3-");
    expect(b).toContain("-w3-");
  });

  it("includes the prefix verbatim so failures remain readable", () => {
    const name = uniqueName("My Readable Prefix", 0);
    expect(name.startsWith("My Readable Prefix")).toBe(true);
  });
});
