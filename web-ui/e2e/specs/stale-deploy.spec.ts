// A tab opened before a deploy keeps every diagram renderer in memory (the `vendor`
// chunk loads once, at page load, and nothing is fetched lazily afterward), so it must
// still render diagrams once the server stops serving that build's JS assets. This spec
// simulates the deploy by failing every `/assets/*.js` request after the initial load and
// asserts no such request is ever made — the regression this guards is "Failed to fetch
// dynamically imported module" after a deploy renames the lazy chunks.
import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

test.describe("Diagrams survive a stale deploy", () => {
  test("a page opened before a deploy still renders its diagrams", async ({ docsPage, page }) => {
    // The only full page load in this test — everything after simulates a deploy that has
    // already happened, so no further navigation may hit the network for JS.
    await docsPage.goto();

    const fetched: string[] = [];
    await page.route(/\/assets\/[^?]+\.js(\?.*)?$/, async (route) => {
      fetched.push(route.request().url());
      await route.fulfill({ status: 404, body: "" });
    });

    await docsPage.selectDoc("Workflow Templates");
    await expect(docsPage.mermaidDiagrams).toHaveCount(1);
    const flowchart = docsPage.mermaidDiagrams.first();
    await expect(flowchart).toHaveAttribute("data-rendered", "true");
    await expect(flowchart.locator("svg")).toBeVisible();
    await expect(flowchart).toContainText("Implement");
    await expect(flowchart).toContainText("Revise Spec");

    // Client-side navigation only from here on — never page.goto() or reload() after the
    // route interception above, or the test would just be reloading a fresh (uncached) page.
    await page.getByTestId("nav-documentation").click();
    await docsPage.selectDoc("Getting Started");
    await expect(docsPage.mermaidDiagrams).toHaveCount(2);
    for (const diagram of await docsPage.mermaidDiagrams.all()) {
      await expect(diagram).toHaveAttribute("data-rendered", "true");
      await expect(diagram.locator("svg")).toBeVisible();
    }

    expect(fetched).toEqual([]);
  });

  test("a missing hashed asset is a 404, never the app shell", async ({ request }) => {
    const response = await request.get(`/assets/${uniqueName("missing-chunk")}.js`);
    expect(response.status()).toBe(404);
  });

  test("the app shell revalidates; hashed assets are immutable and compressed", async ({
    request,
  }) => {
    for (const path of ["/", "/docs/getting-started"]) {
      const response = await request.get(path);
      expect(response.status()).toBe(200);
      expect(response.headers()["cache-control"]).toBe("no-cache");
    }

    const shell = await request.get("/");
    const body = await shell.text();
    const match = body.match(/<script[^>]+type="module"[^>]+crossorigin[^>]+src="(\/assets\/[^"]+\.js)"/);
    expect(match).not.toBeNull();
    const entryScriptPath = match![1];

    const asset = await request.get(entryScriptPath, {
      headers: { "Accept-Encoding": "gzip" },
    });
    expect(asset.status()).toBe(200);
    const cacheControl = asset.headers()["cache-control"];
    expect(cacheControl).toContain("immutable");
    expect(cacheControl).toContain("max-age=31536000");
    expect(asset.headers()["content-encoding"]).toBe("gzip");
  });
});
