import { describe, it, expect } from "vitest";
import { bucketOf, retargetKey, forwardingPrefixFor, retargetUrl } from "./presigned-requests";

const SIGNED_URL =
  "http://object-storage:9000/choruskube-it/system/runs/run-a/out/hello.txt" +
  "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=abc%2F20261125%2Fus-east-1%2Fs3%2Faws4_request" +
  "&X-Amz-Date=20261125T000000Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=deadbeef";

describe("bucketOf", () => {
  it("returns the first path segment of a path-style URL", () => {
    expect(bucketOf(SIGNED_URL)).toBe("choruskube-it");
  });

  it("returns the bucket regardless of how deep the key nests", () => {
    expect(bucketOf("https://host:443/my-bucket/a/b/c.txt?x=1")).toBe("my-bucket");
  });
});

describe("retargetKey", () => {
  const ownTree = "system/runs/run-a/";
  const bucket = "choruskube-it";
  const targetKey = "system/runs/run-b/out/run_log.md";

  it("starts with the own tree, has no empty/./.. segment, and ends in /<bucket>/<targetKey>", () => {
    const key = retargetKey(ownTree, targetKey, bucket);

    expect(key.startsWith(ownTree)).toBe(true);
    expect(key.endsWith(`/${bucket}/${targetKey}`)).toBe(true);
    const segments = key.split("/");
    expect(segments).not.toContain("");
    expect(segments).not.toContain(".");
    expect(segments).not.toContain("..");
  });
});

describe("forwardingPrefixFor", () => {
  it("joined with /<bucket>/<target> equals /<bucket>/<retargetKey>", () => {
    const ownTree = "system/runs/run-a/";
    const bucket = "choruskube-it";
    const target = "system/runs/run-b/out/run_log.md";

    const lhs = forwardingPrefixFor(ownTree, bucket) + "/" + bucket + "/" + target;
    const rhs = "/" + bucket + "/" + retargetKey(ownTree, target, bucket);

    expect(lhs).toBe(rhs);
  });
});

describe("retargetUrl", () => {
  it("keeps the scheme, authority and raw query byte for byte, replacing only the path", () => {
    const newPath = "/choruskube-it/system/runs/run-b/out/run_log.md";

    const result = retargetUrl(SIGNED_URL, newPath);

    const queryIndex = SIGNED_URL.indexOf("?");
    const originalQuery = SIGNED_URL.slice(queryIndex);
    expect(result.startsWith("http://object-storage:9000")).toBe(true);
    expect(result).toBe(`http://object-storage:9000${newPath}${originalQuery}`);
    expect(result.endsWith(originalQuery)).toBe(true);
  });

  it("leaves a URL with no query untouched beyond the path", () => {
    expect(retargetUrl("https://host:9000/bucket/old.txt", "/bucket/new.txt")).toBe(
      "https://host:9000/bucket/new.txt",
    );
  });
});
