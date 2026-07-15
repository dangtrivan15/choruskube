#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail() { echo "FAIL: $1" >&2; exit 1; }

# Case 1: unset -> no-op, no file, no GOPROXY
( unset DEP_PROXY_BASE GOPROXY
  tmp="$(mktemp -d)"
  source "$HERE/dep-proxy.sh"; apply_dep_proxy "$tmp"
  [ -z "${GOPROXY:-}" ] || fail "GOPROXY set when DEP_PROXY_BASE unset"
  [ ! -e "$tmp/init.d/dep-proxy.init.gradle" ] || fail "init script written when unset"
)

# Case 2: set -> exports + init script with the mirror URL
( export DEP_PROXY_BASE="http://proxy.example:8081"
  tmp="$(mktemp -d)"
  source "$HERE/dep-proxy.sh"; apply_dep_proxy "$tmp"
  [ "${GOPROXY:-}" = "http://proxy.example:8081/repository/go-proxy/,direct" ] || fail "GOPROXY wrong: ${GOPROXY:-}"
  [ "${GOSUMDB:-}" = "off" ] || fail "GOSUMDB not off"
  [ "${npm_config_registry:-}" = "http://proxy.example:8081/repository/npm-proxy/" ] || fail "npm registry wrong"
  grep -q "http://proxy.example:8081/repository/maven-central/" "$tmp/init.d/dep-proxy.init.gradle" || fail "gradle mirror missing"
)
echo "PASS"
