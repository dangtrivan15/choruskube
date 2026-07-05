# ChorusKube Quickstart

The canonical first-run guide. This walks you from a clean checkout to a running
stack and, optionally, a real AI workflow. For the project overview see
[README.md](README.md); for the dev / test workflow see [CONTRIBUTING.md](CONTRIBUTING.md).

## Requirements

- **Docker** and **Docker Compose v2** (`docker compose`, not `docker-compose`).
- The published agent and orchestrator images are **linux/amd64-only** for now.
  On Apple Silicon / arm64 hosts Docker pulls the amd64 images and runs them under
  emulation automatically — no `platform:` override is needed. No action required;
  it just works, a little slower.

## Quick start

```bash
./scripts/setup.sh         # configure secrets (all optional), then offers to start the stack
./scripts/up.sh            # build + start, wait until healthy
```

`setup.sh` walks you through the optional Claude and GitHub credentials one value
at a time and writes a gitignored `.env` the stack reads (see
[Run a real AI workflow](#run-a-real-ai-workflow)). Every credential is optional —
**skip `setup.sh` and run `up.sh` directly to boot with zero configuration.** To
start the stack yourself instead of going through the scripts, `up.sh` maps to:

```bash
docker compose up --build --wait
```

The stack boots from a clean volume with **zero configuration** — no OIDC provider,
no GitHub App, no Claude token required just to come up. The API server serves
templates and runs out of the box.

Then open the web UI and explore:

| Service   | URL / address                                   |
|-----------|-------------------------------------------------|
| Web UI    | http://localhost:33000                          |
| API       | http://localhost:38080 (Swagger at `/swagger-ui.html`) |
| Postgres  | localhost:35432                                 |
| Temporal  | localhost:37233                                 |
| Object storage | http://localhost:39000 (API) · http://localhost:39001 (console) |

## Tear down

Tear down (and wipe the Postgres/object storage volumes):

```bash
./scripts/down.sh          # docker compose down -v
```

## Run a real AI workflow

Real runs use two optional secrets, both seeded into the system org at boot:

- a **Claude Code OAuth token** — required for AI agent nodes;
- a **GitHub credential** — required only to push and open PRs against a target
  repo (a Personal Access Token, or a GitHub App).

The easiest way to configure them is [`./scripts/setup.sh`](#quick-start), which
prompts for each value and writes the gitignored `.env`. Prefer to set them by
hand? Export the variables before `up.sh` (or put them in a `.env` at the repo
root):

| Variable | Purpose |
|----------|---------|
| `CLAUDE_CODE_OAUTH_TOKEN` | Claude token for AI agent nodes (`claude setup-token`). |
| `GITHUB_PAT` | A GitHub Personal Access Token. |
| `GITHUB_APP_ID` / `GITHUB_APP_INSTALLATION_ID` / `GITHUB_APP_PRIVATE_KEY_PATH` | A GitHub App credential. The App takes precedence over `GITHUB_PAT` when both are set. Place the App private-key `.pem` under `./.secrets/` and point the path at `/run/secrets/<file>.pem`. |

Without any of these the stack still boots and serves the full API — only the
steps that need them (AI nodes, repo pushes) are skipped; everything else,
including script and human-gate nodes, runs.

> Using a **GitHub App**? Start the stack with `./scripts/up.sh` (or run
> `mkdir -p .secrets` once) before your first `docker compose up`, so the
> `.secrets/` PEM directory is created owned by you rather than by Docker as root.

## Next steps

- **Architecture** — how the pieces fit together: [ARCHITECTURE.md](ARCHITECTURE.md).
- **Dev setup & the end-to-end test suite** — [CONTRIBUTING.md](CONTRIBUTING.md).
