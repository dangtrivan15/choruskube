import type { Blockquote, Root } from "mdast";

const ALERT_KINDS = ["note", "tip", "important", "warning", "caution"] as const;

export type AlertKind = (typeof ALERT_KINDS)[number];

export function isAlertKind(value: unknown): value is AlertKind {
  return typeof value === "string" && (ALERT_KINDS as readonly string[]).includes(value);
}

const ALERT_MARKER = /^\[!(note|tip|important|warning|caution)\][ \t]*(\n|$)/i;

/**
 * remark plugin for GitHub alert syntax (`> [!NOTE]`): removes the marker and tags the
 * blockquote with `data-alert="<kind>"` for the renderer to style.
 *
 * Matches GitHub's rules — the marker must be alone on the quote's first line, and only a
 * top-level quote qualifies — because the same markdown is also published to GitHub, and a
 * looser match here would show a callout that GitHub renders as a plain quote.
 */
export function remarkAlerts() {
  return (tree: Root) => {
    for (const node of tree.children) {
      if (node.type === "blockquote") tagAlert(node);
    }
  };
}

function tagAlert(quote: Blockquote): void {
  const first = quote.children[0];
  if (first?.type !== "paragraph") return;
  const head = first.children[0];
  if (head?.type !== "text") return;
  const match = ALERT_MARKER.exec(head.value);
  if (!match) return;

  // A marker that ends its text node is followed on the same line by whatever inline node
  // comes next — unless that node is a hard break.
  const endsWithNewline = match[2] === "\n";
  const next = first.children[1];
  if (!endsWithNewline && next && next.type !== "break") return;

  const body = head.value.slice(match[0].length);
  if (body) {
    head.value = body;
  } else {
    first.children.shift();
    if (first.children[0]?.type === "break") first.children.shift();
  }
  if (first.children.length === 0) quote.children.shift();

  quote.data = {
    ...quote.data,
    hProperties: { ...quote.data?.hProperties, dataAlert: match[1].toLowerCase() },
  };
}
