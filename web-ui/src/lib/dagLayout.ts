const statusToToken: Record<string, string> = {
  completed: "--status-success",
  running: "--status-info",
  awaiting_human: "--status-warning",
  live_chat: "--status-accent",
  failed: "--status-error",
  paused: "--status-accent",
  cancelled: "--status-neutral",
  pending: "--status-neutral",
};

let _cachedColors: Record<string, string> = {};
let _cachedDarkFlag: boolean | null = null;

/**
 * Resolve every known status CSS custom property (`--status-*`) to its
 * currently computed value, cached per light/dark mode. Exported (beyond
 * `getEdgeColor`'s own status-vocabulary lookup) so callers with a different
 * status-like vocabulary — e.g. RoadmapDependencyEdge's fixed "blocking"
 * color — can resolve a specific `--status-*` token themselves for an
 * SVG marker `color`, which (unlike an inline `style` property) is set as a
 * literal attribute by React Flow and does not reliably resolve `var(...)`.
 */
export function resolveStatusColors(): Record<string, string> {
  const isDark = document.documentElement.classList.contains("dark");
  if (_cachedDarkFlag === isDark && Object.keys(_cachedColors).length > 0) {
    return _cachedColors;
  }
  const style = getComputedStyle(document.documentElement);
  const colors: Record<string, string> = {};
  for (const token of new Set(Object.values(statusToToken))) {
    colors[token] = style.getPropertyValue(token).trim();
  }
  _cachedColors = colors;
  _cachedDarkFlag = isDark;
  return colors;
}

export function getEdgeColor(status: string): string {
  const token = statusToToken[status] ?? statusToToken.pending;
  return resolveStatusColors()[token];
}

/** A 2D position in graph coordinates. */
export interface NodePosition {
  x: number;
  y: number;
}
