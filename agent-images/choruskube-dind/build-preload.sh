#!/usr/bin/env bash
# Pulls the e2e infra stack and `docker save`s it to a portable tar for baking into
# the choruskube-dind image (see Dockerfile's PRELOAD_TAR). Run against a reachable
# docker daemon with network access; not part of the image build itself.
set -euo pipefail

STACK_LIST="${STACK_LIST:-$(dirname "$0")/preload-stack.txt}"
OUT="${OUT:-stack.tar}"

imgs=()
while IFS= read -r ref; do
  [ -z "$ref" ] || case "$ref" in \#*) ;; *) docker pull -q "$ref"; imgs+=("$ref") ;; esac
done < "$STACK_LIST"

docker save "${imgs[@]}" -o "$OUT"
echo "preload: saved ${#imgs[@]} images to $OUT ($(wc -c <"$OUT") bytes)"
