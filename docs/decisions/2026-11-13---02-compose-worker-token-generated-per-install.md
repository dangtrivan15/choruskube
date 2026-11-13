# Each compose install generates its own Worker Fleet token

**Status:** current

## Context

The compose stack gave the api-server and the Worker a shared Fleet token through a
compose default published in the repository. Every install that never set its own
token ran with that published value, so anyone who could reach its api-server could
register a Worker and take its runs — and nothing ever told an operator to set one.
Removing the default outright would also break the published-image smoke test, which
has no interactive terminal to answer a credential prompt.

## Decision

**The token lives in one setup-owned line of the environment file, read as a required
compose variable.** `WORKER_REGISTRATION_TOKEN` in the repository root's gitignored
`.env` is interpolated as `${WORKER_REGISTRATION_TOKEN:?...}` in both the api-server's
`WORKER_REGISTRATION_TOKEN` and the Worker's `FLEET_TOKEN`, so the two can never diverge
and no default remains in the repository. `.env` is also where the compose file already
told operators to set their own token, so an install that did keeps working unchanged. A
mounted file each service reads was rejected: it would need a new file setting in both
binaries, and a bare `docker compose up` would still start the api-server before the
Worker refused. A second env file under the secrets folder was rejected too: compose's
missing-file error would name a path, not the setup command, and the two services would
need two separate lines that could drift against each other.

**The refusal is compose's own required-variable interpolation**, which covers a
scripted start and a bare `docker compose up` with the same mechanism and the same
message, before anything is built or started. `scripts/up.sh` runs `compose config
--quiet` right after sourcing its shared helpers and before building the agent images
(which are not compose services and can take minutes on a cold cache), so the refusal
always lands before that build. `scripts/down.sh` supplies a throwaway placeholder value
to its own compose call when the environment has none, so an install with no token can
still be torn down. A start-script-only check was rejected: it would leave a bare compose
start silently booting a Worker whose registration then fails with no mention of setup.
Moving the refusal into the api-server's own boot was rejected too: it would change the
server's fail-closed behavior for every other deployment of it, and only compose sees
every way this stack can be started.

**Setup owns one token step, gated by two flags: `--worker-token-only` and
`--rotate-worker-token`.** The step runs before the existing credential prompts, so
answering "no" to overwriting credentials still leaves an install with a generated
token. A re-run keeps an existing non-empty token unless `--rotate-worker-token` is
passed; a blank, empty-quoted, or missing value is always treated as absent and
generated regardless of the flag. `--worker-token-only` runs the step alone, with no
credential prompts and no offer to start the stack — the non-interactive form the
published-image smoke test (`scripts/oss-smoke.sh`) uses before booting. Each run
reports only whether the token was generated, kept, or rotated, never the value itself.
A prompt on every re-run plus a separate script for unattended callers was rejected: it
adds a question to every normal re-run and a second entry point for what is really one
step.

## Consequences

- **An install that predates this change is told exactly what to run.** Its first
  `up.sh` (or a bare `docker compose up`) now stops with compose's required-variable
  message naming `./scripts/setup.sh`.
- **A token set in the shell environment overrides the generated one**, as every
  compose variable does; an exported empty value keeps the refusal in place even after
  setup has run. Accepted: this is compose's documented precedence and an explicit
  operator choice, not a default.
- **A hand-copied old default is kept as a working token** if an operator once copied it
  into `.env` by hand, since nothing in the repository supplies that value anymore to
  re-check against. Accepted: `--rotate-worker-token` replaces it, and re-publishing the
  old value inside setup just to detect it would defeat the point of removing it.
- **Rotation needs both services restarted**, and a Worker running on another host needs
  the new value copied in by hand; environment values are read at container start. The
  start script already recreates both services when the value changes.

## Rejected

- **Keeping a published default alongside an opt-in override.** This is the status quo
  being replaced: every un-configured install shares one token that anyone who can read
  the repository already has.
- **A mounted-file token per service** (see above) — new file settings in two binaries,
  and no refusal on a bare compose start.
- **A start-script-only refusal with no required compose variable** (see above) — silent
  on a bare `docker compose up`.
- **Moving the refusal into the api-server's boot** (see above) — changes fail-closed
  behavior other deployments rely on, for a guarantee only compose's own interpolation
  needs to provide.
- **An interactive rotation prompt on every setup re-run, plus a separate unattended
  script** (see above) — an extra question on every re-run and a second entry point for
  one step.

This builds on the credential-location split in
[2026-11-10---01](2026-11-10---01-self-hosted-server-holds-no-credential.md), whose
status stays current: setup still only ever points at stale `CLAUDE_CODE_OAUTH_TOKEN` /
`GITHUB_PAT` / `GITHUB_APP_*` lines in `.env` and never edits them, and now also owns one
further line of that same file, `WORKER_REGISTRATION_TOKEN`, which those credential
lines never shared meaning with.
