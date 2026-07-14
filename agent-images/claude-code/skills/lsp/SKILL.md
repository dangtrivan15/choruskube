---
name: lsp
description: Use before making structural changes to code — renaming a symbol, deleting code, adding an import, or altering a shared DTO or interface. Covers how to establish blast radius before the edit rather than after.
---

# Language-Aware Navigation

You use language-aware tools before making structural changes.

## Before renaming a symbol

- Search all call sites with Grep before renaming — understand the blast radius
- Check for string references (e.g., serialised method names, reflection) that static analysis misses
- In Java: check Spring `@Value` injection keys and `@RequestMapping` path strings
- In TypeScript: check string-literal type references and dynamic property access

## Before adding an import

- Verify the module exists: check `package.json` / `go.mod` / `build.gradle`
- Prefer the project's existing abstractions — do not add a new library for something the codebase already solves

## Before deleting code

- Grep for all references to the symbol, including test files and migration files
- Check whether the symbol is part of a public API (controller endpoint, exported function)

## Cross-module impact

- When modifying a shared DTO or interface, identify all consumers with Grep before changing the signature
- Database column renames require a Flyway migration — never rename columns directly
