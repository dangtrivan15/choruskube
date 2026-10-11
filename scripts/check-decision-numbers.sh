#!/usr/bin/env bash
# Fails when a decision record's name is not YYYY-MM-DD---NN-<slug>.md, or when two records share
# a date and number. Parallel branches each take the next free NN for a date, and git merges both
# files without a conflict because their names differ.
set -euo pipefail

# Run from a subdirectory, the pathspec below lists no records and the check passes unread.
cd "$(dirname "$0")/.."

# :(glob) stops * at a slash; without it, documents in subdirectories are checked as records.
names=$(git ls-files -- ':(glob)docs/decisions/*.md' | sed 's|.*/||' | grep -v -x 'README.md' || true)
pattern='^[0-9]{4}-[0-9]{2}-[0-9]{2}---[0-9]{2}-.+\.md$'
status=0

misnamed=$(printf '%s\n' "$names" | grep -v -E "$pattern" | grep -v '^$' || true)
if [ -n "$misnamed" ]; then
  echo "Decision records not named YYYY-MM-DD---NN-<slug>.md:" >&2
  printf '%s\n' "$misnamed" | sed 's|^|  docs/decisions/|' >&2
  status=1
fi

dupes=$(printf '%s\n' "$names" | grep -E "$pattern" | cut -c1-15 | sort | uniq -d || true)
if [ -n "$dupes" ]; then
  echo "Decision records share a date and number:" >&2
  for stem in $dupes; do
    git ls-files -- ":(glob)docs/decisions/$stem-*" | sed 's/^/  /' >&2
  done
  echo "Keep the one already on the base branch. git mv the other to the next free number for its" >&2
  echo "date and update every reference to its old filename (docs/decisions/README.md)." >&2
  status=1
fi

exit "$status"
