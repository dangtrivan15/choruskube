import { memo, useEffect, useId, useState, useMemo, type ComponentPropsWithoutRef } from "react";
import { Link } from "react-router";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import mermaid from "mermaid";
import { artifactUrl } from "@/lib/api";
import { escapeMermaidSemicolons } from "@/lib/mermaid";

mermaid.initialize({
  startOnLoad: false,
  securityLevel: "strict",
  theme: "default",
  // Mermaid's default behavior on parse failure is to inject a "bomb" error SVG into a
  // body-level container as a side effect of render(), bypassing our contained error
  // box. suppressErrorRendering tells mermaid to reject the promise only, no DOM side
  // effects — see web-ui/node_modules/mermaid/dist/mermaid.esm.mjs:1485,1504.
  suppressErrorRendering: true,
});

interface ArtifactContext {
  runId: string;
  execId: string;
}

interface MarkdownViewerProps {
  /** The raw markdown (or plain text) string to render. */
  content: string;
  /**
   * Maximum height CSS class (e.g. "max-h-48", "max-h-72"). Defaults to "max-h-48".
   * @note Ignored when `variant="prose"` — prose mode has no height constraint.
   */
  maxHeight?: string;
  /** When provided, relative image paths are resolved as artifact URLs. */
  artifactContext?: ArtifactContext;
  /** Rendering mode. "card" shows the embedded viewer chrome (border, height cap, Raw/Rendered
   *  toggle). "prose" renders markdown directly without a border or height cap, but still
   *  includes a Raw/Rendered toggle, suitable for full-page doc views. */
  variant?: "card" | "prose";
  /**
   * Base path for resolving bare-slug links (e.g. `linkBase="/docs"` resolves
   * `[Foo](bar)` → `/docs/bar` via React Router Link). Has no effect on links
   * that already contain a URI scheme (http:, mailto:, etc.) or a leading slash.
   */
  linkBase?: string;
}

/** Strips HTML comments (`<!-- ... -->`) from a markdown string.
 *  Applied to the rendered view only — the raw-view toggle still shows the original.
 *  NOTE: because this is a string-level pre-processor, it also strips HTML comment
 *  syntax that appears inside fenced code blocks. See Caveat 5 in the spec. */
function stripHtmlComments(content: string): string {
  return content.replace(/<!--[\s\S]*?-->/g, "");
}

/** Renders an <img> with an onError fallback to a text link. */
function ImageWithFallback({ src, alt }: { src: string; alt: string }) {
  const [failed, setFailed] = useState(false);

  if (failed || !src) {
    return (
      <a
        href={src}
        target="_blank"
        rel="noopener noreferrer"
        className="text-primary underline underline-offset-2 hover:text-primary/80"
      >
        {alt || "Image"}
      </a>
    );
  }

  return (
    <img
      src={src}
      alt={alt || "Image"}
      className="my-2 max-h-96 max-w-full rounded border object-contain"
      onError={() => setFailed(true)}
    />
  );
}

function isAbsoluteUrl(url: string): boolean {
  return /^https?:\/\//i.test(url) || url.startsWith("data:");
}

/** Extracts a human-readable error string from mermaid's rejection payload.
 *  mermaid v11 rejects with structured plain objects ({ str, hash }) rather than
 *  Error instances, so a simple String(e) produces "[object Object]". */
function extractMermaidError(e: unknown): string {
  if (e instanceof Error) return e.message;
  if (
    e !== null &&
    typeof e === "object" &&
    "str" in e &&
    typeof (e as { str: unknown }).str === "string"
  ) {
    return (e as { str: string }).str;
  }
  return JSON.stringify(e);
}

/** Render a mermaid source string into an inline SVG via mermaid.render, which
 *  returns an SVG string that is injected via dangerouslySetInnerHTML.
 *  This eliminates both the DOM-mutation / React reconciler conflict and the
 *  concurrent-call interference present in mermaid.run().
 *  On render failure, falls back to showing the raw source so the artifact
 *  remains readable. */
