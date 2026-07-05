import type { AuthContextType } from "@/components/AuthProvider";
import type { RunSummary } from "@/lib/types";
import type { NavContext } from "@/extensions";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export type CommandCategory = "navigation" | "actions" | "runs";

export interface Command {
  id: string;
  label: string;
  category: CommandCategory;
  shortcut?: string;
  /** Optional keywords that boost search relevance but are not displayed */
  keywords?: string[];
  /** Predicate against the auth context; omitted = always visible. */
  visibleWhen?: (auth: AuthContextType) => boolean;
  /**
   * Optional explicit navigation target for commands that don't follow the `nav:X → /X`
   * convention (e.g. an injected per-org link). Receives runtime context so a statically
   * injected command can produce a per-org path. Returning undefined is a no-op.
   */
  resolvePath?: (ctx: NavContext) => string | undefined;
}

// ---------------------------------------------------------------------------
// Static command registry — action callbacks are attached at mount time
// ---------------------------------------------------------------------------

export const staticCommands: Command[] = [
  {
    id: "nav:runs",
    label: "Go to Runs",
    category: "navigation",
    shortcut: "g r",
    keywords: ["workflow", "pipeline"],
  },
  {
    id: "nav:approvals",
    label: "Go to Approvals",
    category: "navigation",
    shortcut: "g a",
    keywords: ["gates", "pending", "review"],
  },
  {
    id: "nav:templates",
    label: "Go to Templates",
    category: "navigation",
    shortcut: "g t",
    keywords: ["graph"],
  },
  {
    id: "nav:analytics",
    label: "Go to Analytics",
    category: "navigation",
    shortcut: "g n",
    keywords: ["dashboard", "metrics", "trends", "bottleneck"],
  },
  {
    id: "nav:roadmap",
    label: "Go to Roadmap",
    category: "navigation",
    shortcut: "g m",
    keywords: ["proposals", "features"],
  },
  {
    id: "nav:git-repos",
    label: "Go to Software Projects",
    category: "navigation",
    shortcut: "g g",
    keywords: ["repository", "repositories", "github", "source", "git", "repos", "groups"],
  },
  {
    id: "action:toggle-theme",
    label: "Toggle Theme",
    category: "actions",
    shortcut: "t t",
    keywords: ["dark", "light", "mode"],
  },
  {
    id: "action:command-palette",
    label: "Open Command Palette",
    category: "actions",
    shortcut: "Ctrl+K",
  },
  {
    id: "action:shortcuts-help",
    label: "Show Keyboard Shortcuts",
    category: "actions",
    shortcut: "?",
    keywords: ["help", "keys", "hotkeys"],
  },
];

// ---------------------------------------------------------------------------
// Dynamic run commands — built from TanStack Query cache data
// ---------------------------------------------------------------------------

const MAX_RUN_COMMANDS = 50;

export function buildRunCommands(runs: RunSummary[] | undefined): Command[] {
  if (!runs || runs.length === 0) return [];

  return runs.slice(0, MAX_RUN_COMMANDS).map((run) => ({
    id: `run:${run.id}`,
    label: run.name ?? `Run ${run.id.slice(0, 8)}`,
    category: "runs" as CommandCategory,
    keywords: [run.templateName, run.status, run.id],
  }));
}

// ---------------------------------------------------------------------------
// Fuzzy search scoring
// ---------------------------------------------------------------------------

/**
 * Scores how well `query` matches a `Command`.
 * Returns 0 for no match, higher = better match.
 *
 * Scoring rules:
 *   - Exact prefix match on label → highest score (100)
 *   - Label contains query        → 80
 *   - Word-start match on label   → 70
 *   - Keyword match               → 50
 *   - Subsequence match on label  → 30
 *   - No match                    → 0
 */
export function scoreCommand(command: Command, query: string): number {
  if (!query) return 1; // everything matches empty query

  const q = query.toLowerCase();
  const label = command.label.toLowerCase();

  // Exact prefix
  if (label.startsWith(q)) return 100;

  // Contains
  if (label.includes(q)) return 80;

  // Word-start match: each word's first letter matches query chars in order
  const words = label.split(/\s+/);
  const initials = words.map((w) => w[0]).join("");
  if (initials.includes(q)) return 70;

  // Keyword match
  if (command.keywords?.some((kw) => kw.toLowerCase().includes(q))) return 50;

  // Subsequence match on label
  if (isSubsequence(q, label)) return 30;

  return 0;
}

function isSubsequence(needle: string, haystack: string): boolean {
  let ni = 0;
  for (let hi = 0; hi < haystack.length && ni < needle.length; hi++) {
    if (haystack[hi] === needle[ni]) ni++;
  }
  return ni === needle.length;
}

// ---------------------------------------------------------------------------
// Search — returns scored and sorted commands
// ---------------------------------------------------------------------------

export function searchCommands(
  commands: Command[],
  query: string
): Command[] {
  if (!query.trim()) return commands;

  return commands
    .map((cmd) => ({ cmd, score: scoreCommand(cmd, query.trim()) }))
    .filter(({ score }) => score > 0)
    .sort((a, b) => b.score - a.score)
    .map(({ cmd }) => cmd);
}

// ---------------------------------------------------------------------------
// Grouping helper
// ---------------------------------------------------------------------------

const categoryLabels: Record<CommandCategory, string> = {
  navigation: "Navigation",
  actions: "Actions",
  runs: "Recent Runs",
};

export interface CommandGroup {
  category: CommandCategory;
  label: string;
  commands: Command[];
}

export function groupCommands(commands: Command[]): CommandGroup[] {
  const order: CommandCategory[] = ["navigation", "actions", "runs"];
  const map = new Map<CommandCategory, Command[]>();

  for (const cmd of commands) {
    const existing = map.get(cmd.category);
    if (existing) {
      existing.push(cmd);
    } else {
      map.set(cmd.category, [cmd]);
    }
  }

  return order
    .filter((cat) => map.has(cat))
    .map((cat) => ({
      category: cat,
      label: categoryLabels[cat],
      commands: map.get(cat)!,
    }));
}
