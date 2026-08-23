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

# Hermetic git: the e2e stack has no egress to github.com, and WireMock stubs only
# the GitHub REST API — not git-over-https clone/push. Redirect the seeded
# e2e-test/* repo URLs to the in-network git-server (see docker-compose.e2e.yaml's
# git-server service). Written to the SYSTEM gitconfig (/etc/gitconfig) so it is in
# effect before entrypoint.sh's Step 3 clone runs, and never touched by the
# entrypoint's own `git config --global`. Scoped to e2e-test/* only, so a real repo
# URL (e.g. a public github.com/<org>/<repo>) is unaffected. Test-only: this layer
# ships solely in claude-code:e2e, never in the published production agent image.
RUN git config --system url."git://git-server/e2e-test/".insteadOf "https://github.com/e2e-test/"
USER 1000
