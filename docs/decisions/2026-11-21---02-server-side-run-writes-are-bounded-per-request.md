# Server-side run writes are bounded per request

**Status:** current

## Context

Two of our own services write into a run's storage tree outside any agent presign, and
neither write was bounded. A reviewer's gate feedback (`SignalRequest#feedback`) had no
size limit, so a reviewer — or any API caller with operate rights — could make the
api-server write megabytes of guidance into the run's tree per approval, with a fresh
object written on every Story-version re-check attempt. The orchestrator's run-log
append wrote a node's result, error and artifact refs verbatim into `run_log.md`, so a
Worker outside the server's network, or a runaway agent result, could grow that log by
an unbounded amount per node; every later agent then reads the whole log as its input.

## Decision

**A reviewer's gate feedback is capped at 20000 characters, enforced where the request
first enters the system.** `SignalRequest#feedback` carries `@Size(max = 20000)` — the
same constraint `StoryReviseRequest#guidance` already carries — enforced by the signal
controller's existing `@Valid`. Feedback over the cap gets a 400 before the gate is
claimed or anything is signalled, so the gate stays open and the reviewer can shorten
the text and resubmit. Every web-ui box whose text becomes that feedback (the run
page's gate panel, the Approvals page's gate card, and the escalation gate's guidance
box) stops accepting input at the same length, via one shared constant
(`MAX_GATE_FEEDBACK_LENGTH`) mirroring the server value. A request the system builds
for itself — a Story-version re-check, a Story-conflict release — is never run through
a `Validator` and may exceed the cap by its own fixed header text; it is still
signalled whole, since refusing a decision the system makes on its own behalf has no
caller present to fix it. The alternative of a global request-body size limit would
have to sit far above any one text field to avoid rejecting a large legitimate body
(an edited roadmap document), so it would bound feedback only loosely. Truncating
silently at each write site would avoid ever refusing a request, but a reviewer's text
would be cut without their knowledge, and the truncation would have to be repeated
correctly at every write site.

**A run-log entry's free-text fields are each capped at 64 KiB, cut by the
orchestrator at the one place every source funnels through.** The orchestrator is the
only writer of `run_log.md`, so it bounds each entry itself rather than trusting its
sources: `capRunLogField` cuts a value over `maxRunLogFieldBytes` (64 KiB) to the last
whole UTF-8 rune at or below the cap and appends a marker line reporting the bytes kept
and the original size; a value at or under the cap is unchanged. `buildRunLogEntry`
applies this to the result, error message and artifact refs before formatting, while
the emptiness and `"{}"` checks that decide whether a field is shown at all still run
on the raw value. 64 KiB was chosen because capped reviewer feedback — 20000
characters, at most 60000 bytes even in 3-byte UTF-8 — always survives whole in the
log, leaving headroom for the text the server adds around it; a smaller cap (e.g. 16
KiB) would cut that same capped feedback, and a single cap on the whole entry would let
a long result squeeze out the artifact refs a later agent looks up by path. Capping at
each of the several sources instead (an agent's CLI, a Worker's callback, the gate
signal) was rejected because the orchestrator does not run every source — a Worker
outside the server's network is not ours to enforce a limit on — and refusing an
oversized completion outright was rejected because a node whose work already finished
should not fail over the length of its own summary. The full, uncut text of any field
stays wherever it already lives (the node execution's own record, shown on the run
page); only the run log's copy is cut. The cut is a pure function of the activity's
input, so a Temporal retry produces an identical entry.

## Consequences

- A gate signal over the feedback cap gets 400 before any claim, workflow signal, or
  gate-text write; the gate is left exactly as it was. A null or empty feedback, and
  feedback at exactly the cap, are accepted unchanged.
- A run log can still grow without bound in the number of entries it holds, and each
  append still rewrites the whole object — only one entry's own fields are now capped.
  A deployment's own storage cap backstops the total.
- `attachmentRefs` and the free text inside a signal's `editedCandidates` remain
  uncapped at the API: neither is written to the object store as its own object, so
  neither needed this cap to bound a write.
