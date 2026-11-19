# The agent image chain, built from one source tree: choruskube-dev's base is the claude-code
# target, never a published tag. A downstream build includes this file, so renaming a target or
# CORE_ROOT breaks it.

# Prefix every path below with it: a downstream runs bake from its own root, where an unprefixed
# path such as api-server silently resolves to the downstream's directory instead of this one.
variable "CORE_ROOT" {
  default = "."
}

target "claude-code" {
  context   = "${CORE_ROOT}/agent-images/claude-code"
  platforms = ["linux/amd64"]
}

target "choruskube-dev" {
  context   = "${CORE_ROOT}/agent-images/choruskube-dev"
  platforms = ["linux/amd64"]
  # The arg renames the Dockerfile's FROM to the context bound to the claude-code target. Without
  # both, FROM falls back to the published claude-code:latest, which can predate this commit's CLI.
  args = {
    BASE_AGENT_IMAGE = "claude-code-target"
  }
  contexts = {
    "claude-code-target" = "target:claude-code"
    "api-server"         = "${CORE_ROOT}/api-server"
    "orchestrator"       = "${CORE_ROOT}/orchestrator"
    "worker"             = "${CORE_ROOT}/worker"
    "web-ui"             = "${CORE_ROOT}/web-ui"
  }
}
