import { describe, it, expect } from "vitest";
import { uniqueName } from "./api-client";

// `uniqueName` normally derives its worker/shard indices from Playwright's own
// `test.info().parallelIndex` and the `SHARD_INDEX` env var (unavailable here —
// this suite runs under Vitest, not a live Playwright test run). Every call
// below passes both indices explicitly, exercising the same code path
// `uniqueName` uses internally once the indices are known, without needing a
// running Playwright test.
describe("uniqueName", () => {
  it("never collides across different simulated worker indices in the same shard", () => {
    const names = new Set<string>();
    for (let worker = 0; worker < 8; worker++) {
      names.add(uniqueName("epic", worker, 0));
    }
    expect(names.size).toBe(8);
  });

  it("never collides across different simulated shard indices for the same worker", () => {
    const names = new Set<string>();
    for (let shard = 0; shard < 4; shard++) {
      names.add(uniqueName("epic", 0, shard));
    }
    expect(names.size).toBe(4);
  });

  it("never collides across a full worker x shard matrix", () => {
    const names = new Set<string>();
    for (let shard = 0; shard < 4; shard++) {
      for (let worker = 0; worker < 4; worker++) {
        names.add(uniqueName("task", worker, shard));
      }
    }
    expect(names.size).toBe(16);
  });

  it("repeated calls from the same worker/shard index are distinguishable, not colliding", () => {
    // Mirrors what the worker-scoped fixture relies on: a spec (or the
    // fixture itself) may call uniqueName more than once from the same
    // worker/shard slot to mint several distinct resource names — those
    // calls must not collide with each other.
    const first = uniqueName("run", 2, 1);
    const second = uniqueName("run", 2, 1);
    expect(first).not.toBe(second);
  });

  it("keeps the worker/shard component stable across repeated calls from the same slot", () => {
    // The worker/shard-derived portion of the name is deterministic given the
    // same indices — only the trailing per-call suffix varies. This is useful
    // to any caller correlating multiple uniqueName(...) calls back to the
    // same worker/shard slot (e.g. log/dashboard readability) without needing
    // to memoize a single result. The worker-scoped `workerRepo` fixture does
    // NOT rely on this: it needs fetch-or-create idempotency across retries of
    // the same slot, so it builds its own "s<shard>w<worker>" name directly
    // rather than calling uniqueName(), which mints a fresh suffix every call.
    const a = uniqueName("story", 3, 2);
    const b = uniqueName("story", 3, 2);
    const worker3Shard2Marker = "-s2w3";
    expect(a).toContain(worker3Shard2Marker);
    expect(b).toContain(worker3Shard2Marker);
  });

  it("includes the prefix verbatim so failures remain readable", () => {
    const name = uniqueName("My Readable Prefix", 0, 0);
    expect(name.startsWith("My Readable Prefix")).toBe(true);
  });

  it("defaults shardIndex to 0 when SHARD_INDEX is unset (local, unsharded runs)", () => {
    const original = process.env.SHARD_INDEX;
    delete process.env.SHARD_INDEX;
    try {
      // parallelIndex is still passed explicitly — only shardIndex is left to
      // default from the env var, matching how a local `./scripts/e2e.sh` run
      // (single shard) would exercise this function.
      const name = uniqueName("local", 0);
      expect(name).toContain("-s0w0");
    } finally {
      if (original === undefined) {
        delete process.env.SHARD_INDEX;
      } else {
        process.env.SHARD_INDEX = original;
      }
    }
  });
});
