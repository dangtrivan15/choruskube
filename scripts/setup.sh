#!/usr/bin/env bash
# scripts/setup.sh — Generate this install's Worker Fleet token, then interactively
# configure credentials for a real ChorusKube run.
#
# Every invocation starts with ensure_worker_token: it generates WORKER_REGISTRATION_TOKEN
# into .env when the install has none (an existing non-empty value is kept), the value both
# docker-compose.yaml services require to start.
#   --worker-token-only     run that step alone — no credential prompts, no stack start.
#                            This is the non-interactive form unattended callers use
#                            (see scripts/oss-smoke.sh).
#   --rotate-worker-token   replace an existing token instead of keeping it. Combine
#                            with --worker-token-only for a non-interactive rotation.
# With neither flag, the token step is followed by the credential prompts below.
#
# Prompts one input at a time and writes them into the gitignored folder
# .secrets/credentials/, which docker-compose.yaml mounts read-only on the Worker
# (the api-server holds no credential). All credentials are OPTIONAL: the stack
# boots and serves the full API with none of them. The Claude token is needed to
# run AI agent nodes; a GitHub credential (PAT or App) is needed only to push /
# open PRs against a target repo.
#
# Layout written (the Worker's credentials folder):
#   claude-oauth-token
#   github-pat
#   github-app/app-id, github-app/installation-id, github-app/private-key.pem
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
CRED_DIR="$REPO_ROOT/.secrets/credentials"
ENV_FILE="$REPO_ROOT/.env"

usage() {
  cat >&2 <<'EOF'
Usage: setup.sh [--worker-token-only] [--rotate-worker-token]

With no arguments: generates this install's Worker Fleet token if it has none,
then walks through the optional Claude/GitHub credential prompts and offers to
start the stack.

  --worker-token-only     Generate (or rotate) the Worker's Fleet token only;
                           no credential prompts, no stack start.
  --rotate-worker-token   Replace an existing Worker Fleet token instead of
                           keeping it.
EOF
}

WORKER_TOKEN_ONLY=false
ROTATE_WORKER_TOKEN=false
for arg in "$@"; do
  case "$arg" in
    --worker-token-only) WORKER_TOKEN_ONLY=true ;;
    --rotate-worker-token) ROTATE_WORKER_TOKEN=true ;;
    *)
      usage
      exit 2
      ;;
  esac
done

# Credentials used to live in .env for the api-server; nothing reads those lines now.
# Only point at them: .env may hold the operator's other settings, and setup edits only
# its own WORKER_REGISTRATION_TOKEN line in it.
notice_stale_env() {
  if [[ -f "$ENV_FILE" ]] && grep -Eq '^[[:space:]]*(export[[:space:]]+)?(CLAUDE_CODE_OAUTH_TOKEN=|GITHUB_PAT=|GITHUB_APP_)' "$ENV_FILE"; then
    echo
    echo "Note: $ENV_FILE still has CLAUDE_CODE_OAUTH_TOKEN / GITHUB_PAT / GITHUB_APP_* lines."
    echo "      They are no longer read (credentials live in $CRED_DIR); you can delete them."
  fi
}

# Owns the single WORKER_REGISTRATION_TOKEN line of .env: generates it when the line is
# missing or holds no value (blank, or only quote characters — every unquoted form of that
# is already empty to compose, and a quoted one is not a token anyone set on purpose), keeps
# any other existing value unless $1 is "true", and never prints the value itself.
ensure_worker_token() {
  local rotate="$1"
  local line_re='^[[:space:]]*(export[[:space:]]+)?WORKER_REGISTRATION_TOKEN='
  local existing_line=""

  if [[ -f "$ENV_FILE" ]]; then
    existing_line="$(grep -E "$line_re" "$ENV_FILE" | tail -1 || true)"
  fi

  local status=""
  if [[ -n "$existing_line" ]]; then
    local value stripped
    value="${existing_line#*=}"
    stripped="$(printf '%s' "$value" | tr -d '[:space:]')"
    stripped="${stripped//\'/}"
    stripped="${stripped//\"/}"
    if [[ -n "$stripped" ]]; then
      if [[ "$rotate" != true ]]; then
        echo "Worker token: kept (WORKER_REGISTRATION_TOKEN in .env)"
        return 0
      fi
      status=rotated
    else
      status=generated
    fi
  else
    status=generated
  fi

  # 32 random bytes, hex-encoded without depending on openssl.
  local new_token new_line
  new_token="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"
  new_line="WORKER_REGISTRATION_TOKEN=${new_token}"

  if [[ -f "$ENV_FILE" ]]; then
    local tmp no_trailing_nl=false
    [[ -s "$ENV_FILE" ]] && [[ -n "$(tail -c1 "$ENV_FILE")" ]] && no_trailing_nl=true
    # cp -p (not a fresh redirect) so the temp file starts with the existing file's mode;
    # every edit below only truncates/appends it in place, which never resets that mode.
    tmp="$(mktemp "$ENV_FILE.XXXXXX")"
    cp -p "$ENV_FILE" "$tmp"

    if [[ -n "$existing_line" ]]; then
      # Rewrite through a second temp file rather than `sed -i` (no in-place flag on BSD
      # sed without a backup suffix): every other line is printed back unchanged, and
      # only the matched line is replaced.
      awk -v re="$line_re" -v repl="$new_line" '
        $0 ~ re { print repl; next }
        { print }
      ' "$tmp" > "$tmp.new"
      cat "$tmp.new" > "$tmp"
      rm -f "$tmp.new"
      # awk always terminates its last printed line; undo that one byte when the file's
      # true last line (unrelated to the match) had no final newline to begin with.
      if [[ "$no_trailing_nl" == true ]]; then
        truncate -s -1 "$tmp"
      fi
    else
      if [[ "$no_trailing_nl" == true ]]; then
        printf '\n' >> "$tmp"
      fi
      printf '%s\n' "$new_line" >> "$tmp"
    fi
    mv "$tmp" "$ENV_FILE"
  else
    (umask 077; printf '%s\n' "$new_line" > "$ENV_FILE")
  fi

  echo "Worker token: ${status} (WORKER_REGISTRATION_TOKEN in .env)"
}

