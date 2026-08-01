import { test, expect } from "../fixtures";

test.describe("Documentation", () => {
  test("shows documentation index", async ({ docsPage }) => {
    await docsPage.goto();
    await expect(docsPage.heading).toBeVisible();
    await expect(docsPage.docListItems.first()).toBeVisible();
  });

  test("navigates to a doc page", async ({ docsPage }) => {
    await docsPage.goto();
    await docsPage.selectDoc("Getting Started");
    await expect(docsPage.pageTitle).toContainText("Getting Started");
    await expect(docsPage.pageContent).toBeVisible();
  });

  test("documentation link exists in sidebar", async ({ page }) => {
    await page.goto("/");
    const docsLink = page.getByTestId("nav-documentation");
    await expect(docsLink).toBeVisible();
  });

  // 6a — Multiple mermaid diagrams render independently with no cross-contamination.
  test("renders multiple mermaid diagrams without cross-contamination", async ({ docsPage }) => {
    await docsPage.goto();
    await docsPage.selectDoc("Getting Started");

    // Wait for both diagrams to finish rendering.
    await expect(docsPage.mermaidDiagrams).toHaveCount(2);
    for (const diagram of await docsPage.mermaidDiagrams.all()) {
      await expect(diagram).toHaveAttribute("data-rendered", "true");
      await expect(diagram.locator("svg")).toBeVisible();
    }

    // Each SVG contains only its own participants.
    const first  = docsPage.mermaidDiagrams.nth(0);
    const second = docsPage.mermaidDiagrams.nth(1);
    await expect(first).toContainText("Client");
    await expect(first).not.toContainText("Worker");
    await expect(second).toContainText("Worker");
    await expect(second).not.toContainText("Client");
  });

  // Regression: a sequenceDiagram participant alias that collides with a Mermaid
  // reserved word (`actor`) renders as a real diagram, not the raw-source error
  // fallback. Exercises the fix end-to-end against a real browser and mermaid.js,
  // using the "Approval Flow" fixture diagram in the Features Overview doc.
  test("renders a sequence diagram whose participant alias is a reserved word", async ({
    docsPage,
  }) => {
    await docsPage.goto();
    await docsPage.selectDoc("Features Overview");

    await expect(docsPage.mermaidDiagrams).toHaveCount(1);
    const diagram = docsPage.mermaidDiagrams.first();
    await expect(diagram).toHaveAttribute("data-rendered", "true");
    await expect(diagram.locator("svg")).toBeVisible();

    // The `as` label proves the diagram parsed and rendered rather than
    // falling back to the bordered raw-source error box.
    await expect(diagram).toContainText("Reviewer");
  });

  // 6b — Internal links navigate via pushState without triggering a popup.
  test("internal link navigates via pushState without page reload", async ({ docsPage, page }) => {
    await docsPage.goto();
    await docsPage.selectDoc("Getting Started");
    await expect(docsPage.internalLink).toBeVisible();

    let popupCreated = false;
    page.once("popup", () => { popupCreated = true; });

    await docsPage.internalLink.click();
    await page.waitForURL("**/docs/features");
    expect(popupCreated).toBe(false);
  });

  // 6c — External links open in a new tab; the current tab URL does not change.
  test("external link opens a new tab", async ({ docsPage, page }) => {
    await docsPage.goto();
    await docsPage.selectDoc("Getting Started");
    await expect(docsPage.externalLink).toBeVisible();

    const [popup] = await Promise.all([
      page.waitForEvent("popup"),
      docsPage.externalLink.click(),
    ]);
    await expect(popup).toBeDefined();
    // Current tab URL must not have changed.
    expect(page.url()).toContain("/docs/getting-started");
  });

  test("doc page content fills the available width (no bounded box)", async ({ docsPage }) => {
    await docsPage.goto();
    await docsPage.selectDoc("Getting Started");
    const content = docsPage.pageContent;
    await expect(content).toBeVisible();
    const box = await content.boundingBox();
    expect(box?.width).toBeGreaterThan(640);
  });

  // "Getting Started" fixture doc contains [Features Overview](/docs/features) — an internal link
  // guaranteed to be present, so there is no conditional skip here.
  test("internal link in doc content navigates in the same tab", async ({ docsPage, page }) => {
    await docsPage.goto();
    await docsPage.selectDoc("Getting Started");
    const internalLink = page.locator('[data-testid="docs-page-content"] a[href^="/"]').first();
    await expect(internalLink).toBeVisible();
    await internalLink.click();
    await expect(page).not.toHaveURL("/docs/getting-started");
  });

  // "Getting Started" fixture doc contains [project repository](https://github.com/...) — an external link
  // guaranteed to be present, so there is no conditional skip here.
  test("external link in doc content has target=_blank", async ({ docsPage, page }) => {
    await docsPage.goto();
    await docsPage.selectDoc("Getting Started");
    const externalLink = page.locator('[data-testid="docs-page-content"] a[href^="http"]').first();
    await expect(externalLink).toBeVisible();
    await expect(externalLink).toHaveAttribute("target", "_blank");
    await expect(externalLink).toHaveAttribute("rel", "noopener noreferrer");
  });
});
