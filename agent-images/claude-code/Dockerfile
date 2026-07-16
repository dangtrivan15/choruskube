# agent-images/claude-code/Dockerfile
FROM debian:bookworm-slim

# Some networks restrict outbound traffic to 443, so apt has to reach the mirror
# over HTTPS from its very first request. bookworm-slim ships no CA bundle (that is
# what ca-certificates below installs), leaving nothing to verify the mirror against
# on that first fetch, so seed a trust store here to break the cycle. Only this one
# handshake depends on it: apt verifies package integrity via GPG signatures, and the
# ca-certificates package then takes ownership of /etc/ssl/certs. Pinned to alpine's
# multi-arch index digest so amd64 and arm64 builds resolve the same bundle.
COPY --from=alpine:3@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b \
    /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt

# Install system dependencies
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    git \
    jq \
    ripgrep \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Install Node.js 22 LTS (Claude Code requires Node 20+)
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# Install Claude Code via npm
RUN npm install -g @anthropic-ai/claude-code

# Install GitHub CLI (gh)
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
    | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
    | tee /etc/apt/sources.list.d/github-cli.list > /dev/null \
    && apt-get update && apt-get install -y --no-install-recommends gh \
    && rm -rf /var/lib/apt/lists/*

# Install Docker CLI (used when template enables DinD sidecar)
RUN install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
    && chmod a+r /etc/apt/keyrings/docker.asc \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian bookworm stable" \
        | tee /etc/apt/sources.list.d/docker.list > /dev/null \
    && apt-get update && apt-get install -y --no-install-recommends docker-ce-cli \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -g 1000 agent && useradd -u 1000 -g agent -m agent

# Copy entrypoint
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# Copy helper scripts
COPY report-result /usr/local/bin/report-result
COPY fetch-github-token /usr/local/bin/fetch-github-token
COPY check-decision /usr/local/bin/check-decision
COPY list-decisions /usr/local/bin/list-decisions
COPY send-heartbeat /usr/local/bin/send-heartbeat
COPY send-callback /usr/local/bin/send-callback
COPY artifact /usr/local/bin/artifact
COPY create-proposal /usr/local/bin/create-proposal
COPY list-proposals /usr/local/bin/list-proposals
COPY live-chat-loop /usr/local/bin/live-chat-loop
COPY register-pr /usr/local/bin/register-pr
COPY update-proposal /usr/local/bin/update-proposal
COPY create-story /usr/local/bin/create-story
COPY create-task /usr/local/bin/create-task
COPY run-all-tests /usr/local/bin/run-all-tests
COPY system_prompt.md /usr/local/share/choruskube/system_prompt.md
RUN chmod +x /usr/local/bin/report-result \
    /usr/local/bin/fetch-github-token \
    /usr/local/bin/check-decision \
    /usr/local/bin/list-decisions \
    /usr/local/bin/send-heartbeat \
    /usr/local/bin/send-callback \
    /usr/local/bin/artifact \
    /usr/local/bin/create-proposal \
    /usr/local/bin/list-proposals \
    /usr/local/bin/live-chat-loop \
    /usr/local/bin/register-pr \
    /usr/local/bin/update-proposal \
    /usr/local/bin/create-story \
    /usr/local/bin/create-task \
    /usr/local/bin/run-all-tests

# Disable telemetry, update checks, and other non-essential network traffic.
# Set via ENV (not settings.json) so it applies to all processes in the container.
ENV CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1

# Without this, --add-dir grants filesystem access only; CLAUDE.md in the added
# directory is not loaded. Skills and subagents are discovered either way.
ENV CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD=1

# Create ~/.claude/skills/ with agent ownership BEFORE switching to USER 1000.
# chown -R ensures directories (not just files) are writable by uid 1000 at runtime.
RUN mkdir -p /home/agent/.claude/skills \
    && chown -R agent:agent /home/agent/.claude

# Generic, repo-agnostic skills. Repo-specific skills ship in each repo's own
# .claude/skills/ and are discovered from the workspace clones at runtime.
# Layout must be <name>/SKILL.md with YAML frontmatter, or they are not loaded.
COPY --chown=agent:agent skills/ /home/agent/.claude/skills/

# Agent configuration: max turns guard
COPY --chown=agent:agent settings.json /home/agent/.claude/settings.json

# Own /workspace before dropping to uid 1000 — the entrypoint (running as the agent) creates
# /workspace/in and /workspace/out at startup. WORKDIR alone creates the dir with
# builder-dependent ownership (root on some buildkit/node combos), making those mkdirs fail
# with "Permission denied". Same reasoning as the .claude chown above.
RUN mkdir -p /workspace && chown agent:agent /workspace

USER 1000
WORKDIR /workspace

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
