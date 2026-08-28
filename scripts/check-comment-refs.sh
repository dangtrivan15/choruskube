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

# A bare section sign is checked everywhere, code and prose alike. It usually cites
# a run's spec, not a document's own headings; the rare document that does is named
# in ALLOWLIST rather than carved out by extension.
SECTION='§ ?[0-9]+'

# BaseFeatureDevSeeder's prompt strings tell the design agent how to STRUCTURE a
# spec — naming `Decision N` and `§N` there is the format definition, not a
# citation. Every other file's mentions are ordinary comments.
ALLOWLIST='api-server/src/main/java/com/choruskube/core/config/BaseFeatureDevSeeder.java'

# `git ls-files` rather than `grep -r`: it enumerates every versioned file, so the
# walk reaches the extensionless `#!/bin/bash` scripts, the `.md` docs and the
# `.yaml` config an extension list never opens — and it prunes `node_modules`,
# `build/` and the composed `.staging` tree through .gitignore instead of
# descending them and filtering afterwards.
#
# `--others` keeps the not-yet-added files in scope, so a decision entry written
# but not yet committed is checked at the moment it is easiest to fix.
#
# `done < <(cmd)` never surfaces `cmd`'s exit status to this shell: a failing `git
# ls-files` would leave `files` empty and the scan below would report a clean tree
# it never read. A real file makes the listing an ordinary command `set -e` can catch.
list_file=$(mktemp)
trap 'rm -f "$list_file"' EXIT
git ls-files -z --cached --others --exclude-standard > "$list_file"

files=()
while IFS= read -r -d '' file; do
  case "$file" in
    # Applied migrations are checksum-frozen: Flyway CRC32s the raw file lines,
    # so a comment edit here fails every existing database at boot. The guard
    # must never ask for one.
    */db/migration*/*) continue ;;
    # A per-run planning archive *is* the spec, so its ordinals address itself.
    docs/plans/*|docs/progress/*) continue ;;
  esac
  # Regular files only, and the test is `-f` rather than `-e` for two reasons.
  #
  # A tracked path can be deleted in the working tree — staged for removal, or
  # swept by a build — and grep would exit 2 on it, tripping the fail-closed
  # check below over a file nobody can cite from.
  #
  # `--others` also emits a bare directory entry for a tree git will not descend,
  # notably a nested checkout: CI clones the sibling repo into `_choruskube/`, and
  # `grep: _choruskube/: Is a directory` is likewise exit 2. That sibling carries
  # its own copy of this guard, so skipping it here loses nothing.
  #
  # An unreadable file is still a regular file, so permission errors are unaffected.
  [ -f "$file" ] || continue
  files+=("$file")
done < "$list_file"

hits=''

# grep answers "nothing matched" with exit 1 and "I could not read something"
# with exit 2. Collapsing the two — the `2>/dev/null … || true` this replaces —
# reports a clean tree it never finished reading, so an unreadable file anywhere
# in the walk silently disarms the whole guard. Only exit 1 counts as clean.
scan() {
  local pattern=$1
  shift
  [ "$#" -gt 0 ] || return 0
  local out status=0 err
  err=$(mktemp)
  # `-d skip` is a second line of defence behind the `-f` filter above: a path can
  # still become a directory between enumeration and here.
  out=$(grep -HInE -d skip "$pattern" -- "$@" 2>"$err") || status=$?
  # A path enumerated a moment ago can be gone before grep opens it: a CI build
  # writes and sweeps untracked files while this runs. A file that no longer
  # exists cannot be cited from, so it is not a read this guard depends on.
  # Anything else grep could not read — permission denied, I/O error — is, and
  # still disarms the guard. An empty stderr with a hard exit counts as unknown.
  if [ "$status" -gt 1 ] && { [ ! -s "$err" ] || grep -qvE ': No such file or directory$' "$err"; }; then
    {
      echo
      cat "$err"
      echo
      echo "check-comment-refs: grep exited $status — the scan did not finish, so its"
      echo "silence proves nothing. Fix the read error above and re-run."
    } >&2
    rm -f "$err"
    exit "$status"
  fi
  rm -f "$err"
  [ -z "$out" ] || hits="${hits}${out}"$'\n'
  return 0
}

scan "$NAMED|$SECTION" ${files[@]+"${files[@]}"}

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
