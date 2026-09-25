# Redundant, non-color severity encoding for execution logs, and always-fetch log retrieval

## Status

current

## Context

The execution log viewer (`ExecutionLogs.tsx`) carried a log entry's severity
(info/warn/error) through text color alone, via an inline `levelStyles` map. Measured
against the log panel's surface, warn in light mode is about 2.0:1 contrast — below
even the 3:1 non-text bar — and info in dark mode is about 3.2:1, clearing 3:1 but not
the 4.5:1 text bar. [2026-09-24---01-status-tone-vocabulary.md](2026-09-24---01-status-tone-vocabulary.md)
settled the `--status-*` token values and the app's status-indicator conventions; a
downstream accessibility effort, not this change, owns retuning those hues. So the
weak cases needed a fix that does not touch token values.

Separately, the run-detail panel disables the node-logs query (`useNodeLogs`'s third
argument) for any node that is not running. The orchestrator writes a failed node's
warn entry (pod-log tail) and error entry ("Node failed: …") only *after* it flips
the node to `failed`, so a failed node's panel showed "No logs available." — exactly
the entries a severity fix would need to be visible for.

## Decision

**Severity encoding.** `src/lib/logLevelStyles.ts` exports `logLevelStyle(level)`,
mapping info/warn/error (case-insensitive, with a neutral fallback for anything else)
to `{ Icon, text, weight, border, row }` — a distinct icon shape, the existing
`text-status-*` color, a label font-weight step, a left accent border and, for warn
and error only, a row background tint. Icon shape, label weight and row tint are
channels that stay distinguishable even where a `text-status-*` hue is weak in one
theme, so they carry the "tell severities apart" guarantee that color alone could not.
`ExecutionLogs.tsx` renders these per row and keeps the message body in the default
foreground color rather than the severity color — the same "tone carries the
indicator, not the sentence" rule
[2026-09-24---01-status-tone-vocabulary.md](2026-09-24---01-status-tone-vocabulary.md)
established for `StatusCallout`, applied here to a log line's message.
`logLevelStyle` is a sibling to `statusColors.ts` / `priorityMeta.ts` rather than a
branch of either: log level is a different domain from run/node status, and its
return shape (icon + color + weight + border + row tint) differs from both.

**Log retrieval.** `useNodeLogs`'s third parameter is renamed `live` and now only
gates polling. The query's `enabled` condition drops the `enabled` (now `live`) flag
and fires whenever a node execution id is present; `refetchInterval` stays gated on
`live`. A finished node therefore fetches its logs once on mount instead of never,
and refreshes only through the run subscription's existing invalidation of
`["runs", runId, …]` — which already fires on every run event, including the one
published when a log entry is written — so no new polling is introduced for finished
nodes.

## Alternatives considered

- **Add dedicated log-severity foreground tokens, or re-tune the six `--status-*`
  hexes.** Both were rejected in favor of the existing tokens: either would collide
  with the palette-settling and accessible-contrast efforts that bracket this change.
- **Render each level as a filled, saturated badge instead of redundant channels.**
  Needs a foreground color that reads on its own fill in both themes (warn's fill is
  light, info's dark), which reintroduces the same token problem, and reads heavy in
  a dense log panel.
- **Prove the severity styling only on a running node**, or **keep polling finished
  nodes every 3s**, instead of changing the hook's fetch condition. The former ships
  a treatment operators would rarely see on the warn/error lines that need it most;
  the latter reintroduces polling for data that only changes through events the UI
  already subscribes to.

## Consequences

A log level's icon/color/weight/border/tint has exactly one place to change, tested
by `logLevelStyles.test.ts` (per-level values, case-insensitivity, neutral fallback,
pairwise distinctness) and guarded against a raw palette or hex literal by
`screen-token-hygiene.test.ts`. Selecting a finished node now costs one extra GET
per node execution (cached by TanStack Query per query key), and a finished node's
panel now shows entries — including a multi-line pod-log tail — it used to hide; that
tail still renders with collapsed whitespace, a pre-existing rendering gap unrelated
to severity. The severity hues themselves are unchanged: warn in light and info in
dark stay below full WCAG contrast until the accessible-contrast effort retunes
`--status-warning` (light) and `--status-info` (dark).
