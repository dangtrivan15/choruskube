# A transfer reports a missing object apart from a failed one

**Status:** current

## Context

An agent's artifact CLI used to treat every presign or transfer failure the same
way: one attempt, and any nonzero outcome read as "this key is simply absent." That
makes a wrong endpoint, a refused request, or a dropped connection indistinguishable
from an object that genuinely was never written — the node runs anyway, against
whatever inputs it happened to receive, and can report success against the wrong
data. Separately, the entrypoint pushed output artifacts from inside a subshell: a
failing upload aborted the subshell under `set -e`, and the subshell's own nonzero
exit then aborted the whole pod before it ever sent a callback, so the node only
resurfaced once its heartbeat finally timed out.

## Decision

**A transfer and a presign call both retry what a retry can fix, bounded well
inside the presigned URL's validity.** Both retry a connection failure, a timeout,
or HTTP 408/429/5xx, backing off 2s/4s/8s/16s (five attempts total, thirty seconds
of backoff). A transfer additionally starts no attempt more than 120 seconds after
its URL was issued, inside the three minutes or more of validity that even a URL
served from the presign cache still has, and reuses the same URL across its own
retries rather than presigning again.

**A 404 on a GET is the only outcome that reports "no such object," and it is final
at once, with no retry.** Every other outcome — a 403, a malformed presign
response, a host that does not resolve, a persistent 5xx, a 404 on a PUT — is an
ordinary failure. The CLI's exit status makes the distinction load-bearing: one
status means "not found," a different one means everything else, and an `EXIT` trap
prevents any other command's own exit code from leaking through as "not found" by
accident.

**The entrypoint skips an input only on "not found," and only when that input is
optional or is the run log.** A required input that reads "not found" fails the
node loudly instead of running the agent without it. Any other failure — on any
input, or on an output upload — fails the node the same way, including a failure
the previous behavior swallowed silently. Carrying a session across a quota hit is
the one exception: a failure to save or restore it still falls back to a fresh run,
because that outcome was always safe.

**Nothing prints the signed URL or a transfer's response body.** Retried or not,
the CLI's diagnostics name the method, the object path, the attempt, and the HTTP
status or curl's own exit code — never the query string a presigned URL carries its
signature in, and never a response body an object store could echo that signature
back through.

## Rejected

- **Keep treating every final failure as absent.** Correct for an input that is
  actually missing, but it leaves a misconfigured or unreachable transfer running
  an agent without its inputs, silently — the exact failure mode this decision
  closes.
- **Retry without a bound tied to the URL's own validity.** A transfer that outlives
  its presigned URL fails anyway, just later and with a confusing signature-expired
  error instead of a clear one.
