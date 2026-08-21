#!/bin/bash
# quota-lib.sh — pure helpers for handling Claude quota exhaustion.
#
# Sourced by entrypoint.sh and by test/test-quota-park.sh. Everything here is a
# pure function over its arguments so the tests need no container and no network.
#
# Not executable on its own; there is no main.
#
# No `set -euo pipefail` here, deliberately: this file is sourced, and shell
# options set in a sourced file apply to the caller's shell. Callers set their
# own options — entrypoint.sh sets them at the top and uses `set +e` in places
# where it needs them off.

# The longest park we will ever schedule. A parse bug or an upstream change to
# the message format must degrade to the node's existing failure path, never to
# an unbounded wait.
QUOTA_MAX_PARK_SECONDS=21600

# quota_reset_at <result-text> [now-epoch]
#
# Echoes the reset instant in epoch seconds and returns 0 when the text is a
# quota message whose reset time is parseable, labelled UTC, and within
# QUOTA_MAX_PARK_SECONDS. Returns 1 and echoes nothing otherwise.
#
# The message carries no epoch — only a wall-clock time and a timezone label,
# e.g. "resets 3:40pm (UTC)". The label is read rather than assumed: every
# observed message says UTC because the pod's TZ is UTC, but a pod with a
# different TZ would emit a different label, and parsing that as UTC would
# silently shift the wake time.
#
# Arithmetic is done on epoch seconds rather than with `date -d` so the function
# behaves identically under GNU and BSD date.
quota_reset_at() {
    local text="$1"
    local now="${2:-$(date -u +%s)}"

    case "$text" in
        *"hit your session limit"*) ;;
        *) return 1 ;;
    esac

    local hour minute meridiem tz
    if [[ "$text" =~ resets[[:space:]]+([0-9]{1,2}):([0-9]{2})(am|pm)[[:space:]]*\(([A-Za-z]+)\) ]]; then
        hour="${BASH_REMATCH[1]}"
        minute="${BASH_REMATCH[2]}"
        meridiem="${BASH_REMATCH[3]}"
        tz="${BASH_REMATCH[4]}"
    elif [[ "$text" =~ resets[[:space:]]+([0-9]{1,2})(am|pm)[[:space:]]*\(([A-Za-z]+)\) ]]; then
        hour="${BASH_REMATCH[1]}"
        minute="00"
        meridiem="${BASH_REMATCH[2]}"
        tz="${BASH_REMATCH[3]}"
    else
        return 1
    fi

    [ "$tz" = "UTC" ] || return 1

    # 12-hour to 24-hour. 12am is 00, 12pm is 12; every other pm adds 12.
    hour=$((10#$hour))
    minute=$((10#$minute))
    [ "$hour" -le 12 ] || return 1
    [ "$minute" -le 59 ] || return 1
    if [ "$meridiem" = "pm" ] && [ "$hour" -ne 12 ]; then
        hour=$((hour + 12))
    elif [ "$meridiem" = "am" ] && [ "$hour" -eq 12 ]; then
        hour=0
    fi

    local midnight target
    midnight=$((now - (now % 86400)))
    target=$((midnight + hour * 3600 + minute * 60))
    # A time at or before now means the reset is tomorrow.
    [ "$target" -gt "$now" ] || target=$((target + 86400))

    [ $((target - now)) -le "$QUOTA_MAX_PARK_SECONDS" ] || return 1

    echo "$target"
}
