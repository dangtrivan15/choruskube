# Privacy

ChorusKube sends one small, anonymous telemetry report on a periodic schedule.
This helps us understand roughly how many installs are active and what
platforms they run on. It is **opt-out** — you can turn it off completely.

## What it collects

Each report is a single JSON object with exactly these six fields:

| Field           | Type   | Example   | Meaning                                                |
|-----------------|--------|-----------|--------------------------------------------------------|
| `schemaVersion` | int    | `1`       | Telemetry payload version                              |
| `installId`     | UUID   | a random id | Stable, random per-install id, generated once and persisted |
| `appVersion`    | string | `1.4.0`   | The running ChorusKube version                         |
| `os`            | string | `linux`   | Operating system (`os.name`)                           |
| `arch`          | string | `amd64`   | CPU architecture (`os.arch`)                           |
| `runCount`      | int    | `12`      | Number of workflow runs created in the last 7 days     |

`installId` is a random UUID created once and stored locally. It is not tied to
your identity, machine, or network, and we cannot reverse it to anything about
you.

## What it does NOT collect

There is **no PII** and no business data of any kind. Specifically, ChorusKube
never sends:

- prompts, system prompts, or any agent input
- repository names, URLs, branches, or commit contents
- organization names, user names, emails, or IP addresses
- workflow/template names, node outputs, logs, or artifacts
- secrets, tokens, or credentials

`runCount` is a single global number (a count of runs in the trailing 7 days). It
contains nothing about *which* runs or *what* they did.

## When it is sent

- The report is sent on a **weekly** cadence.
- The first send is delayed after startup, so telemetry never fires during boot.
- If a send fails (network error, non-2xx response), the failure is silently
  ignored — telemetry never disrupts or slows down ChorusKube.

It is delivered to the receiver endpoint, by default:

```
https://api.choruskube.com/api/public/v1/telemetry
```

## How to opt out

Set the environment variable on the API server and restart:

```bash
CHORUSKUBE_TELEMETRY=off
```

When disabled, no payload is built and nothing is sent.
