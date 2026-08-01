import { describe, it, expect, vi } from "vitest";
import { screen, fireEvent, act, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import MarkdownViewer from "../MarkdownViewer";

// 3a. Mock uses mermaid.render (not mermaid.run) — matches the rewritten MermaidDiagram.
const mermaidRenderMock = vi.fn().mockResolvedValue({ svg: "<svg>mock</svg>" });
vi.mock("mermaid", () => ({
  default: {
    initialize: vi.fn(),
    render: (...args: unknown[]) => mermaidRenderMock(...args),
  },
}));

describe("MarkdownViewer", () => {
  it("renders null for empty content", () => {
    const { container } = renderWithProviders(<MarkdownViewer content="" />);
    expect(container.innerHTML).toBe("");
  });

  it("renders plain text content", () => {
    renderWithProviders(<MarkdownViewer content="Hello world" />);
    expect(screen.getByText("Hello world")).toBeInTheDocument();
  });

  it("renders bold markdown syntax", () => {
    renderWithProviders(<MarkdownViewer content="This is **bold** text" />);
    const strong = screen.getByText("bold");
    expect(strong.tagName).toBe("STRONG");
  });

  it("renders italic markdown syntax", () => {
    renderWithProviders(<MarkdownViewer content="This is *italic* text" />);
    const em = screen.getByText("italic");
    expect(em.tagName).toBe("EM");
  });

  it("renders headings", () => {
    renderWithProviders(<MarkdownViewer content="# Main Title" />);
    const heading = screen.getByText("Main Title");
    expect(heading.tagName).toBe("H1");
  });

  it("renders unordered lists", () => {
    renderWithProviders(
      <MarkdownViewer content={"- item one\n- item two\n- item three"} />
    );
    expect(screen.getByText("item one")).toBeInTheDocument();
    expect(screen.getByText("item two")).toBeInTheDocument();
    expect(screen.getByText("item three")).toBeInTheDocument();
  });

  // 3j. External link test — preserved; https links open in new tab.
  it("renders external https links with target=_blank and rel", () => {
    renderWithProviders(
      <MarkdownViewer content="Visit [Example](https://example.com)" />
    );
    const link = screen.getByText("Example");
    expect(link.tagName).toBe("A");
    expect(link).toHaveAttribute("href", "https://example.com");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  it("renders absolute images as img tags", () => {
    renderWithProviders(
      <MarkdownViewer content="![Alt text](https://example.com/image.png)" />
    );
    const img = screen.getByRole("img");
    expect(img).toBeInTheDocument();
    expect(img).toHaveAttribute("src", "https://example.com/image.png");
    expect(img).toHaveAttribute("alt", "Alt text");
  });

  it("falls back to text link when image fails to load", () => {
    renderWithProviders(
      <MarkdownViewer content="![Alt text](https://example.com/image.png)" />
    );
    const img = screen.getByRole("img");
    fireEvent.error(img);

    // After error, should show a text link instead
    const link = screen.getByText("Alt text");
    expect(link.tagName).toBe("A");
    expect(link).toHaveAttribute("href", "https://example.com/image.png");
  });

  it("resolves relative image paths with artifactContext", () => {
    renderWithProviders(
      <MarkdownViewer
        content="![Screenshot](screenshot.png)"
        artifactContext={{ runId: "run-1", execId: "exec-1" }}
      />
    );
    const img = screen.getByRole("img");
    expect(img).toBeInTheDocument();
    expect(img.getAttribute("src")).toContain("/runs/run-1/node-executions/exec-1/artifacts/screenshot.png");
  });

  it("does not resolve absolute URLs even with artifactContext", () => {
    renderWithProviders(
      <MarkdownViewer
        content="![Photo](https://cdn.example.com/photo.jpg)"
        artifactContext={{ runId: "run-1", execId: "exec-1" }}
      />
    );
    const img = screen.getByRole("img");
    expect(img).toHaveAttribute("src", "https://cdn.example.com/photo.jpg");
  });

  it("renders relative images as text links without artifactContext", () => {
    renderWithProviders(
      <MarkdownViewer content="![Chart](chart.png)" />
    );
    // Without artifactContext, relative path stays as-is — still rendered as img
    const img = screen.getByRole("img");
    expect(img).toHaveAttribute("src", "chart.png");
  });

  it("renders inline code", () => {
    renderWithProviders(<MarkdownViewer content="Use `const x = 1`" />);
    const code = screen.getByText("const x = 1");
    expect(code.tagName).toBe("CODE");
  });

  it("renders GFM tables", () => {
    const tableContent =
      "| Name | Value |\n| --- | --- |\n| Alice | 42 |\n| Bob | 99 |";
    renderWithProviders(<MarkdownViewer content={tableContent} />);
    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("Alice")).toBeInTheDocument();
    expect(screen.getByText("99")).toBeInTheDocument();
  });

  it("renders GFM strikethrough", () => {
    renderWithProviders(<MarkdownViewer content="This is ~~deleted~~ text" />);
    const del = screen.getByText("deleted");
    expect(del.tagName).toBe("DEL");
  });

  it("shows Raw toggle button", () => {
    renderWithProviders(<MarkdownViewer content="# Hello" />);
    expect(screen.getByText("Raw")).toBeInTheDocument();
  });

  it("toggles between Raw and Rendered views", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <MarkdownViewer content="**bold text**" />
    );

    // Initially rendered — bold text should be in a <strong>
    expect(screen.getByText("bold text").tagName).toBe("STRONG");

    // Click Raw button to show raw markdown
    await user.click(screen.getByText("Raw"));

    // Now the toggle button should say "Rendered"
    expect(screen.getByText("Rendered")).toBeInTheDocument();
    // The raw markdown source should be visible
    expect(screen.getByText("**bold text**")).toBeInTheDocument();

    // Click Rendered button to go back
    await user.click(screen.getByText("Rendered"));
    expect(screen.getByText("Raw")).toBeInTheDocument();
    expect(screen.getByText("bold text").tagName).toBe("STRONG");
  });

  it("applies custom maxHeight class", () => {
    const { container } = renderWithProviders(
      <MarkdownViewer content="Some content" maxHeight="max-h-96" />
    );
    const contentDiv = container.querySelector(".max-h-96");
    expect(contentDiv).toBeInTheDocument();
  });

  it("renders blockquotes", () => {
    renderWithProviders(<MarkdownViewer content="> This is a quote" />);
    const quote = screen.getByText("This is a quote");
    expect(quote.closest("blockquote")).toBeInTheDocument();
  });

  // 3b. Updated mermaid dispatch test — uses mermaidRenderMock (not mermaidRunMock).
  it("dispatches ```mermaid fenced blocks to mermaid.render instead of rendering as code", async () => {
    mermaidRenderMock.mockClear();
    const mermaidContent =
      "```mermaid\nsequenceDiagram\n  participant A\n  A->>B: hello\n```";
    renderWithProviders(<MarkdownViewer content={mermaidContent} />);

    const diagram = await screen.findByTestId("mermaid-diagram");
    expect(diagram).toBeInTheDocument();
    // mermaid CSS class removed — no toHaveClass("mermaid") assertion.

    // mermaid.render was called with an id string and the source string.
    expect(mermaidRenderMock).toHaveBeenCalled();
    const [callId, callSource] = mermaidRenderMock.mock.calls[0] as [string, string];
    expect(typeof callId).toBe("string");
    // Every sequenceDiagram participant alias is quoted unconditionally before
    // render (Decision 2) — a no-op for a non-colliding alias like "A" other
    // than the added quotes themselves.
    expect(callSource).toBe('sequenceDiagram\n  participant "A"\n  "A"->>B: hello');

    // The diagram element should indicate successful render.
    await waitFor(() => expect(diagram).toHaveAttribute("data-rendered", "true"));
  });

  // 3c. Updated non-mermaid block test.
  it("renders non-mermaid fenced code blocks as <code> blocks unchanged", () => {
    mermaidRenderMock.mockClear();
    const jsContent = "```js\nconst x = 1;\n```";
    renderWithProviders(<MarkdownViewer content={jsContent} />);

    expect(screen.queryByTestId("mermaid-diagram")).not.toBeInTheDocument();
    // The js source still renders inside a <code> element.
    const code = screen.getByText(/const x = 1;/);
    expect(code.tagName).toBe("CODE");
    // mermaid.render must not be invoked for non-mermaid blocks.
    expect(mermaidRenderMock).not.toHaveBeenCalled();
  });

  // 3d. Updated render-failure test.
  it("falls back to raw source when mermaid render fails", async () => {
    mermaidRenderMock.mockClear();
    mermaidRenderMock.mockRejectedValueOnce(new Error("syntax error"));
    const badMermaid = "```mermaid\ninvalid syntax here\n```";
    renderWithProviders(<MarkdownViewer content={badMermaid} />);

    expect(await screen.findByText(/Mermaid render error/)).toBeInTheDocument();
    expect(screen.getByText(/syntax error/)).toBeInTheDocument();
    // Source is preserved so the artifact stays readable.
    expect(screen.getByText(/invalid syntax here/)).toBeInTheDocument();
  });

  // 3e. extractMermaidError behaviour — plain-object rejection.
  it("shows str field and raw source when mermaid rejects with a plain object", async () => {
    mermaidRenderMock.mockClear();
    const source = "graph TD\n  A --> B";
    mermaidRenderMock.mockRejectedValueOnce({ str: "unexpected token at line 2" });
    renderWithProviders(
      <MarkdownViewer content={"```mermaid\n" + source + "\n```"} />
    );

    // The .str field should appear in the error message.
    expect(
      await screen.findByText(/unexpected token at line 2/)
    ).toBeInTheDocument();
    // "[object Object]" must NOT appear — that was the old broken behaviour.
    expect(screen.queryByText(/\[object Object\]/)).not.toBeInTheDocument();
    // Raw source is preserved in the <pre> fallback block.
    // (Matches the existing error-path pattern — same as the "invalid syntax here" check above.)
    expect(screen.getByText(/graph TD/)).toBeInTheDocument();
  });

  // 3f. Per-diagram isolation — two mermaid blocks call mermaid.render independently.
  it("two mermaid diagrams call mermaid.render independently", async () => {
    mermaidRenderMock.mockClear();
    const content = [
      "```mermaid",
      "sequenceDiagram",
      "  participant Client",
      "```",
      "",
      "```mermaid",
      "sequenceDiagram",
      "  participant Worker",
      "```",
    ].join("\n");

    renderWithProviders(<MarkdownViewer content={content} />);

    // Wait for both diagrams to appear.
    await waitFor(() => {
      const diagrams = screen.getAllByTestId("mermaid-diagram");
      expect(diagrams).toHaveLength(2);
    });

    // mermaid.render was called exactly twice (once per diagram).
    expect(mermaidRenderMock).toHaveBeenCalledTimes(2);

    // Each call used a distinct id string.
    const [firstId] = mermaidRenderMock.mock.calls[0] as [string, string];
    const [secondId] = mermaidRenderMock.mock.calls[1] as [string, string];
    expect(firstId).not.toBe(secondId);
  });

  // 3g. 4-branch href classifier — https links open in new tab.
  it("routes https: links to new tab with target=_blank and rel", () => {
    renderWithProviders(
      <MarkdownViewer content="Click [here](https://external.example.com)" />
    );
    const link = screen.getByText("here");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  // 3g. 4-branch href classifier — root-relative paths use React Router Link.
  it("routes /path links to React Router Link without target=_blank", () => {
    renderWithProviders(
      <MarkdownViewer content="See [other doc](/docs/other)" />
    );
    const link = screen.getByText("other doc");
    // React Router <Link> renders as <a> without target attribute.
    expect(link).not.toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("href", "/docs/other");
  });

  // 3h. Cancellation — stale SVG is discarded when source changes mid-render.
  it("discards stale SVG when source changes before render resolves", async () => {
    mermaidRenderMock.mockClear();

    let resolveFirst!: (result: { svg: string }) => void;
    const firstPromise = new Promise<{ svg: string }>((resolve) => {
      resolveFirst = resolve;
    });

    // First call returns a pending promise; second call resolves immediately.
    mermaidRenderMock.mockReturnValueOnce(firstPromise);
    mermaidRenderMock.mockResolvedValueOnce({ svg: "<svg>second</svg>" });

    const { rerender } = renderWithProviders(
      <MarkdownViewer content={"```mermaid\nsource1\n```"} />
    );

    // Change source before the first render promise resolves.
    rerender(<MarkdownViewer content={"```mermaid\nsource2\n```"} />);

    // Wait for the second render to complete (second mock resolves immediately).
    const diagram = screen.getByTestId("mermaid-diagram");
    await waitFor(() => expect(diagram).toHaveAttribute("data-rendered", "true"));

    // Now resolve the stale first promise — it should be discarded.
    await act(async () => {
      resolveFirst({ svg: "<svg>stale</svg>" });
      await Promise.resolve(); // flush microtasks
    });

    // The diagram should not contain the stale SVG.
    expect(diagram.innerHTML).not.toContain("stale");
  });

  // End-to-end: a `;` mid-message-label is escaped to `#59;` before mermaid.render
  // is called, working around Mermaid's grammar treating `;` as a statement separator.
  it("escapes `;` to `#59;` in mermaid source labels before calling mermaid.render", async () => {
    mermaidRenderMock.mockClear();
    const content =
      "```mermaid\nsequenceDiagram\n  A->>B: Page fills available width; no card border\n```";
    renderWithProviders(<MarkdownViewer content={content} />);

    await screen.findByTestId("mermaid-diagram");
    expect(mermaidRenderMock).toHaveBeenCalled();
    const [, callSource] = mermaidRenderMock.mock.calls[0] as [string, string];
    expect(callSource).toContain("Page fills available width#59; no card border");
    // The raw `;` mid-label must not survive into mermaid's input.
    expect(callSource).not.toMatch(/width; no/);
  });

  // End-to-end: a sequenceDiagram participant alias that collides with a Mermaid
  // reserved word (`actor`) is quoted before mermaid.render is called, working
  // around Mermaid's grammar treating bare reserved words as keywords.
  it("quotes a reserved-word participant alias before calling mermaid.render", async () => {
    mermaidRenderMock.mockClear();
    const fenceSource =
      "sequenceDiagram\n  participant Actor as Any user/agent\n  Actor->>API: hello";
    const content = "```mermaid\n" + fenceSource + "\n```";
    renderWithProviders(<MarkdownViewer content={content} />);

    await screen.findByTestId("mermaid-diagram");
    expect(mermaidRenderMock).toHaveBeenCalled();
    const [, callSource] = mermaidRenderMock.mock.calls[0] as [string, string];

    // The healed source reaching mermaid.render has the alias quoted at both
    // the declaration and the message-arrow reference.
    expect(callSource).toContain('participant "Actor" as Any user/agent');
    expect(callSource).toContain('"Actor"->>API: hello');

    // The original, unquoted fence text is untouched — the Raw toggle still
    // shows exactly what was authored.
    expect(screen.getByText("Raw")).toBeInTheDocument();
    await userEvent.setup().click(screen.getByText("Raw"));
    const pre = screen.getByText(/participant Actor as Any user\/agent/);
    expect(pre.textContent).toContain(fenceSource);
  });

  // 3i. Prose variant — now has Raw/Rendered toggle.
  it("prose variant still renders Raw/Rendered toggle", () => {
    renderWithProviders(<MarkdownViewer content="**bold**" variant="prose" />);
    expect(screen.getByText("Raw")).toBeInTheDocument();
  });

  // 3i. Prose variant — maxHeight class is not applied.
  it("prose variant does not apply maxHeight class to a wrapper", () => {
    const { container } = renderWithProviders(
      <MarkdownViewer content="text" variant="prose" maxHeight="max-h-96" />
    );
    expect(container.querySelector(".max-h-96")).not.toBeInTheDocument();
  });

  // 3g. 4-branch href classifier — protocol-relative URLs open in new tab (not routed as SPA paths).
  it("routes protocol-relative // links to new tab, not React Router", () => {
    renderWithProviders(
      <MarkdownViewer content="See [CDN](//cdn.example.com/asset.js)" />
    );
    const link = screen.getByText("CDN");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    expect(link).toHaveAttribute("href", "//cdn.example.com/asset.js");
  });

  it("renders internal /path links as React Router Link with no target", () => {
    renderWithProviders(<MarkdownViewer content="Go to [Runs](/runs)" />);
    const link = screen.getByText("Runs");
    expect(link).toHaveAttribute("href", "/runs");
    expect(link).not.toHaveAttribute("target");
  });

  it("renders anchor #id links as plain <a> with no target", () => {
    renderWithProviders(<MarkdownViewer content="[Jump](#section)" />);
    const link = screen.getByText("Jump");
    expect(link.tagName).toBe("A");
    expect(link).toHaveAttribute("href", "#section");
    expect(link).not.toHaveAttribute("target");
  });

  it("renders mailto: links as plain <a> with no target", () => {
    renderWithProviders(<MarkdownViewer content="[Email](mailto:admin@example.com)" />);
    const link = screen.getByText("Email");
    expect(link.tagName).toBe("A");
    expect(link).toHaveAttribute("href", "mailto:admin@example.com");
    expect(link).not.toHaveAttribute("target");
  });

  it("javascript: hrefs are sanitised by react-markdown (regression)", () => {
    renderWithProviders(<MarkdownViewer content="[Bad](javascript:alert(1))" />);
    const link = screen.getByText("Bad");
    // react-markdown sanitizes javascript: URLs — href should be absent or not contain "javascript:"
    const href = link.getAttribute("href");
    expect(href === null || !href.includes("javascript:")).toBe(true);
  });

  it("renders prose variant without card border classes", () => {
    const { container } = renderWithProviders(
      <MarkdownViewer content="Hello" variant="prose" />
    );
    expect(container.querySelector(".border")).not.toBeInTheDocument();
    expect(container.querySelector(".bg-muted\\/30")).not.toBeInTheDocument();
    expect(container.querySelector(".overflow-auto")).not.toBeInTheDocument();
  });

  it("renders card variant (default) with border classes", () => {
    const { container } = renderWithProviders(
      <MarkdownViewer content="Hello" />
    );
    const borderEl = container.querySelector(".border");
    expect(borderEl).toBeInTheDocument();
  });

  it("prose variant renders mermaid diagrams", async () => {
    mermaidRenderMock.mockClear();
    const mermaidContent = "```mermaid\nsequenceDiagram\n  participant A\n  A->>B: hello\n```";
    renderWithProviders(<MarkdownViewer content={mermaidContent} variant="prose" />);

    const diagram = await screen.findByTestId("mermaid-diagram");
    expect(diagram).toBeInTheDocument();
    await waitFor(() => expect(diagram).toHaveAttribute("data-rendered", "true"));
  });

  // HTML comment stripping
  it("strips HTML comments from rendered view, leaving surrounding content intact", () => {
    const { container } = renderWithProviders(
      <MarkdownViewer content={"<!-- DO NOT edit this heading -->\n\nHello world."} />
    );
    // The comment text must not appear in the rendered output.
    expect(screen.queryByText(/DO NOT edit this heading/)).not.toBeInTheDocument();
    // Surrounding content is preserved — use container.textContent to avoid
    // normalisation quirks when react-markdown includes leading whitespace in
    // the paragraph's text node.
    expect(container.textContent).toContain("Hello world.");
  });

  it("raw view shows original content including HTML comments", async () => {
    const user = userEvent.setup();
    const rawContent = "<!-- this is a comment -->\n\nSome text.";
    const { container } = renderWithProviders(<MarkdownViewer content={rawContent} />);

    // Switch to raw view.
    await user.click(screen.getByText("Raw"));

    // The raw view should show the original, unprocessed content including the
    // HTML comment text. We check the <pre> element directly because Testing
    // Library's getByText normaliser can struggle with exact-match of strings
    // that mix HTML-comment-like syntax with real newlines.
    const pre = container.querySelector("pre");
    expect(pre).toBeInTheDocument();
    expect(pre?.textContent).toContain("<!-- this is a comment -->");
    expect(pre?.textContent).toContain("Some text.");
  });

  it("strips HTML comment syntax inside fenced code blocks in rendered view (pre-processor limitation)", () => {
    // stripHtmlComments operates on the raw string before react-markdown parses it,
    // so HTML comment syntax inside fenced code blocks is also stripped (Caveat 5).
    const content = "```\n<!-- comment inside code -->\nsome code\n```";
    renderWithProviders(<MarkdownViewer content={content} />);
    // "some code" is still present.
    expect(screen.getByText(/some code/)).toBeInTheDocument();
    // The comment text is stripped even though it was inside a code fence.
    expect(screen.queryByText(/comment inside code/)).not.toBeInTheDocument();
  });

  // Prose variant heading sizes
  it("prose variant h2 has text-2xl class", () => {
    const { container } = renderWithProviders(
      <MarkdownViewer content="## Section Heading" variant="prose" />
    );
    const h2 = container.querySelector("h2");
    expect(h2).toBeInTheDocument();
    expect(h2).toHaveClass("text-2xl");
  });

  it("prose variant h3 has text-xl class", () => {
    const { container } = renderWithProviders(
      <MarkdownViewer content="### Sub Heading" variant="prose" />
    );
    const h3 = container.querySelector("h3");
    expect(h3).toBeInTheDocument();
    expect(h3).toHaveClass("text-xl");
  });

  it("card variant h2 has text-sm class (compact)", () => {
    const { container } = renderWithProviders(
      <MarkdownViewer content="## Section Heading" />
    );
    const h2 = container.querySelector("h2");
    expect(h2).toBeInTheDocument();
    expect(h2).toHaveClass("text-sm");
    expect(h2).not.toHaveClass("text-2xl");
  });

  it("prose variant h4 has text-lg class", () => {
    const { container } = renderWithProviders(
      <MarkdownViewer content="#### Detail Heading" variant="prose" />
    );
    const h4 = container.querySelector("h4");
    expect(h4).toBeInTheDocument();
    expect(h4).toHaveClass("text-lg");
    expect(h4).not.toHaveClass("text-xs");
  });

  it("card variant h3 has text-sm class (compact)", () => {
    const { container } = renderWithProviders(
      <MarkdownViewer content="### Sub Heading" />
    );
    const h3 = container.querySelector("h3");
    expect(h3).toBeInTheDocument();
    expect(h3).toHaveClass("text-sm");
    expect(h3).not.toHaveClass("text-xl");
  });

  it("card variant h4 has text-xs class (compact)", () => {
    const { container } = renderWithProviders(
      <MarkdownViewer content="#### Detail Heading" />
    );
    const h4 = container.querySelector("h4");
    expect(h4).toBeInTheDocument();
    expect(h4).toHaveClass("text-xs");
    expect(h4).not.toHaveClass("text-lg");
  });

  // Prose variant list indentation
  it("prose variant ul has pl-6 class", () => {
    const { container } = renderWithProviders(
      <MarkdownViewer content={"- item one\n- item two"} variant="prose" />
    );
    const ul = container.querySelector("ul");
    expect(ul).toBeInTheDocument();
    expect(ul).toHaveClass("pl-6");
  });

  it("card variant ul has pl-4 class", () => {
    const { container } = renderWithProviders(
      <MarkdownViewer content={"- item one\n- item two"} />
    );
    const ul = container.querySelector("ul");
    expect(ul).toBeInTheDocument();
    expect(ul).toHaveClass("pl-4");
    expect(ul).not.toHaveClass("pl-6");
  });

  it("prose variant ol has pl-6 class", () => {
    const { container } = renderWithProviders(
      <MarkdownViewer content={"1. first\n2. second"} variant="prose" />
    );
    const ol = container.querySelector("ol");
    expect(ol).toBeInTheDocument();
    expect(ol).toHaveClass("pl-6");
  });

  it("card variant ol has pl-4 class", () => {
    const { container } = renderWithProviders(
      <MarkdownViewer content={"1. first\n2. second"} />
    );
    const ol = container.querySelector("ol");
    expect(ol).toBeInTheDocument();
    expect(ol).toHaveClass("pl-4");
    expect(ol).not.toHaveClass("pl-6");
  });

  // linkBase bare-slug resolution
  it("linkBase: bare slug resolves to linkBase/slug via React Router Link", () => {
    renderWithProviders(
      <MarkdownViewer
        content="See [Workflow Templates](workflow-templates)"
        linkBase="/docs"
      />
    );
    const link = screen.getByText("Workflow Templates");
    // Should be an in-app SPA link, not an external anchor.
    expect(link).toHaveAttribute("href", "/docs/workflow-templates");
    expect(link).not.toHaveAttribute("target", "_blank");
  });

  it("linkBase: mailto: link is NOT treated as a bare slug", () => {
    renderWithProviders(
      <MarkdownViewer
        content="[Contact](mailto:admin@example.com)"
        linkBase="/docs"
      />
    );
    const link = screen.getByText("Contact");
    // mailto: contains ":" so it must NOT be resolved via linkBase.
    expect(link).toHaveAttribute("href", "mailto:admin@example.com");
    expect(link).not.toHaveAttribute("href", "/docs/mailto:admin@example.com");
  });
});
