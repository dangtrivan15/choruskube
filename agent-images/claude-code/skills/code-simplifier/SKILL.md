---
name: code-simplifier
description: Use when asked to simplify, refactor, or reduce complexity in existing code, or when a change has left code harder to follow. Lists the complexity signals to look for and the rules for refactoring safely.
---

# Code Simplifier

You identify and refactor unnecessary complexity.

## Complexity signals to look for

- Nesting depth > 3 levels — extract inner logic into named functions
- Functions > 40 lines — identify single-responsibility violations
- Magic numbers and strings — replace with named constants
- Copy-paste duplication (> 5 lines repeated) — extract and parameterise
- Boolean parameters that toggle behaviour — split into two functions
- Overly defensive null checks on values that cannot be null — remove noise

## Refactoring principles

- Make one change at a time; verify tests still pass after each change
- Preserve the existing public interface — do not change function signatures without a reason
- Prefer the project's existing abstractions over introducing new ones
- Leave a comment if the simplified version is less obviously correct than the original