function MermaidDiagram({ source }: { source: string }) {
  const reactId = useId();
  const renderId = `mermaid-${reactId.replace(/[^a-zA-Z0-9]/g, "")}`;
  const [svg, setSvg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const healedSource = useMemo(() => escapeMermaidSemicolons(source), [source]);

  useEffect(() => {
    let cancelled = false;
    setSvg(null);
    setError(null);
    mermaid
      .render(renderId, healedSource)
      .then((result) => {
        if (!cancelled) setSvg(result.svg);
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setError(extractMermaidError(e));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [healedSource, renderId]);

  if (error) {
    return (
      <div className="my-2 rounded border border-destructive/40 bg-destructive/5 p-2">
        <div className="mb-1 text-[10px] font-semibold text-destructive">
          Mermaid render error: {error}
        </div>
        <pre className="overflow-x-auto text-[11px] text-muted-foreground whitespace-pre">
          {source}
        </pre>
      </div>
    );
  }

  return (
    <div
      data-testid="mermaid-diagram"
      data-rendered={svg !== null ? "true" : "false"}
      className="my-2 overflow-x-auto rounded border bg-background p-2"
      dangerouslySetInnerHTML={{ __html: svg ?? "" }}
    />
  );
}

/** Raw / Rendered toggle button rendered in the top-right corner of the viewer. */
function RawToggleButton({ showRaw, onToggle }: { showRaw: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      onClick={onToggle}
      className="absolute top-2 right-2 z-10 rounded bg-muted/80 px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
    >
      {showRaw ? "Rendered" : "Raw"}
    </button>
  );
}

/**
 * Inline markdown viewer that renders inside existing content blocks.
 *
 * - Uses react-markdown + remark-gfm for safe, XSS-free rendering.
 * - Styles applied via Tailwind classes on component overrides (no @tailwindcss/typography).
 * - Includes a Raw / Rendered toggle button in the top-right corner (both "card" and "prose" modes).
 * - Images are rendered inline with graceful fallback on load errors.
 * - SVGs are rendered via <img> tags only (prevents embedded script execution).
 * - Internal links (e.g. /docs/slug) navigate via React Router pushState.
 * - External links (http/https) open in a new tab.
 * - "card" variant (default): embedded chrome with border, height cap, and toggle.
 * - "prose" variant: no border or height cap, but still includes the Raw/Rendered toggle.
 * - HTML comments are stripped from the rendered view (raw view still shows the original).
 * - `linkBase` resolves bare-slug links (e.g. "workflow-templates") to SPA routes.
 */
function MarkdownViewerInner({ content, maxHeight = "max-h-48", artifactContext, variant, linkBase }: MarkdownViewerProps) {
  const [showRaw, setShowRaw] = useState(false);

  // Strip HTML comments for the rendered view; the raw toggle still shows the original.
  const strippedContent = useMemo(() => stripHtmlComments(content), [content]);

  // Depend on individual fields so a new object reference with the same
  // values does not invalidate the memoisation.
  const markdownComponents = useMemo(
    () => buildMarkdownComponents(artifactContext, variant, linkBase),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [artifactContext?.runId, artifactContext?.execId, variant, linkBase]
  );

  if (!content) {
    return null;
  }

  // Prose mode: no border or height cap, but still includes the Raw/Rendered toggle.
  if (variant === "prose") {
    return (
      <div className="relative">
        <RawToggleButton showRaw={showRaw} onToggle={() => setShowRaw((prev) => !prev)} />

        {showRaw ? (
          <pre className="whitespace-pre-wrap text-sm">{content}</pre>
        ) : (
          <div className="prose-content">
            <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
              {strippedContent}
            </ReactMarkdown>
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="relative">
      <RawToggleButton showRaw={showRaw} onToggle={() => setShowRaw((prev) => !prev)} />

      {showRaw ? (
        <pre
          className={`${maxHeight} overflow-auto rounded-md border bg-muted/30 p-3 pr-16 text-xs whitespace-pre-wrap`}
        >
          {content}
        </pre>
      ) : (
        <div
          className={`${maxHeight} overflow-auto rounded-md border bg-muted/30 p-3 pr-16 text-xs`}
        >
          <ReactMarkdown
            remarkPlugins={[remarkGfm]}
            components={markdownComponents}
          >
            {strippedContent}
          </ReactMarkdown>
        </div>
      )}
    </div>
  );
}

/** Build Tailwind-styled component overrides for react-markdown.
 *  Heading and list sizes are variant-aware: prose mode uses document-scale
 *  typography; card mode uses compact sizes that fit the small viewer chrome. */
function buildMarkdownComponents(
  artifactContext?: ArtifactContext,
  variant?: "card" | "prose",
  linkBase?: string
): ComponentPropsWithoutRef<typeof ReactMarkdown>["components"] {
  const isProse = variant === "prose";

  return {
    // Headings — prose uses document-scale sizes; card uses compact sizes.
    h1: ({ children }) => (
      <h1 className="mb-2 mt-3 text-base font-bold first:mt-0">{children}</h1>
    ),
    h2: ({ children }) => (
      <h2 className={`mb-2 mt-3 ${isProse ? "text-2xl" : "text-sm"} font-bold first:mt-0`}>{children}</h2>
    ),
    h3: ({ children }) => (
      <h3 className={`mb-1 mt-2 ${isProse ? "text-xl" : "text-sm"} font-semibold first:mt-0`}>{children}</h3>
    ),
    h4: ({ children }) => (
      <h4 className={`mb-1 mt-2 ${isProse ? "text-lg" : "text-xs"} font-semibold first:mt-0`}>{children}</h4>
    ),

    // Block elements
    p: ({ children }) => <p className="mb-2 last:mb-0">{children}</p>,
    blockquote: ({ children }) => (
      <blockquote className="mb-2 border-l-2 border-muted-foreground/30 pl-3 italic text-muted-foreground">
        {children}
      </blockquote>
    ),
    hr: () => <hr className="my-3 border-border" />,

    // Lists — prose uses wider indentation for visual hierarchy; card uses compact.
    ul: ({ children }) => (
      <ul className={`mb-2 list-disc ${isProse ? "pl-6 space-y-1" : "pl-4"} last:mb-0`}>{children}</ul>
    ),
    ol: ({ children }) => (
      <ol className={`mb-2 list-decimal ${isProse ? "pl-6 space-y-1" : "pl-4"} last:mb-0`}>{children}</ol>
    ),
    li: ({ children }) => <li className="mb-0.5">{children}</li>,

    // Code
    code: ({ children, className }) => {
      // Fenced code blocks get a language className from react-markdown
      const isBlock = className?.startsWith("language-");
      if (isBlock) {
        const lang = className?.replace(/^language-/, "");
        if (lang === "mermaid") {
          return <MermaidDiagram source={String(children).replace(/\n$/, "")} />;
        }
        return (
          <code className="block overflow-x-auto rounded bg-muted/50 p-2 text-xs whitespace-pre">
            {children}
          </code>
        );
      }
      return (
        <code className="rounded bg-muted/50 px-1 py-0.5 text-[0.85em]">
          {children}
        </code>
      );
    },
    // For mermaid blocks the <code> override returns a top-level <div>; render
    // <pre> transparently in that case so we don't end up with a <pre> wrapping
    // a block element.
    pre: ({ children }) => {
      const child = Array.isArray(children) ? children[0] : children;
      if (
        child &&
        typeof child === "object" &&
        "type" in child &&
        (child as { type: unknown }).type === MermaidDiagram
      ) {
        return <>{children}</>;
      }
      return <pre className="mb-2 last:mb-0">{children}</pre>;
    },

    // Tables (GFM)
    table: ({ children }) => (
      <div className="mb-2 overflow-x-auto last:mb-0">
        <table className="w-full border-collapse text-xs">{children}</table>
      </div>
    ),
    thead: ({ children }) => (
      <thead className="border-b border-border">{children}</thead>
    ),
    tbody: ({ children }) => <tbody>{children}</tbody>,
    tr: ({ children }) => (
      <tr className="border-b border-border/50">{children}</tr>
    ),
    th: ({ children }) => (
      <th className="px-2 py-1 text-left font-semibold">{children}</th>
    ),
    td: ({ children }) => <td className="px-2 py-1">{children}</td>,

    // Links — classified into branches by href prefix.
    //
    // Branch order:
    //   1. Root-relative (/path, not //) → React Router Link (same-tab SPA nav)
    //   2. Anchor (#id)                  → plain <a> (same-page scroll)
    //   3. External (http/https//)       → new tab
    //   4. Bare slug with linkBase       → React Router Link resolved to linkBase/slug
    //   5. Everything else (mailto: etc) → native <a> (no target override)
    a: ({ href, children }) => {
      const h = href ?? "";
      if (h.startsWith("/") && !h.startsWith("//")) {
        // Root-relative internal route — SPA navigation, same tab.
        // The !startsWith("//") guard prevents protocol-relative URLs (//cdn.example.com)
        // from being routed as SPA paths.
        return <Link to={h} className="text-primary underline underline-offset-2 hover:text-primary/80">{children}</Link>;
      }
      if (h.startsWith("#")) {
        // Anchor — same-page scroll, no target
        return <a href={h} className="text-primary underline underline-offset-2 hover:text-primary/80">{children}</a>;
      }
      if (h.startsWith("http://") || h.startsWith("https://") || h.startsWith("//")) {
        // HTTP/HTTPS/protocol-relative external site — open in new tab
        return <a href={h} target="_blank" rel="noopener noreferrer" className="text-primary underline underline-offset-2 hover:text-primary/80">{children}</a>;
      }
      if (linkBase && !h.includes(":")) {
        // Bare slug (e.g. "workflow-templates") with a linkBase configured —
        // resolve to linkBase/slug via React Router so cross-doc links stay
        // in-app without a full page reload.
        return <Link to={`${linkBase}/${h}`} className="text-primary underline underline-offset-2 hover:text-primary/80">{children}</Link>;
      }
      // Other schemes (mailto:, tel:, etc.) — native browser handling, no target override
      return <a href={h} className="text-primary underline underline-offset-2 hover:text-primary/80">{children}</a>;
    },

    // Images → inline with fallback
    img: ({ src, alt }) => {
      let resolvedSrc = src ?? "";
      // Resolve relative paths to artifact URLs when context is provided
      if (resolvedSrc && !isAbsoluteUrl(resolvedSrc) && artifactContext) {
        resolvedSrc = artifactUrl(
          artifactContext.runId,
          artifactContext.execId,
          resolvedSrc
        );
      }
      return <ImageWithFallback src={resolvedSrc} alt={alt ?? ""} />;
    },

    // Inline styling
    strong: ({ children }) => <strong className="font-semibold">{children}</strong>,
    em: ({ children }) => <em className="italic">{children}</em>,
    del: ({ children }) => <del className="line-through">{children}</del>,

    // Task lists (GFM)
    input: ({ checked, ...props }) => (
      <input
        type="checkbox"
        checked={checked}
        readOnly
        className="mr-1 align-middle"
        {...props}
      />
    ),
  };
}

const MarkdownViewer = memo(MarkdownViewerInner);
MarkdownViewer.displayName = "MarkdownViewer";

export default MarkdownViewer;
