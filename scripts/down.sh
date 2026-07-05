#!/usr/bin/env bash
# scripts/down.sh — Tear down the OSS ChorusKube stack and remove volumes.
#
# Usage:
#   ./scripts/down.sh               # down -v (wipes postgres/object storage volumes)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec docker compose -f "$SCRIPT_DIR/../docker-compose.yaml" down -v "$@"
