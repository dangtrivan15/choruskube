# Security Policy

Thanks for helping keep ChorusKube and its users safe.

## Supported versions

Security fixes target the latest release and the latest `main`. Older
versions are not maintained.

## Reporting a vulnerability

**Please do not open a public issue or pull request for a security problem.**
Public reports give attackers a head start before a fix is available.

Report privately through either channel:

- **Preferred:** GitHub private security advisories — open the repository's
  **Security** tab and choose **Report a vulnerability**.
- **Fallback:** email **dangtrivan15@gmail.com**.

Include enough detail to reproduce — affected component, version or commit,
and the steps involved. A proof of concept helps but is not required.

We will acknowledge your report promptly and keep you updated as we
investigate and prepare a fix.

## Disclosure

We follow coordinated disclosure: our target is **90 days** from the initial
report to a public fix and disclosure. We will work with you on timing and
credit you for the report unless you prefer otherwise.

## Scope

This policy covers the components in this repository:

- **api-server** (Spring Boot)
- **orchestrator** (Go / Temporal)
- **web-ui** (React)
- the **agent images** (`agent-images/`)

## Threat model

The open-source core runs **single-tenant** on a trusted developer machine, on a
network its operator trusts. By design it boots with the API open
(`AUTH_ENABLED=false`), and the compose stack publishes its services' ports on all
of the host's interfaces, the database and Temporal included, with development
credentials. It is not hardened for exposure to untrusted networks, and every
request is scoped to a single seeded "system" organization.

Inside a compose stack every container shares one network, so an agent container
can reach the database, Temporal and every port of the object store, not only
the presigned URLs it is handed. The stack is a trusted environment, not a
security boundary between its containers
([why](docs/decisions/2026-11-28---01-compose-stacks-are-a-trusted-environment-not-a-boundary.md)).

Please assume this posture when reporting. "The API has no authentication" and
"an agent container can reach another service in the stack" are expected
behavior for local single-tenant use, not vulnerabilities. Reports that
meaningfully break this model — for example, an agent escaping its container
onto the host, secret leakage, or remote code execution — are in scope and very
welcome.
