#!/usr/bin/env bash
# scripts/down.sh — Tear down the OSS ChorusKube stack and remove volumes.
#
# Usage:
#   ./scripts/down.sh               # down -v (wipes postgres/object storage volumes)
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
# Compose interpolates the whole file for every subcommand, teardown included, so the
# services' required WORKER_REGISTRATION_TOKEN still needs a value even though nothing
# started here reads it.
: "${WORKER_REGISTRATION_TOKEN:=down-teardown-placeholder}"
export WORKER_REGISTRATION_TOKEN
compose down -v "$@"
