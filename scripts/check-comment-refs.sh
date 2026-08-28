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
NAMED='(^|[^A-Za-z-])[Dd]ecisions? +#?[0-9]+|(^|[^A-Za-z-])[Cc]aveats? +#?[0-9]+'

# A bare section sign is checked in code only. In a comment it can only point
# outside the file, at a run's spec; in a document it almost always points at
# that document's own numbered headings, which is a durable reference.
SECTION='§ ?[0-9]+'

# BaseFeatureDevSeeder's prompt strings tell the design agent how to STRUCTURE a
# spec — naming `Decision N` and `§N` there is the format definition, not a
# citation. Every other file's mentions are ordinary comments.
ALLOWLIST='api-server/src/main/java/com/choruskube/core/config/BaseFeatureDevSeeder.java'

# `git ls-files` rather than `grep -r`: it enumerates every versioned file plus
# the not-yet-added ones, so the walk reaches the extensionless `#!/bin/bash`
# scripts, the `.md` docs and the `.yaml` config an extension list never opens —
# and it prunes `node_modules`, `build/` and the composed `.staging` tree
# through .gitignore instead of descending them and filtering afterwards.
code=()
prose=()
while IFS= read -r -d '' file; do
  case "$file" in
    # Applied migrations are checksum-frozen: Flyway CRC32s the raw file lines,
    # so a comment edit here fails every existing database at boot. The guard
    # must never ask for one.
    */db/migration*/*) continue ;;
    # A per-run planning archive *is* the spec, so its ordinals address itself.
    docs/plans/*|docs/progress/*) continue ;;
  esac
  case "$file" in
    *.md) prose+=("$file") ;;
    *) code+=("$file") ;;
  esac
done < <(git ls-files -z --cached --others --exclude-standard)

hits=''

# grep answers "nothing matched" with exit 1 and "I could not read something"
# with exit 2. Collapsing the two — the `2>/dev/null … || true` this replaces —
# reports a clean tree it never finished reading, so an unreadable file anywhere
# in the walk silently disarms the whole guard. Only exit 1 counts as clean.
scan() {
  local pattern=$1
  shift
  [ "$#" -gt 0 ] || return 0
  local out status=0
  out=$(grep -HInE "$pattern" -- "$@") || status=$?
  if [ "$status" -gt 1 ]; then
    {
      echo
      echo "check-comment-refs: grep exited $status — the scan did not finish, so its"
      echo "silence proves nothing. Fix the read error above and re-run."
    } >&2
    exit "$status"
  fi
  [ -z "$out" ] || hits="${hits}${out}"$'\n'
  return 0
}

scan "$NAMED|$SECTION" ${code[@]+"${code[@]}"}
scan "$NAMED" ${prose[@]+"${prose[@]}"}

hits=${hits%$'\n'}
if [ -n "$hits" ] && [ -n "$ALLOWLIST" ]; then
  hits=$(printf '%s\n' "$hits" | grep -vF "$ALLOWLIST" || true)
fi

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
