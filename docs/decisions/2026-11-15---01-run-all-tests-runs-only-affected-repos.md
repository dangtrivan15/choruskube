# run-all-tests can run only the repos a run affected, decided in the pod with git

**Status:** current

## Context

A multi-repo Software Project ran every member repo's `test_command` whenever its suite ran, on the
Test node and whenever an agent called `run-all-tests`. A run that changed one repo still re-ran
the suites of repos it could not affect, which in a project where one repo builds against another
meant re-running a long suite on a base the default branch had already passed.

A project can now declare which of its repos depend on which ("A depends on B" means a change to B
can break A) and switch on **Test only affected repos**. `run-all-tests` then runs the repos that
changed plus every repo that transitively depends on a changed one.

## Decision

**Changes are detected in the pod with git, not by the API server.** The server has no GitHub
client and its PR registry exists only after `register-pr`, so it cannot see unpushed or
uncommitted work, which is exactly what an agent calling `run-all-tests` mid-session has. A repo
counts as changed when its default branch's remote ref does not resolve (the base could not be
fetched, so the safe answer is "changed"), when `HEAD` is ahead of it, or when the working tree
has tracked edits or untracked, non-ignored files.

**The changed set is computed once, before any suite runs.** A suite that writes files into its
own or a sibling's checkout would otherwise change how a later repo is classified.

**It is opt-in per project and off by default.** A project that never touches the setting behaves
exactly as before, and so does a single-repo project. The graph and the switch are frozen into the
run snapshot with the rest of the project's test settings, so edits apply to runs started
afterwards.

**A run that changed no repo runs nothing and exits 0.** Every repo is reported skipped. Running
everything "to be safe" would defeat the setting for the common overlay-only run.

**A skipped suite is reported as SKIPPED, never PASSED.** The report line is
`**Status:** SKIPPED (unaffected — no change in this repo or any repo it depends on)`, distinct
from the existing "no test_command configured" skip. A reader of the report, or anything parsing
it, must never take an unrun suite for a passing one.

**The scope is only `run-all-tests`.** Nothing stops an agent running any repo's suite directly.

Edges are stored per project, not on the repo, and are written through their own endpoint
(`PUT /api/v1/repo-groups/{id}/test-scope`) rather than as fields on the repo-group request, so
existing constructor call sites of that request do not change. The server refuses a self-edge, a
non-member endpoint, or a cycle with a 400.

## Rejected

- **The API server decides which repos changed and marks each entry run or skip.** It cannot see
  unpushed work, has no GitHub client by rule, and would give the wrong answer to an agent calling
  `run-all-tests` before pushing.
- **Each repo declares its upstreams in a file in its own tree.** A repo cannot name its siblings
  stably across projects, and the per-project switch would have nowhere to live.
- **Differentiating by node type (only the Test node scopes).** Any agent node may run the tests
  itself, so the same logic has to apply wherever `run-all-tests` runs.
- **Running all repos when nothing changed.** Safe, but it keeps the cost the setting exists to
  remove.
