# agent-images/claude-code-e2e/Dockerfile
# Thin e2e-only layer: the production agent image stays free of test code.
# Build arg lets CI/local point at whatever base agent image is available.
ARG BASE_AGENT_IMAGE=claude-code:latest
FROM ${BASE_AGENT_IMAGE}

# Deliver the deterministic mock agent at a STABLE path, decoupled from the repo
# clone. docker-compose.e2e.yaml sets E2E_MOCK_SCRIPT_PATH to this path so the
# seeder's node commands resolve regardless of whether a repo was cloned.
USER 0
COPY mock-agent.sh /usr/local/bin/mock-agent.sh
RUN chmod +x /usr/local/bin/mock-agent.sh

# Hermetic git, no extra stack service: the e2e stack has no egress to github.com,
# and WireMock stubs only the GitHub REST API — not git-over-https clone/push. A
# prior revision ran a separate git-daemon container as a shared remote for every
# worker and node execution; besides being an extra moving part, it meant pushes
# accumulated across the whole e2e run instead of resetting per container (see git
# history for the daemon's own startup-hang failure mode). Everything that needs
# this remote — entrypoint.sh's Step 3 clone, and check-prs/register-pr's own
# ls-remote/fetch verification — runs inside this SAME container, in the same node
# execution, so a remote reachable only from inside this one container is enough;
# nothing else ever needs to see it. So seed a set of bare repos INTO the image at
# build time instead, and redirect the seeded e2e-test/* URLs to them over
# file://. Each container gets a fresh, private copy baked from this layer — no
# shared state to leak across runs or workers, no daemon to keep healthy.
RUN set -eux; \
    git config --global user.email "e2e@choruskube.local"; \
    git config --global user.name "ChorusKube E2E"; \
    git config --global init.defaultBranch main; \
    seed=$(mktemp -d); \
    cd "$seed"; \
    git init -q -b main .; \
    printf '# ChorusKube E2E mock repository\n' > README.md; \
    git add README.md; \
    git commit -q -m "Initial commit (e2e git fixture seed)"; \
    mkdir -p /opt/e2e-git-fixtures/e2e-test; \
    for r in mock-repo mock-frontend dind-repo; do \
        d="/opt/e2e-git-fixtures/e2e-test/$r.git"; \
        git init -q --bare -b main "$d"; \
        git config -f "$d/config" receive.denyCurrentBranch ignore; \
        git push -q "$d" main; \
        git -C "$d" symbolic-ref HEAD refs/heads/main; \
    done; \
    cd /; \
    rm -rf "$seed"; \
    chown -R agent:agent /opt/e2e-git-fixtures

# Written to the SYSTEM gitconfig (/etc/gitconfig) so it is in effect before
# entrypoint.sh's Step 3 clone runs, and never touched by the entrypoint's own
# `git config --global`. Scoped to e2e-test/* only, so a real repo URL (e.g. a
# public github.com/<org>/<repo>) is unaffected. Test-only: this layer ships
# solely in claude-code:e2e, never in the published production agent image.
RUN git config --system url."file:///opt/e2e-git-fixtures/e2e-test/".insteadOf "https://github.com/e2e-test/"
USER 1000
