#!/usr/bin/env bash
# Fails when code cites a run-scoped spec identifier.
#
# `Decision N`, `Caveat N` and `§N.N` are ordinal within one run's spec. A
# comment outlives that run, so the next agent resolves the number against its
# own spec and gets a different answer. State the constraint instead; durable
# references (file path, type name, issue URL) are fine.
set -euo pipefail

cd "$(dirname "$0")/.."

# The leading (^|[^A-Za-z-]) keeps this off the CLI tool names `check-decision` and
# `list-decisions` — without it, a stderr-redirected call to the former false-matches
# on its trailing file descriptor digit.
PATTERN='(^|[^A-Za-z-])[Dd]ecisions? +#?[0-9]+|(^|[^A-Za-z-])[Cc]aveats? +#?[0-9]+|§ ?[0-9]+'

# BaseFeatureDevSeeder's prompt strings tell the design agent how to STRUCTURE a
# spec — naming `Decision N` and `§N` there is the format definition, not a
# citation. Every other file's mentions are ordinary comments.
ALLOWLIST='api-server/src/main/java/com/choruskube/core/config/BaseFeatureDevSeeder.java'

hits=$(grep -rnE "$PATTERN" \
         --include='*.java' --include='*.go' --include='*.ts' --include='*.tsx' \
         --include='*.sql' --include='*.sh' . 2>/dev/null \
       | grep -vE 'node_modules|/build/|/\.worktrees/|/\.gradle/|/dist/|/bin/' \
       | { [ -n "$ALLOWLIST" ] && grep -vF "$ALLOWLIST" || cat; } || true)

if [ -n "$hits" ]; then
  count=$(printf '%s\n' "$hits" | wc -l | tr -d ' ')
  {
    echo "check-comment-refs: $count run-scoped spec reference(s) found."
    echo
    printf '%s\n' "$hits"
    echo
    echo "A comment states its constraint; it does not cite a run's spec by ordinal."
    echo "See CLAUDE.md -> Code Comments."
  } >&2
  exit 1
fi

echo "check-comment-refs: clean"
