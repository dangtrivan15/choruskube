#!/bin/bash
# Unit tests for normalize-lockfiles (no live container needed)
set -euo pipefail

PASS=0
FAIL=0
TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT

ok() { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL+1)); }

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/normalize-lockfiles"
PROXY="http://proxy.example:8081"

# Writes a lockfile whose single `resolved` URL is $1.
make_lock() {
  cat > "$2" <<EOF
{
  "name": "app",
  "lockfileVersion": 3,
  "packages": {
    "node_modules/pkg": {
      "version": "1.0.0",
      "resolved": "$1",
      "integrity": "sha512-abc"
    }
  }
}
EOF
}

# --- Test 1: proxy URL is rewritten to the public registry ---
LOCK="$TESTDIR/package-lock.json"
make_lock "${PROXY}/repository/npm-proxy/pkg/-/pkg-1.0.0.tgz" "$LOCK"
DEP_PROXY_BASE="$PROXY" "$SCRIPT" "$LOCK" >/dev/null
grep -q '"resolved": "https://registry.npmjs.org/pkg/-/pkg-1.0.0.tgz"' "$LOCK" \
  && ok "proxy URL rewritten to public registry" || fail "proxy URL rewritten to public registry"

# --- Test 2: no proxy host survives the rewrite ---
grep -q "proxy.example" "$LOCK" \
  && fail "proxy host fully removed" || ok "proxy host fully removed"

# --- Test 3: output stays valid JSON ---
python3 -c "import json,sys; json.load(open('$LOCK'))" 2>/dev/null \
  && ok "rewritten lockfile is valid JSON" || fail "rewritten lockfile is valid JSON"

# --- Test 4: no-op when DEP_PROXY_BASE is unset (a dev machine off the proxy) ---
LOCK2="$TESTDIR/unset-lock.json"
make_lock "${PROXY}/repository/npm-proxy/pkg/-/pkg-1.0.0.tgz" "$LOCK2"
BEFORE=$(cat "$LOCK2")
(unset DEP_PROXY_BASE; "$SCRIPT" "$LOCK2" >/dev/null)
[ "$BEFORE" = "$(cat "$LOCK2")" ] \
  && ok "no-op when DEP_PROXY_BASE unset" || fail "no-op when DEP_PROXY_BASE unset"

# --- Test 5: already-public URLs are left untouched ---
LOCK3="$TESTDIR/public-lock.json"
make_lock "https://registry.npmjs.org/pkg/-/pkg-1.0.0.tgz" "$LOCK3"
BEFORE3=$(cat "$LOCK3")
DEP_PROXY_BASE="$PROXY" "$SCRIPT" "$LOCK3" >/dev/null
[ "$BEFORE3" = "$(cat "$LOCK3")" ] \
  && ok "public URLs left untouched" || fail "public URLs left untouched"

# --- Test 6: a trailing slash on DEP_PROXY_BASE still matches ---
LOCK4="$TESTDIR/slash-lock.json"
make_lock "${PROXY}/repository/npm-proxy/pkg/-/pkg-1.0.0.tgz" "$LOCK4"
DEP_PROXY_BASE="${PROXY}/" "$SCRIPT" "$LOCK4" >/dev/null
grep -q "registry.npmjs.org" "$LOCK4" \
  && ok "trailing slash on DEP_PROXY_BASE handled" || fail "trailing slash on DEP_PROXY_BASE handled"

# --- Test 7: several lockfiles in one call (multi-repo run) ---
LOCK5="$TESTDIR/a-lock.json"; LOCK6="$TESTDIR/b-lock.json"
make_lock "${PROXY}/repository/npm-proxy/a/-/a-1.0.0.tgz" "$LOCK5"
make_lock "${PROXY}/repository/npm-proxy/b/-/b-1.0.0.tgz" "$LOCK6"
DEP_PROXY_BASE="$PROXY" "$SCRIPT" "$LOCK5" "$LOCK6" >/dev/null
{ grep -q "registry.npmjs.org" "$LOCK5" && grep -q "registry.npmjs.org" "$LOCK6"; } \
  && ok "multiple lockfiles rewritten" || fail "multiple lockfiles rewritten"

# --- Test 8: a missing path is skipped, not fatal (set -e must not trip) ---
DEP_PROXY_BASE="$PROXY" "$SCRIPT" "$TESTDIR/does-not-exist.json" >/dev/null 2>&1 \
  && ok "missing path skipped without error" || fail "missing path skipped without error"

# --- Test 9: regression — the scoped-package shape that leaked in 7f383d1 ---
LOCK7="$TESTDIR/scoped-lock.json"
make_lock "${PROXY}/repository/npm-proxy/@dnd-kit/utilities/-/utilities-3.2.2.tgz" "$LOCK7"
DEP_PROXY_BASE="$PROXY" "$SCRIPT" "$LOCK7" >/dev/null
grep -q '"resolved": "https://registry.npmjs.org/@dnd-kit/utilities/-/utilities-3.2.2.tgz"' "$LOCK7" \
  && ok "scoped package URL rewritten" || fail "scoped package URL rewritten"

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
