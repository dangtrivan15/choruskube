# CLAUDE.md Management Skill

You have deep expertise in maintaining `CLAUDE.md` files for software projects.

## When managing CLAUDE.md files:

- Read the existing `CLAUDE.md` before making any changes to understand current conventions
- Keep the file under 300 lines — it is loaded into every agent's context window
- Structure: Quick Reference commands first, then language conventions, then architecture rules
- Do not duplicate content already in `CONTRIBUTING.md` — reference it instead
- When adding a new section, check whether it belongs in `CLAUDE.md` (agent-facing) or `CONTRIBUTING.md` (human-facing)
- After updating the root `CLAUDE.md`, check whether sub-package `CLAUDE.md` files need corresponding updates
- Use the existing heading hierarchy — do not introduce new heading levels
- Verify line count after changes: `wc -l CLAUDE.md` must be ≤ 300