ensure_worker_token "$ROTATE_WORKER_TOKEN"

if [[ "$WORKER_TOKEN_ONLY" == true ]]; then
  exit 0
fi

if [[ -d "$CRED_DIR" ]] && [[ -n "$(ls -A "$CRED_DIR" 2>/dev/null)" ]]; then
  read -r -p "$CRED_DIR already holds credentials. Overwrite them? [y/N] " ans
  if ! [[ "$ans" =~ ^[Yy]$ ]]; then
    echo "Keeping existing credentials."
    notice_stale_env
    exit 0
  fi
fi

CLAUDE_TOKEN="" ; PAT="" ; APP_ID="" ; APP_INSTALL="" ; PEM_SRC=""

# 1) Claude token
echo
echo "1) Claude Code OAuth token — required to run AI agent nodes."
if command -v claude >/dev/null 2>&1; then
  read -r -p "   Run 'claude setup-token' now to generate one? [y/N] " ans
  # '|| true' so a failed/cancelled setup-token falls through to the paste prompt
  # below instead of aborting the whole script under 'set -e'.
  if [[ "$ans" =~ ^[Yy]$ ]]; then
    CLAUDE_TOKEN="$(claude setup-token)" || true
  fi
fi
if [[ -z "$CLAUDE_TOKEN" ]]; then
  read -r -s -p "   Paste a Claude token (or leave blank to skip): " CLAUDE_TOKEN ; echo
fi

# 2) GitHub credential
echo
echo "2) GitHub credential — needed only to push / open PRs against a target repo."
echo "   [1] None   [2] Personal Access Token (PAT)   [3] GitHub App"
read -r -p "   Choose [1/2/3] (default 1): " choice
case "${choice:-1}" in
  2)
    read -r -s -p "   Paste GitHub PAT: " PAT ; echo
    ;;
  3)
    read -r -p "   GitHub App ID: " APP_ID
    read -r -p "   Installation ID: " APP_INSTALL
    read -r -p "   Path to App private-key .pem on this host: " PEM_SRC
    [[ -f "$PEM_SRC" ]] || { echo "   ERROR: no file at '$PEM_SRC'" >&2; exit 1; }
    ;;
  *) ;;
esac

# The Worker prefers github-pat over github-app/, so a file left over from the
# other choice would silently keep winning: start from an empty folder.
mkdir -p "$CRED_DIR"
chmod 700 "$REPO_ROOT/.secrets" "$CRED_DIR"
find "$CRED_DIR" -mindepth 1 -delete

# umask 077 so no credential is ever readable by others, even before its chmod.
write_secret() { (umask 077; printf '%s\n' "$2" > "$CRED_DIR/$1"); chmod 600 "$CRED_DIR/$1"; }

[[ -n "$CLAUDE_TOKEN" ]] && write_secret claude-oauth-token "$CLAUDE_TOKEN"
[[ -n "$PAT" ]]          && write_secret github-pat "$PAT"
if [[ -n "$PEM_SRC" ]]; then
  mkdir -p "$CRED_DIR/github-app" ; chmod 700 "$CRED_DIR/github-app"
  [[ -n "$APP_ID" ]]      && write_secret github-app/app-id "$APP_ID"
  [[ -n "$APP_INSTALL" ]] && write_secret github-app/installation-id "$APP_INSTALL"
  (umask 077; cp "$PEM_SRC" "$CRED_DIR/github-app/private-key.pem")
  chmod 600 "$CRED_DIR/github-app/private-key.pem"
fi

hint() { local s="$1"; [[ ${#s} -ge 4 ]] && printf '…%s' "${s: -4}" || printf '(set)'; }
echo
echo "Wrote $CRED_DIR (mounted read-only on the Worker):"
[[ -n "$CLAUDE_TOKEN" ]] && echo "  claude-oauth-token            $(hint "$CLAUDE_TOKEN")"
[[ -n "$PAT" ]]          && echo "  github-pat                    $(hint "$PAT")"
[[ -n "$APP_ID" ]]       && echo "  github-app/app-id             $APP_ID"
[[ -n "$APP_INSTALL" ]]  && echo "  github-app/installation-id    $APP_INSTALL"
[[ -n "$PEM_SRC" ]]      && echo "  github-app/private-key.pem    (copied from $PEM_SRC)"
[[ -z "$CLAUDE_TOKEN$PAT$PEM_SRC" ]] && echo "  (no credentials — the stack boots; AI nodes and GitHub steps will fail)"
notice_stale_env

echo
read -r -p "Start the stack now with ./scripts/up.sh? [Y/n] " ans
[[ "$ans" =~ ^[Nn]$ ]] || exec "$SCRIPTS_DIR/up.sh"
