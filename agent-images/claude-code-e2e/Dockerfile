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
USER 1000
