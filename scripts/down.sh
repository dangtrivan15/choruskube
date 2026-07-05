#!/usr/bin/env bash
# scripts/down.sh — Tear down the OSS ChorusKube stack and remove volumes.
#
# Usage:
#   ./scripts/down.sh               # down -v (wipes postgres/object storage volumes)
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
compose down -v "$@"
