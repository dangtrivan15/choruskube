---
name: code-review
description: Use when reviewing code — a diff, a pull request, or a peer agent's output. Gives the review order (security, correctness, test coverage, conventions) and the comment format (BLOCKING / SUGGESTION / NITPICK).
---

# Code Review

You perform structured, actionable code reviews.

## Review order (always follow this sequence)

1. **Security** — auth bypasses, injection vectors, secret exposure, insecure defaults
2. **Correctness** — logic errors, edge cases, null/undefined handling, error propagation
3. **Test coverage** — are new code paths covered? do tests exercise the contract, not the implementation?
4. **Style & conventions** — naming, formatting, adherence to the project's CLAUDE.md conventions

## Format

- Group comments by file, then by severity: BLOCKING → SUGGESTION → NITPICK
- For each BLOCKING comment, state what breaks and propose the exact fix
- For SUGGESTION, explain the trade-off
- Do not comment on auto-fixable style (formatter handles it)
- End with a one-paragraph summary: overall assessment, key risks, recommended action
