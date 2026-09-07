#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
# stub docker: record calls, simulate a reachable daemon and a successful load
cat > "$TMP/docker" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  info) exit 0 ;;
  load) echo "Loaded image: stub" ; exit 0 ;;
  *) exit 0 ;;
esac
EOF
chmod +x "$TMP/docker"; export PATH="$TMP:$PATH"

# case A: archive present -> load is invoked
mkdir -p "$TMP/preload"; : > "$TMP/preload/stack.tar"
PRELOAD_ARCHIVE="$TMP/preload/stack.tar" \
  bash -c 'source '"$DIR"'/../entrypoint.sh --preload-only 2>&1' | tee "$TMP/outA.log"
grep -q "preload: loaded" "$TMP/outA.log" || { echo "FAIL: archive present should load"; exit 1; }

# case B: archive absent -> no-op, no failure
PRELOAD_ARCHIVE="$TMP/preload/missing.tar" \
  bash -c 'source '"$DIR"'/../entrypoint.sh --preload-only 2>&1' | tee "$TMP/outB.log"
grep -q "preload: none" "$TMP/outB.log" || { echo "FAIL: absent archive should be a no-op"; exit 1; }

echo "PASS"
