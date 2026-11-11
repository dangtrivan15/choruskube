#!/usr/bin/env bash
# scripts/setup.sh — Interactively configure credentials for a real ChorusKube run.
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

# Credentials used to live in .env for the api-server; nothing reads those lines now.
# Only point at them: .env may hold the operator's other settings, so it is never edited here.
notice_stale_env() {
  if [[ -f "$ENV_FILE" ]] && grep -Eq '^[[:space:]]*(export[[:space:]]+)?(CLAUDE_CODE_OAUTH_TOKEN=|GITHUB_PAT=|GITHUB_APP_)' "$ENV_FILE"; then
    echo
    echo "Note: $ENV_FILE still has CLAUDE_CODE_OAUTH_TOKEN / GITHUB_PAT / GITHUB_APP_* lines."
    echo "      They are no longer read (credentials live in $CRED_DIR); you can delete them."
  fi
}

if [[ -d "$CRED_DIR" ]] && [[ -n "$(ls -A "$CRED_DIR" 2>/dev/null)" ]]; then
  read -r -p "$CRED_DIR already holds credentials. Overwrite them? [y/N] " ans
  if ! [[ "$ans" =~ ^[Yy]$ ]]; then
    echo "Keeping existing credentials. Nothing changed."
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
