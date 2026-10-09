// A tab opened before a deploy must still render diagrams once the server no longer has that
// build's JS. The deploy is simulated by 404ing every `/assets/*.js` request after the first load.
import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

test.describe("Diagrams survive a stale deploy", () => {
  test("a page opened before a deploy still renders its diagrams", async ({ docsPage, page }) => {
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
    // A long-lived header here would pin the miss in the browser for a year.
    expect(response.headers()["cache-control"]).toBeUndefined();
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

    // The `Via` case is a request forwarded by a reverse proxy, which nginx does not compress
    // by default; dropping it leaves gzip_proxied in nginx-spa-locations.conf untested.
    const requests: Record<string, string>[] = [
      { "Accept-Encoding": "gzip" },
      { "Accept-Encoding": "gzip", Via: "1.1 reverse-proxy" },
    ];
    for (const headers of requests) {
      const asset = await request.get(entryScriptPath, { headers });
      expect(asset.status()).toBe(200);
      const cacheControl = asset.headers()["cache-control"];
      expect(cacheControl).toContain("immutable");
      expect(cacheControl).toContain("max-age=31536000");
      expect(asset.headers()["content-encoding"]).toBe("gzip");
    }
  });
});
