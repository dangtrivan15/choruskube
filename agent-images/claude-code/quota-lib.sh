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

# redact_transcript <src> <dst>
#
# Writes a redacted copy of a Claude session transcript. The transcript is a
# verbatim record of everything the agent read, so it can contain credentials the
# agent printed: fetch-github-token is on PATH, and an agent that runs `env`
# captures CLAUDE_CODE_OAUTH_TOKEN, which is a roughly year-long org credential.
#
# Exact value substitution for the two tokens we hold, plus a shape scrub for
# GitHub tokens we do not. Byte substitution cannot corrupt the JSONL framing
# because these tokens are [A-Za-z0-9_-] only and are therefore never
# JSON-escaped — which would not hold for, say, a PEM body with newlines. The
# same alphabet is what makes interpolating the value into the sed program safe:
# it contains neither the `|` delimiter nor any BRE metacharacter, so a token
# can neither terminate the s/// command early nor be reinterpreted as a pattern.
#
# The sed program is written to a private file rather than passed as arguments,
# because a secret in argv is visible in `ps` to anything sharing the pod's PID
# namespace.
redact_transcript() {
    local src="$1" dst="$2"
    local script
    script=$(umask 077 && mktemp)
    # shellcheck disable=SC2064
    trap "rm -f '$script'" RETURN

    if [ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
        printf 's|%s|[redacted-oauth-token]|g\n' "$CLAUDE_CODE_OAUTH_TOKEN" >> "$script"
    fi
    if [ -n "${GITHUB_TOKEN_FOR_REDACTION:-}" ]; then
        printf 's|%s|[redacted-github-token]|g\n' "$GITHUB_TOKEN_FOR_REDACTION" >> "$script"
    fi
    # Defence in depth for credentials we never held.
    cat >> "$script" <<'SEDRULES'
s|gh[psuro]_[A-Za-z0-9]\{16,\}|[redacted-github-token]|g
s|github_pat_[A-Za-z0-9_]\{20,\}|[redacted-github-token]|g
SEDRULES

    sed -f "$script" "$src" > "$dst" || { rm -f "$script" "$dst"; return 1; }
    rm -f "$script"
}
