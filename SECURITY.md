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

The open-source core runs **single-tenant** on a trusted developer machine. By
design it boots with the API open (`AUTH_ENABLED=false`) and is intended to
**bind to localhost** — it is not hardened for exposure to untrusted networks,
and every request is scoped to a single seeded "system" organization.

Please assume this posture when reporting. "The API has no authentication" is
expected behavior for local single-tenant use, not a vulnerability. Reports
that meaningfully break this model — for example, escaping the localhost trust
boundary, agent-container escape, secret leakage, or remote code execution —
are in scope and very welcome.
