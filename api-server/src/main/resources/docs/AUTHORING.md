# Authoring Docs

## Conventions

**Do NOT add a `# H1` heading** to any Markdown file in this directory.

Each page's title is sourced from `index.json` and rendered as `<h1>` by the web UI.
Starting your Markdown with `# Title` creates a duplicate heading.

Start content at `##` (section level) instead:

```markdown
## Overview

Welcome to ChorusKube...

## Getting Started

...
```

## Callouts

Use GitHub alert syntax for a point the reader must not miss. The marker sits alone on the
quote's first line; the kinds are `NOTE`, `TIP`, `IMPORTANT`, `WARNING` and `CAUTION`:

```markdown
> [!WARNING]
> Approving this gate merges every registered pull request.
```

## File Naming

- File names must match the `slug` field in `index.json`
- Use lowercase letters and hyphens only (e.g. `getting-started.md`)
- No spaces, underscores, or uppercase letters

## Adding a New Page

1. Create a new `.md` file following the naming convention above
2. Add an entry to `index.json` with the correct `slug`, `title`, and `order`
3. Both changes must be committed together
