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
./scripts/setup.sh         # run once: generates the Worker's Fleet token; every other prompt is optional
./scripts/up.sh            # build + start, wait until healthy
```

The first run of `setup.sh` generates this install's own Worker Fleet token into the
gitignored `.env` (see [Rotating or sharing the Worker token](#rotating-or-sharing-the-worker-token)) —
`up.sh` and a bare compose start both refuse without it. Beyond that one step,
`setup.sh` walks you through the optional Claude and GitHub credentials one value
at a time and writes them into the gitignored folder `.secrets/credentials/`, which
the stack mounts read-only on the Worker (see
[Run a real AI workflow](#run-a-real-ai-workflow)). Every credential is optional, and
you can run the token step alone, with no prompts: `./scripts/setup.sh --worker-token-only`.
To start the stack yourself instead of going through the scripts, `up.sh` maps to:

```bash
docker compose up --build --wait
```

The stack boots from a clean volume with **one setup step, every prompt optional** —
no OIDC provider, no GitHub App, no Claude token required just to come up, only the
generated Worker token. The API server serves templates and runs out of the box.

> **Upgrading an install from before this token step?** `up.sh` (or a bare
> `docker compose up`) now stops with a message naming `./scripts/setup.sh` — run it once
> to generate the token into `.env`, then start the stack again. A Worker running on
> another host takes the same value: copy `WORKER_REGISTRATION_TOKEN` from `.env` into
> its own `FLEET_TOKEN`.

Then open the web UI and explore:

| Service   | URL / address                                   |
|-----------|-------------------------------------------------|
| Web UI    | http://localhost:33000                          |
| API       | http://localhost:38080 (Swagger at `/swagger-ui.html`) |
| Postgres  | localhost:35432                                 |
| Temporal  | localhost:37233                                 |
| Object storage | http://localhost:39000 (S3 API) · http://localhost:39001 (filer UI) |

## Tear down

Tear down (and wipe the Postgres/object storage volumes):

```bash
./scripts/down.sh          # docker compose down -v
```

## Rotating or sharing the Worker token

The api-server and the Worker must present the same `WORKER_REGISTRATION_TOKEN`
(stored only in `.env`) or the Worker's registration is refused and no run node ever
executes. To replace it:

```bash
./scripts/setup.sh --worker-token-only --rotate-worker-token
./scripts/up.sh
```

A Worker running on another host (not this compose stack) takes the same value as its
own `FLEET_TOKEN` — copy it out of `.env` by hand after rotating.

## Run a real AI workflow

Real runs use two optional secrets, both held by the Worker — the API server holds
no credential:

- a **Claude Code OAuth token** — required for AI agent nodes;
- a **GitHub credential** — required only to push and open PRs against a target
  repo (a Personal Access Token, or a GitHub App).

The easiest way to configure them is [`./scripts/setup.sh`](#quick-start), which
prompts for each value and writes them into `.secrets/credentials/`. The stack
mounts that folder read-only on the Worker as its credentials folder. Prefer to
write it by hand? Use the Worker's layout, one value per file:

| File under `.secrets/credentials/` | Purpose |
|------------------------------------|---------|
| `claude-oauth-token` | Claude token for AI agent nodes (`claude setup-token`). |
| `github-pat` | A GitHub Personal Access Token. |
| `github-app/app-id`, `github-app/installation-id`, `github-app/private-key.pem` | A GitHub App credential: its id, its installation id, and its private-key PEM. All three are required. |

The Worker reads the folder on every use, so a file replaced on disk takes effect on
the next run without a restart. The folder comes before the Worker's own environment
(`CLAUDE_CODE_OAUTH_TOKEN`, `GITHUB_PAT`, `GITHUB_APP_*`, which the compose stack
does not set), and a PAT comes before a GitHub App: with both in the folder, the
Worker uses the PAT. The full precedence and the registry login are in
[worker/README.md](worker/README.md#credentials).

> **Upgrading an install that kept its credentials in `.env`?** Those lines are no
> longer read. Re-run `./scripts/setup.sh` to write the folder, then delete the
> `CLAUDE_CODE_OAUTH_TOKEN`, `GITHUB_PAT` and `GITHUB_APP_*` lines from `.env`.

Approving the Final Approval of Feature Development or Task Delivery merges the run's
registered pull requests, so the credential needs write access to repository contents and pull
requests (App: *Contents* and *Pull requests* read/write; classic PAT: `repo`) —
the same access agents already use to push branches and open PRs — plus *Workflows*
read/write (classic PAT: `workflow`) if a run's PRs may change `.github/workflows/`.

The Worker mints a GitHub App token narrowed to the run's own GitHub repositories
and a fixed run permission list (`contents`, `pull_requests`, `issues` and
`workflows` write; `actions`, `checks`, `metadata` and `statuses` read), each
capped at what the App was granted — never the whole installation. The App's installation must be on the
account that owns the repositories your runs use, with each of them in its
repository selection: one outside the selection fails the whole mint for its run.
A Personal Access Token cannot be narrowed this way — it carries whatever access
its owner granted it, so prefer a fine-grained PAT limited to the repositories
your runs use; the Worker checks once per run that it reaches each of them.

Without any of these the stack still boots and serves the full API — only the
steps that need them (AI nodes, repo pushes) fail; everything else, including
script and human-gate nodes, runs.

> Starting with a bare `docker compose up`? Run `./scripts/setup.sh --worker-token-only`
> first — compose refuses without a generated `WORKER_REGISTRATION_TOKEN` in `.env`.
> Also run `mkdir -p .secrets/credentials` once (`./scripts/up.sh` does it for you), so
> the credentials folder is created owned by you rather than by Docker as root —
> `setup.sh` could not write a root-owned one.

## Next steps

- **Architecture** — how the pieces fit together: [ARCHITECTURE.md](ARCHITECTURE.md).
- **Dev setup & the end-to-end test suite** — [CONTRIBUTING.md](CONTRIBUTING.md).
