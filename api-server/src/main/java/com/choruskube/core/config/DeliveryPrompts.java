package com.choruskube.core.config;

import static java.util.Map.entry;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Implement and Code Review prompts, shared by every template that delivers against an approved spec.
 * Text outside a slot reaches all of those templates, so changing it means bumping each one's version:
 * a seeded version is never rewritten, and without a bump the new text never reaches a running install.
 */
final class DeliveryPrompts {

    enum SpecSource {
        FEATURE_DEVELOPMENT,
        TASK_DELIVERY
    }

    // A slot is a whole `<<NAME>>` line, replaced together with its newline: a passage must end in a
    // newline or be empty, or it fuses with the prompt's next line.
    private static final Pattern SLOT = Pattern.compile("(?m)^<<([A-Z_]+)>>\n");

    private DeliveryPrompts() {}

    static String implement(SpecSource source) {
        return fill(IMPLEMENT, passages(source));
    }

    static String codeReview(SpecSource source) {
        return fill(CODE_REVIEW, passages(source));
    }

    private static Map<String, String> passages(SpecSource source) {
        return switch (source) {
            case FEATURE_DEVELOPMENT -> FEATURE_DEVELOPMENT_PASSAGES;
            case TASK_DELIVERY -> TASK_DELIVERY_PASSAGES;
        };
    }

    private static String fill(String template, Map<String, String> passages) {
        return SLOT.matcher(template).replaceAll(slot -> {
            String passage = passages.get(slot.group(1));
            if (passage == null) {
                throw new IllegalStateException("No passage for prompt slot " + slot.group(1));
            }
            return Matcher.quoteReplacement(passage);
        });
    }

    private static final String IMPLEMENT = """
            <<OPENING>>

            Before writing docs or comments in a repo, read that repo's CLAUDE.md.
            Its conventions override these instructions. Follow the instructions below
            only where that repo states no rule. When a run touches several repos,
            each repo's rules apply to its own files.

            <<SPEC_DOCUMENTS>>

            ## Inputs

            <<SPEC_INPUTS>>
            - `/workspace/in/run_log.md` — accumulated history of all prior
              nodes. On a retry it also holds the earlier Implement summary and
              the Test node's report.
            <<GATE_INPUTS>>

            Repositories are cloned under /workspace/repo/<name>/. Each repo already has
            a working branch created for this run — you can verify with `git branch` in
            each repo directory. Follow existing patterns and conventions in each repo's
            codebase.

            ## Parallel execution across repos

            When the plan marks repos as independently implementable, dispatch Task
            subagents — one per repo — to implement in parallel. Each subagent should:
            - `cd` into its assigned /workspace/repo/<name>/
            - Implement the planned changes for that repo only
            - Write tests for the changes (the deterministic Test node downstream
              of Code Review will run them — do NOT run the full test suite yourself)
            - Commit all changes on the working branch
            - Push the branch to origin with `git push --force-with-lease origin HEAD`
            - Do NOT run `gh pr create` from inside this per-repo subagent. PR
              creation happens once, afterward, in the "Opening and updating
              pull requests" section below, after every repo's implementation
              is done. Your subagent's responsibility ends at `git push`.

            For repos with cross-repo dependencies (the plan will call these out),
            implement them in the ordered sequence specified in the plan; do not
            dispatch those in parallel.

            Independently of the per-repo parallelism above: when a specific
            sub-problem within a repo is genuinely hard — an ambiguous design
            decision, a tricky concurrency or migration edge case, a piece of logic
            you are not confident about — escalate that sub-problem to a Task
            subagent and explicitly request the more capable model for it. Keep
            straightforward, mechanical edits on the primary thread. This mirrors
            how the platform already dispatches subagents for parallel exploration;
            here it is about matching model capability to problem difficulty rather
            than fan-out.

            Note: this node may be a retry of a previous attempt. Before you start,
            take a quick look at the current state of each repo's working branch —
            prior commits, partial changes, or anything left over. Use that context
            to decide how to proceed.

            If you encounter build errors or ambiguities in the plan, fix them
            yourself. Try multiple approaches if the first one fails. A quick
            compile or type-check is fine to catch obvious breakage, but the
            authoritative test gate is the Test node downstream — not your local
            invocation.

            <<UNSETTLED_PLAN>>

            ## Routing the spec into durable docs

            <<ROUTING_SOURCE>>
              - Technical Decisions -> docs/decisions/  (accumulates; an entry is immutable
                                       except its status line)
              - Architecture, with its
                Runtime Flow        -> merge into ARCHITECTURE.md (rewritten in place, so it
                                       does NOT accumulate); this is present-tense state, not
                                       a record of this change
              - Caveats tagged "Future work" -> the roadmap proposal (see "Proposing
                                       deferred work to the roadmap" below). Never file a GitHub
                                       issue for one yourself: each proposed Task gets its own
                                       issue when Final Approval approves it, and that issue
                                       closes with the Task. Accepted caveats live only in the PR
                                       body. Do not create a new docs/ surface for either
              - Context, Approach   -> discard; they tell the story of this change, and
                                       whatever in them lasts is already in Architecture or
                                       a decision
            <<ROUTING_DISCARD>>
            Graduate a decision only when something in this repo cites it. Do not bulk-copy
            the spec.

            Graduation is selective, so a decision you graduate can reference one you left
            behind. Resolve or delete every such reference as you write the entry: state what
            the other decision settled, or drop the clause. A reference to something the
            reader cannot open is worse than no reference.

            These docs are derived from the spec. A run owns exactly one decisions file —
            docs/decisions/YYYY-MM-DD---NN-<slug>.md — holding every decision that run
            graduates. Date it by the UTC date you write it, and take as NN the next free
            number for that date on the base branch (`01` if there is none). On a re-run,
            edit that same file; never create a second one. The spec may have changed since
            an earlier iteration wrote it and may now conflict with what is there — amend
            or rewrite as you judge fit. ARCHITECTURE.md is an existing living document;
            merge into it rather than adding a parallel file.

            Parallel runs can take the same number, and git merges both files without a
            word because their names differ. Before you push, check the base branch as it
            is then: if it already holds an entry with your date and number, keep that one,
            because it is merged and cited. Move yours to the next free number for that
            date with `git mv`, and update every reference to its old filename.

            Directly under the entry's title, with a blank line on each side, write its
            status line: `**Status:** current`. That line is where a later run marks the
            entry superseded, and where a reader who arrives from a code comment sees
            whether it still holds; without it, the directory accumulates decisions no
            reader can tell are still true. Keep no index of entries beside them: a list
            every run appends a row to conflicts between any two open pull requests that
            each add an entry.

            Before graduating a decision, read the title and status line of each existing
            entry for one this run's decision reverses or replaces. If one exists, do not
            edit its body. Add your entry as normal, name in it which entry it supersedes,
            and rewrite only the old entry's status line, to
            `**Status:** superseded by [<your entry>](<your entry>.md)` — or, when your
            decision replaces only part of it,
            `**Status:** current; its <part> is superseded by [<your entry>](<your entry>.md)`.

            ## Proposing deferred work to the roadmap

            Deferred work can become roadmap items only through a proposal that the Final
            Approval reviewer approves. Never create roadmap items yourself: in this workflow
            the server rejects create-proposal, update-proposal, create-story, create-task,
            create-dependency and create-milestone.

            <<ROADMAP_SOURCE>>

            A deferral criterion is one of:
              (a) it needs its own design decision with real alternatives, one this
                  request did not ask the run to make;
              (b) it changes a contract, schema or component this change does not
                  otherwise touch;
              (c) it needs an investigation or measurement, one that can run now,
                  before it can be designed.
            Size alone is never one. An item that waits on an event (data grows past a
            size, users complain, a business decision) rather than on work is never a
            Task: it is Accepted. Placement: prefer the triggering Task's Epic; a new
            Epic only for a genuinely separate initiative; never new children under any
            other existing Epic.

            Additions are rare. A gap you find while implementing is in scope by default: fix
            it in this run. Only when it meets a deferral criterion, add it to the PR
            body's Caveats as Future work marked `(found during implementation)`, then add it to
            the proposal. A defect outside this change that someone hits today may be added the
            same way; drop anything else unrelated.

            Start every new Task's description with one origin line, so the Final Approval
            reviewer knows which entries need a first look:
              Origin: approved with the spec — Caveat: <title>
              Origin: added during implementation — <criterion>: <one line>

            The spec links an existing roadmap item to its page in the app, and the item's
            id is the last segment of that link: use it as the item's "existingId".

            A new Task's title and description become a GitHub issue in the repo it is about.
            Resolve that repo's visibility the same way the PR body does below, and generalize
            or drop anything a PUBLIC repo may not carry. Write each app link from the spec
            (a path starting /roadmap/ or /tasks/) as its plain title: those paths open only
            inside ChorusKube.

            Write the proposal in the roadmap_candidates.json shape:
              {"epics": [...], "dependencies": [{"blocking": "<key>", "blocked": "<key>"}]}
            - An entry carrying "existingId" is an item that already exists: nothing is created
              for it and its other fields are ignored. Any other entry is new and needs a title
              and a description; "priority" is High, Medium or Low.
            - If this run was started from a Task (see "Triggering Task" in your system prompt,
              or run get-roadmap-graph), prefer extending that Task's Epic. The top-level entry
              that holds new items is {"existingId": "<Epic id>", "stories": [...]}: add new
              Stories there, or new Tasks under one of its existing Stories via
              {"existingId": "<Story id>", "tasks": [...]}. Only when the deferred work is a
              genuinely separate initiative, not a follow-up to this run's own change, propose a
              wholly new top-level Epic instead (a title, at least one Story and at least one
              Task). Do not anchor a new Story or Task under any other existing Epic.
            - If this run was not started from a Task, a new Epic is allowed, but every new
              Epic needs at least one Story and every new Story at least one Task.
            - Give an entry a "key" to use it in "dependencies". When a follow-up builds on
              this run's change, list this run's Task as {"existingId": "<Task id>", "key": "..."}
              under its Story and make it the blocking side. When a "Proposed as" line names a
              blocking Task elsewhere, anchor that Task the same way, under its own anchored
              Story and Epic and with no new children, and make it the blocking side.
            - No milestones.
            - A new Task in a multi-repo project needs "repoId" naming which of this run's repos
              it is about; a single-repo project fills it in for you. The server files a matching
              GitHub issue for every new Task automatically and closes it when the Task is done.

            Install it with `propose-roadmap --file <path>`. The server validates it and the
            tool writes /workspace/out/roadmap_candidates.json. Fix every reported error and
            re-run until it succeeds; re-running replaces the proposal. To withdraw it, install an
            empty proposal, {"epics": []}; never just delete the file, or Final Approval may
            fall back to an older copy. If /workspace/in/implement/roadmap_candidates.json
            exists, it is your previous attempt's proposal: re-submit it (amended as needed),
            or install an empty one to withdraw it.
            Code Review reviews your proposal next and may edit it.

            ## Rebased branches

            The entrypoint rebases this run's branch onto the latest default branch when it
            clones, so once main has moved a plain `git push` is rejected. Always push with
            `git push --force-with-lease origin HEAD`; the lease still refuses to overwrite
            commits you have not fetched.

            If `/workspace/in/rebase_status.md` exists, read it before anything else: the
            entrypoint could not rebase some repo, and your checkout there is still on the old
            base. Rebase it yourself and resolve the conflicts as part of this pass.

            ## Opening and updating pull requests

            Once every repo's implementation is done, open or refresh a pull
            request for every repo you pushed commits to this run. This is your
            responsibility now — Push & Create PR no longer exists as a
            separate node; PR creation moved here so a reviewer has something
            to look at from the moment implementation starts.

            **Retry check first.** Build a known-PRs set before doing anything
            else: merge every `pr_urls.txt` visible in your Predecessor
            Artifacts — your own earlier iteration's (label `implement` —
            present only on a retry after a Test failure, i.e. iteration > 1)
            AND Code Review's, if present (label `code_review` — present
            whenever Code Review ran and registered anything before Test
            failed and routed back to you; Code Review is reachable as your
            own predecessor on this path, and it may have opened a fallback PR
            for a repo you never touched).
            `artifact get` each one that's present. Where both list the same
            repo, the `code_review` entry wins (it reflects more recent
            state); where only one lists a repo, include it anyway. For any
            repo in this merged set, do NOT open a new PR — just push your new
            commits and re-run `register-pr`, using the PR's current
            title/number (fetch via `gh pr view <url> --json title,number`
            first, so the refresh call doesn't blank out existing metadata).

            For every other repo with commits pushed this run:

            0. **Resolve the repo's visibility FIRST**, before writing any PR
               text: run `gh repo view <owner/repo> --json visibility`. Treat it
               as PUBLIC if the command fails or the answer is unclear
               (fail-safe). This classification governs what may appear in the
               PR body. See "Repository Isolation" in your system prompt for the
               full rule; it overrides any instruction here that conflicts with
               it. A PUBLIC repo's PR must not name, link to, or otherwise
               reveal the existence of any non-public repo in this run, and must
               not carry that repo's infrastructure detail — scope and
               generalize instead.

            1. **Push and open a PR.** Ensure all changes are committed and the
               branch is pushed to origin, then create a pull request against
               the default branch via `gh pr create`.
               - PR title: a concise summary of this repo's portion of the
                 feature.
               - PR body, in this exact order. Its readers are the human who
                 approves the merge and whoever later reaches this change from
                 the code; the spec is not kept, so the body stands on its own,
                 and it stays short enough to read before the diff. Every
                 section is scoped to this repo, and a section with nothing in
                 it is omitted: no heading, no "none" note.

            <<PR_SPEC_SOURCE>>
                 **a. `## Context`** — the specification's Context diagram and
                 the numbered flaw lines this repo's change addresses.

                 **b. `## Approach`** — the specification's Approach diagram, the
                 fix lines for those same numbers, and its closing paragraph
                 trimmed to what concerns this repo. The fix lines are the
                 summary of what changed: add no other summary and no changelog
                 of the files.

                 **c. `## Where to review`** — the three to seven files where
                 this repo's change makes a decision or alters behaviour, each as
                 `` `<path>` — <number>: <what to check there, in one phrase> ``.
                 End with one line naming the mechanical rest (call sites,
                 fixtures, formatting) that is safe to skim.

                 **d. `## Test plan`** — what you ran in this repo and its
                 result, one line per command, then one line on what CI covers
                 that you did not run, and anything the reviewer must check by
                 hand.

                 **e. `## Operator actions`** — the Production actions and
                 rollout notes from the specification's Manual Operations that
                 concern this repo, one bullet each: the step and when to take
                 it. Leave out the Local column, rows with nothing to do in
                 production, and the "Checked, nothing needed" line.

                 **f. `## ❓ Open decisions`** — ONLY if at least one Caveat is
                 still tagged "Needs human decision": restate the open question
                 in one sentence, list the options the spec laid out (if any),
                 and make clear that merging implies accepting the default
                 behavior described in the Caveat's Reasoning.

                 **g. Known limitations** — every other Caveat that concerns
                 this repo, one row each and Future work first, in a table
                 inside a collapsed block:

                   <details><summary>Known limitations (N)</summary>

                   | Caveat | Disposition | Why |
                   |---|---|---|
                   | <title> | <Disposition> | <its reasoning in one phrase> |

                   </details>

                 Each cell is one phrase, with no code or pipe characters: they
                 break the table. Once the spec is gone this block is the only
                 record of what was knowingly left out, so it keeps every such
                 Caveat however minor. Write each app link as its plain title. In
                 the Disposition cell, mark each Future-work Caveat you proposed
                 to the roadmap this run as `proposed to the roadmap (pending
                 Final Approval)`, and keep the `(found during implementation)`
                 marker on any you added.

                 **h. Companion PRs placeholder** — a single line:
                 `_Companion PRs: (linked after all PRs are created)_`. OMIT
                 this placeholder entirely if this repo will have no linkable
                 companions under step 3 below (e.g. a PUBLIC repo in a run
                 whose other repos are all non-public).

               - Visibility. A NON-PUBLIC repo's body carries the spec's content
                 as it stands, numbers included, so they match the spec and the
                 companion PRs. A PUBLIC repo's body:
                   - Context and Approach: redraw each diagram with only
                     components a public reader can see, or drop the diagram and
                     keep the numbered lines. Renumber from ① with no gaps, since
                     a gap hints at work elsewhere. Justify the change on grounds
                     that hold within this repo alone, never by reference to
                     another repo.
                   - Operator actions, Open decisions and Known limitations:
                     include ONLY the operations, decisions and Caveats that apply
                     to this repo, rewritten to remove other-repo names, internal
                     hosts, cluster/namespace detail and internal paths. Drop an
                     item that describes work in, or exists only because of, a
                     non-public repo entirely — never a placeholder that implies
                     one exists.

               Record the PR URL and PR number. You may dispatch Task subagents
               to open PRs in parallel — one per repo.

            2. **Register each PR with ChorusKube** by running, for each PR:

                   register-pr --repo-id <gitRepoId> --pr-url <url> \\
                       --pr-number <number> --title <title> --repo-name <name>

               Use the `id` from config.json's `repos[]` as `<gitRepoId>`.

            3. **Cross-link the PRs you just opened, honoring visibility.**
               After all of this pass's PRs exist, for each PR edit its body
               (via `gh pr edit <url> --body <new_body>`) to replace the
               Companion PRs placeholder with a `## Companion PRs` section.
               Which PRs may be listed depends on the visibility of the repo
               whose body you are editing:
                 - NON-PUBLIC repo's PR: list every OTHER PR opened this pass.
                 - PUBLIC repo's PR: list ONLY the other PRs opened this pass
                   whose repos are also PUBLIC. Never link or name a
                   non-public repo's PR from a public repo — the URL alone
                   discloses that repo's existence.
               When the specification's rollout notes set a merge order among
               the PRs listed, add it under the list in one line that names only
               those PRs.
               If no companions remain listable for a public repo, omit the
               `## Companion PRs` section entirely. Do NOT write "none", "not
               applicable", or any note explaining why it is empty. PRESERVE
               the other sections during this edit.

            4. **Save artifacts**:
               - /workspace/out/pr_urls.txt — one `repo_name: url` line per
                 repo with an open PR: the full known-PRs set from the retry
                 check above (even a repo you didn't push to this pass, e.g.
                 one only Code Review's file listed), plus anything newly
                 opened this pass — not just the repos this pass touched — so
                 this file fully supersedes both your own prior iteration's
                 file and Code Review's for every downstream reader.
               - /workspace/out/pr_summary.md — brief summary of what was
                 shipped. Include one-line notes if any PR carries Operator
                 actions and/or if any Open decisions remain.

            Code Review runs next and will keep these PRs current as it fixes
            issues; it also opens a PR itself for any repo you didn't touch. Do
            not merge PRs yourself — approving Final Approval merges every
            registered PR, so a PR you do not register is never merged.

            Before finishing, verify the implementation in each affected repo:
            - All plan steps for that repo are addressed
            - Tests are written (the Test node will execute them)
            - Code quality is clean (no dead code, no debug artifacts, no unused imports)
            - Changes match the spec intent, not just the plan mechanics
            - All changes committed and pushed to the working branch
            - A PR is open and registered (via `register-pr`) for every repo
              with commits pushed this run, unless it already had one from a
              prior iteration (Test-failure retry), in which case it was
              refreshed, not duplicated.

            Save a summary of what you changed (per repo) as /workspace/out/summary.md.

            {review_history}""";

    private static final String CODE_REVIEW = """
            You are a self-iterating code reviewer. You FIND flaws AND FIX them in
            the same session — by editing the code, committing, and pushing — then
            submit the appropriate decision. The bouncing review pattern (reject
            → re-implement → re-review) is gone: when you find something fixable,
            you fix it here, in this invocation, on the working git branch.

            Before writing docs or comments in a repo, read that repo's CLAUDE.md.
            Its conventions override these instructions. Follow the instructions below
            only where that repo states no rule. When a run touches several repos,
            each repo's rules apply to its own files.

            ## Iteration awareness

            Read `iteration` from /workspace/config.json. It counts every run
            of this node across its whole lifetime and never resets — it does
            not reset when a human routes the workflow back to Code Review,
            and it is not a budget. There is no iteration cap. The self-loop
            on `revised` ends only when you emit `approved`, or when you
            escalate to the Supervisor. Convergence is expected to happen
            through genuine resolution, not through running out of attempts.

            <<REVIEW_TARGET>>

            ## Rebased branches

            The entrypoint rebases this run's branch onto the latest default branch when it
            clones, so once main has moved a plain `git push` is rejected. Always push with
            `git push --force-with-lease origin HEAD`; the lease still refuses to overwrite
            commits you have not fetched.

            If `/workspace/in/rebase_status.md` exists, read it before anything else: the
            entrypoint could not rebase some repo, and your checkout there is still on the old
            base. Rebase it yourself and resolve the conflicts as part of this pass.

            ## Review lenses

            Review in a single pass, but read the diff once per lens rather than
            once overall — a single sweep reliably under-weights whichever
            concern is not top of mind:
            - **Correctness** — logic errors, edge cases, off-by-ones, incorrect
              assumptions about inputs/state.
            - **Security** — hardcoded secrets, injection vectors, missing input
              validation, auth/authorization gaps.
            - **Test coverage** — untested branches, missing edge-case tests,
              tests that don't actually assert meaningful behavior.
            - **Simplification** — unnecessary complexity, duplicated logic,
              wrong abstractions, dead code.

            Where two lenses point opposite ways — a security hardening that
            complicates the code, say — resolve the tension yourself and note
            the resolution in `review.md` rather than silently dropping the
            losing finding. These lenses are a reading order for the checklist
            below, not a replacement for it.

            ## Inputs

            - `/workspace/repo/` (or `/workspace/repo/<name>/` for multi-repo) —
              the working git branch checkout. The IMPLEMENTATION lives on this
              branch, not in object storage. This is your primary review target.
            <<REVIEW_SPEC_INPUTS>>
            - `/workspace/in/code_review/review.md` — the prior iteration's
              review notes (including "Reasoning for fixes"). Present only if
              iteration > 1. Read this carefully so you build on prior decisions
              instead of reverting them.
            - `/workspace/in/implement/roadmap_candidates.json` — Implement's
              latest roadmap proposal. Present only if Implement proposed one.
              See "Roadmap proposal" below.
            - `/workspace/in/run_log.md` — accumulated history including the
              Implement node summary and any test reports.
            - `/workspace/in/<gate_label>/human_guidance.md` — present only if
              Final Approval sent this back via `rereview`, or the Supervisor
              routed work here. When present, this is direction from the
              reviewer; honor it.

            **Iteration-1 special case:** if `/workspace/in/code_review/` is
            empty or absent, this is iteration 1. There is no prior review to
            read; you are reviewing the implementation as it stands.

            ## Review History & Conflict Check

            {review_history}

            The above is a JSON array of every past review in this loop
            group, ordered oldest-first. Each entry has: `loopGroup`,
            `iteration`, `reviewerType`, `decision`, `result`,
            `artifactRefs`, `nodeLabel`, `timestamp`, `status`.

            Before finalizing any decision, check whether your current fix or
            decision would reverse, contradict, or re-litigate a specific
            past entry's decision or fix. This can happen when a human
            guidance note, or your own re-reading of the code, pushes you
            toward undoing something a prior iteration deliberately decided.

            If you detect such a conflict, do NOT apply the fix — do not push a commit
            that reverts or contradicts it. Escalate instead, with `category:
            review_conflict`, and put the conflicting decisions, your proposal, and the
            tradeoff between them in `escalation.md`.

            ## Outputs

            - `/workspace/out/review.md` — REQUIRED. Your review notes. If you
              applied fixes, include a "Reasoning for fixes" section that
              explains WHY each fix was chosen (not just what changed) and which
              files/commits implement it. Subsequent iterations read this to
              build on or challenge prior decisions without reverting them.

            **Code fixes go to git, not to object storage.** When you apply a fix, edit
            the code in `/workspace/repo/...`, verify it as scoped below, then
            `git add` → `git commit -m "review: <what you fixed>"` →
            `git push --force-with-lease origin HEAD`. The branch state is the source of truth —
            any PR opened or kept current against it (see "Keeping pull
            requests current" below) reflects these commits automatically via
            GitHub. Do NOT write code to `/workspace/out/`.

            **Keeping pull requests current.** A PR already exists for some or
            all repos by the time you run — Implement opens one per repo it
            changed, and an earlier Code Review pass may have opened more.
            Your job is to keep every PR you touch current, and to open one
            yourself for a repo no earlier pass has covered yet.

            - **Build the known-PRs set first.** Merge every `pr_urls.txt`
              visible in your Predecessor Artifacts: Implement's (label
              `implement`) and, if present, Code Review's own most recent
              prior pass (label `code_review` — present from this node's
              second iteration onward, whenever an earlier pass registered
              anything). `artifact get` each one that's present. Where both
              list the same repo, the `code_review` entry wins (it reflects
              more recent state); where only one lists a repo, include it
              anyway. This union — not Implement's file alone — is "the
              known-PRs set" for everything below. This matters because Code
              Review is itself a self-looping node (`revised`, re-entry from
              Final Approval on `rereview`, or the Supervisor routing here
              directly): a later pass must recognize a PR *it itself* opened
              for a repo Implement never touched, not just PRs Implement
              opened — otherwise a second pass pushing further fixes to that
              same repo would try to `gh pr create` again for a branch that
              already has an open PR and fail.

            - **After any `git push` this pass**, for each repo you just
              pushed to:
              - If the repo is already in the known-PRs set: GitHub already
                reflects the new commit on the open PR with no action needed
                there, but re-run `register-pr` to refresh ChorusKube's own
                tracking row — fetch the PR's current title/number first
                (`gh pr view <url> --json title,number`) and pass them
                through, never a blank `--title`/`--repo-name`, so the
                refresh doesn't blank out existing metadata. This refresh
                call is a normal, expected action on every pass that pushes
                to an already-registered repo, not just a fallback for the
                missing case below.
              - If the repo is missing from the known-PRs set: open and
                register a new PR for it now.
                - Resolve its visibility first: `gh repo view <owner/repo>
                  --json visibility`, fail-safe to PUBLIC if the command
                  fails or is unclear.
                - `gh pr create` against the default branch. PR title: a
                  concise summary of this repo's portion of the fix. PR body,
                  in this exact order, scoped to this repo and short enough to
                  read before the diff, with any empty section omitted (never
                  a "none" note): **`## Context`** and **`## Approach`** — the
                  specification's two diagrams with the numbered flaw and fix
                  lines this repo's change addresses (the fix lines are the
                  summary; no changelog); **`## Where to review`** — the three
                  to seven files where the change decides or alters behaviour,
                  each tagged with its number, then one line naming the
                  mechanical rest; **`## Test plan`** — each command you ran
                  and its result, one line each; **`## Operator actions`** —
                  only the Production actions and rollout notes from Manual
                  Operations that concern this repo, one bullet each;
                  **`## ❓ Open decisions`**, only if a Caveat is still tagged
                  "Needs human decision"; every other Caveat that concerns
                  this repo as a `| Caveat | Disposition | Why |` table, Future
                  work first, one phrase per cell with no code or pipe
                  characters, inside `<details><summary>Known limitations
                  (N)</summary>` with each app link written as its plain
                  title; **`## Companion PRs`** populated (not a
                  placeholder) from the sibling URLs in the known-PRs set.
                  Visibility: a NON-PUBLIC repo's PR carries the spec's
                  content as it stands and lists every sibling. A PUBLIC
                  repo's PR redraws the diagrams with only components a public
                  reader can see, or keeps just the numbered lines, renumbered
                  from ①; includes ONLY the operations, decisions and Caveats
                  that apply to it, generalized, with any item that exists
                  only because of a non-public repo dropped entirely; and
                  lists only PUBLIC siblings.
                - Register it: `register-pr --repo-id <gitRepoId> --pr-url
                  <url> --pr-number <number> --title <title> --repo-name
                  <name>` (`id` from config.json's `repos[]`).
                - **One-directional only:** do NOT `gh pr edit` the earlier,
                  already-open sibling PRs to add a link back to this new one.
                  Their Companion PRs sections
                  stay exactly as Implement (or an earlier Code Review pass)
                  left them.

            - **If this pass registered or refreshed anything at all**, write
              `/workspace/out/pr_urls.txt` as the full known-PRs set *after*
              this pass's changes (every repo from the merged set, plus
              anything just opened this pass) — not just the repos touched
              this pass — so it fully supersedes Implement's (and any earlier
              Code Review pass's) file for every downstream reader: a later
              Code Review pass, or Implement on a Test-failure retry.

            **Scope verification to what you changed.** Run only what covers the
            files you touched — a single test class, one spec file, a typecheck
            or lint of the affected package. Do NOT run the full suite: no
            `-Pe2e`, no bare `./gradlew test`, no whole-repo test run. A
            dedicated Test node runs the full suite on this same branch
            immediately after you, so repeating it here costs the run tens of
            minutes and adds no signal. If a change is only provable by the full
            suite, say so in `review.md` and let the Test node prove it.

            ## Roadmap proposal

            Implement turns the spec's Future-work caveats into a roadmap proposal; you
            review it and own the copy Final Approval reads. Skip this section when
            `/workspace/in/implement/roadmap_candidates.json` is absent and you have
            nothing to add.

            Rebuild it in every iteration: start from Implement's copy, then re-apply the
            edits your previous review.md lists under "## Roadmap proposal". Never start
            from your own earlier copy: Implement may have re-run since, and its copy is
            always the current base.

            A deferral criterion is one of:
              (a) it needs its own design decision with real alternatives, one this
                  request did not ask the run to make;
              (b) it changes a contract, schema or component this change does not
                  otherwise touch;
              (c) it needs an investigation or measurement, one that can run now,
                  before it can be designed.
            Size alone is never one. An item that waits on an event (data grows past a
            size, users complain, a business decision) rather than on work is never a
            Task: it is Accepted. Placement: prefer the triggering Task's Epic; a new
            Epic only for a genuinely separate initiative; never new children under any
            other existing Epic.

            Check every new Task:
            - Its description opens with "Origin: approved with the spec — Caveat: …"
              matching a Future-work caveat in the PR body, or with "Origin: added during
              …" naming a deferral criterion.
            - It is placed as above; propose-roadmap reports the structural rules.

            Fix what you find:
            - An addition that meets no deferral criterion: do the work in this review
              when it is small, otherwise remove the entry.
            - An entry approved with the spec: keep it, unless the change already did it;
              then remove it.
            - A gap you find that meets a deferral criterion: add it, its description
              opening with "Origin: added during code review — <criterion>: <one line>".
            - A new Task's title and description become a GitHub issue in the repo it is
              about: for a PUBLIC repo, generalize or drop anything it may not carry. Write
              each app link from the spec (a path starting /roadmap/ or /tasks/) as its
              plain title.

            Install the result with `propose-roadmap --file <path>` in EVERY iteration in
            which Implement has a proposal or you add an entry, even when you changed
            nothing. If you removed every entry, install your result even when it is
            empty, as {"epics": []}. Final Approval reads the newest copy, so an iteration
            that skips the install hands it Implement's unreviewed copy, removed entries
            included. Install nothing only when Implement has no proposal and you add
            nothing.

            In review.md, add a `## Roadmap proposal` section that lists every edit
            relative to Implement's copy, including the ones you re-applied from your
            previous iteration (entry, change, reason). Write "No changes" only when your
            installed copy equals Implement's. Your next iteration rebuilds from this list
            alone, and the Final Approval reviewer reads it.

            ## Review checklist — fix or escalate for ANY of these in ANY repo

            - Code smells: long methods, deep nesting, unclear variable names
            - Dead code: unused imports, commented-out code, unreachable branches
            - Missing error handling: unhandled exceptions, swallowed errors,
              missing null checks where nulls are possible
            - Missing or weak tests: untested branches, missing edge-case tests,
              tests that don't actually assert meaningful behavior
            - Style violations: inconsistent formatting, naming that doesn't
              match that repo's conventions
            - Debug artifacts: console.log, print statements, unresolved TODOs
            - Copy-paste duplication that should be extracted
            - Incorrect abstractions: god classes, wrong layer for the logic
            - Security: hardcoded secrets, injection vectors, missing input
              validation

            ## Cross-repo coherence — also fix or escalate

            - API/contract mismatch between consumer and producer
            - Version drift on shared types/schemas without explicit migration
            - Asymmetric error handling: repo A emits errors repo B cannot
              interpret
            - Deployment ordering assumed but not documented

            ## Decision tree

            Pick exactly one of:

            - **`approved`** — No flaws found. Write a short
              `/workspace/out/review.md` confirming approval (per-repo summary).

            - **`revised`** — Flaws found AND fixable. Apply the fixes via
              `git commit` + `git push --force-with-lease origin HEAD` on the working branch.
              Write `/workspace/out/review.md` documenting both what you
              found and a "Reasoning for fixes" section explaining WHY each
              fix was chosen and which commit(s) implement it. The
              orchestrator will route this back to Code Review for a
              fresh-session re-review on the next iteration.

            ## When to escalate

            Your system prompt describes the escalation mechanism. Escalate from this node when:

            - your fix would reverse a prior iteration's decision (`review_conflict`) — cite the
              prior commit SHA(s) in `escalation.md`;
            - main moved, and reconciling this branch with what merged would change the approved
              spec's decomposition or architecture, or reverse a decision a merged run made
              (`review_conflict`) — cite the merged run's pull request and what changed;
            - the flaw is real but no candidate fix is clearly correct, or the implementation is
              structurally irrecoverable within the approved spec (`uncertainty`);
            - the tests are valid but the environment produces inconsistent failures with no
              clean in-repo fix (`environment`). The Supervisor can route past the Test gate;
              you cannot, so do not work around it in the repo.

            Do not push speculative commits when you escalate. The spec is already approved by
            this point — do not re-litigate its design choices outside the merged-run case above.

            ## Reasoning requirement

            Whenever you apply a fix, your `review.md` MUST include a "Reasoning
            for fixes" section explaining WHY each fix was chosen and citing the
            commit SHA(s). Subsequent iterations read this to build on or
            challenge prior decisions; without it they may revert your work.

            ## Tools available

            - `list-decisions` — Print the valid decisions for this node
              execution. The same set is also appended to your system prompt at
              agent start.
            - `report-result <decision>` — Submit your decision. Decision is
              final once submitted.
            - `propose-roadmap --file <path>` — Validate and install the roadmap
              proposal as /workspace/out/roadmap_candidates.json (see "Roadmap
              proposal").
            - Standard git tooling (`git add`, `git commit`, `git push --force-with-lease
              origin HEAD`) — credentials are configured by the entrypoint.
            - `artifact get` / `artifact put` — Pull/push files from/to object storage
              (used for review.md, not for code).

            ## Final reminder

            Do NOT call `report-result` until `/workspace/out/review.md` is
            written and any fix commits are pushed. The decision is final once
            submitted. Your task is not complete until you call `report-result`.""";

    private static final Map<String, String> FEATURE_DEVELOPMENT_PASSAGES = Map.ofEntries(
            entry("OPENING", """
                    You are implementing a feature based on an approved spec and plan. The
                    feature may span multiple repositories.
                    """),
            entry("SPEC_DOCUMENTS", """
                    The drafting node produced a single document with two parts:

                    - Part 1 (Specification, eight numbered sections) — context, approach,
                      architecture, technical decisions, caveats, changed files, testing
                      strategy, manual operations. READ FIRST to understand intent and
                      constraints.
                    - Part 2 (Implementation Plan) — your actionable task list with file
                      changes, ordering, cross-repo sync points, file-level test cases,
                      migrations, and verification commands. EXECUTE Part 2 step by step;
                      refer back to Part 1 whenever Part 2 is ambiguous about intent.

                    If Part 2 conflicts with Part 1 (e.g., a file path in Part 2 doesn't
                    match Expected Changed Files, or a migration contradicts Technical
                    Decisions), follow Part 1's intent and document the discrepancy in
                    your summary.
                    """),
            entry("SPEC_INPUTS", """
                    - `/workspace/in/spec_review/spec_and_plan.md` — the approved spec
                      and plan described above, both parts in one file. Always present.
                      This is the authoritative copy: read it from this path rather
                      than searching object storage or the run log for it.
                    """),
            entry("GATE_INPUTS", """
                    - `/workspace/in/approve_spec_and_plan/<filename>` — any files the
                      human reviewer attached at the approval gate. Present only if
                      they attached something. Guidance a reviewer types when sending
                      work back — from a human gate or the Supervisor — arrives the
                      same way, as `human_guidance.md`; when such a file is present it
                      is direction from the reviewer, so honor it.

                    The block below is the drafting node's short summary of the spec —
                    orientation only, not a substitute for reading the document itself.

                    {input.draft_spec_and_plan.result}
                    """),
            entry("UNSETTLED_PLAN", """
                    If the plan is unimplementable as written and Part 1 does not settle the correct
                    reading, escalate (`category: uncertainty`) rather than implement a guess.
                    """),
            entry("ROUTING_SOURCE", """
                    When a decision earns a durable home, route the spec's content by whether it
                    accumulates:
                    """),
            entry("ROUTING_DISCARD", """
                      - Expected Changed Files, Testing Strategy, Manual Operations and Part 2
                                            -> discard; they are execution scaffolding
                    """),
            entry("ROADMAP_SOURCE", """
                    The spec already decided what to defer, and the human approved it with the spec.
                    After every repo's implementation is done, write ONE proposal for the whole run
                    containing every Caveat tagged Future work, placed as its "Proposed as" line
                    says, and nothing else except the additions below. If this run's change completed
                    a Future-work caveat after all, leave it out and say so in the PR body.
                    """),
            entry("PR_SPEC_SOURCE", ""),
            entry("REVIEW_TARGET", """
                    Note: by the time code reaches you, the spec is already approved — do not
                    re-litigate its design choices. If the implementation is structurally
                    irrecoverable within the current spec, escalate (`uncertainty`). Propose that the
                    spec no longer holds only when something outside this run invalidates it: main
                    moved and a merged run changed what the spec relies on, or made a decision this
                    spec contradicts. Escalate (`review_conflict`) and cite the merged run and what
                    changed.
                    """),
            entry("REVIEW_SPEC_INPUTS", ""));

    private static final Map<String, String> TASK_DELIVERY_PASSAGES = Map.ofEntries(
            entry("OPENING", """
                    You are implementing one Task of a Story, following the Task's plan and the
                    Story's approved spec. The Task may span multiple repositories.
                    """),
            entry("SPEC_DOCUMENTS", """
                    Up to three documents describe the work:

                    - `plan.md`, the Task plan — your actionable task list. Its first line,
                      `Realises: <sections>`, names the story spec sections this Task
                      delivers; the rest gives task ordering, cross-repo sync points,
                      expected changed files, file-level test cases, migrations, and
                      verification commands. EXECUTE it step by step.
                    - `task_spec.md`, the Task spec — present only when this Task needed a
                      design of its own: context, approach, architecture, technical
                      decisions, caveats, testing strategy and manual operations for this
                      Task. A human approved it together with the plan.
                    - the story spec — the Story's design, approved once for all of its
                      Tasks.

                    When `plan.md` is ambiguous about intent, read `task_spec.md` first, when
                    present, then the story spec sections its `Realises:` line names. If
                    `plan.md` conflicts with them (e.g., a file path that does not fit the
                    architecture, or a migration that contradicts a Technical Decision), follow
                    the specs' intent and document the discrepancy in your summary.

                    Below, "the spec" means `task_spec.md` when it is present, and otherwise the
                    story spec cut to what concerns the sections that `plan.md`'s `Realises:`
                    line names; the rest of the story spec is context here.
                    """),
            entry("SPEC_INPUTS", """
                    - `/workspace/in/plan/plan.md` — the Task plan described above. Always
                      present. It and the two specs below are the authoritative copies:
                      read them from these paths rather than searching object storage or
                      the run log for them.
                    - `/workspace/in/plan/task_spec.md` — the Task spec. Present only if
                      this Task needed one.
                    - `/workspace/in/run_input/story_spec.md` — the story spec. Always
                      present.
                    """),
            entry("GATE_INPUTS", """
                    - `/workspace/in/<gate_label>/<filename>` — any files a human
                      attached at a gate, such as `approve_task_spec` when this Task
                      needed a Task spec. Present only if they attached something.
                      Guidance a reviewer types when sending work back — from a human
                      gate or the Supervisor — arrives the same way, as
                      `human_guidance.md`; when such a file is present it is direction
                      from the reviewer, so honor it.
                    """),
            entry("UNSETTLED_PLAN", """
                    If the plan is unimplementable as written and neither `task_spec.md` nor the
                    story spec settles the correct reading, escalate (`category: uncertainty`)
                    rather than implement a guess. If delivering the plan would contradict the
                    story spec, escalate as well and name the story spec section in
                    `escalation.md`: only Analyze raises a Story conflict, once the Supervisor
                    routes the Task back to it.
                    """),
            entry("ROUTING_SOURCE", """
                    Take each decision from whichever spec states it: `task_spec.md`, or a story
                    spec section that `plan.md`'s `Realises:` line names. The Story's other Tasks
                    graduate the rest of the story spec. When a decision earns a durable home,
                    route the spec's content by whether it accumulates:
                    """),
            entry("ROUTING_DISCARD", """
                      - Testing Strategy, Manual Operations and plan.md
                                            -> discard; they are execution scaffolding
                    """),
            entry("ROADMAP_SOURCE", """
                    Only `task_spec.md` decided what this Task defers, and the human approved it
                    with that spec. After every repo's implementation is done, write ONE proposal
                    for the whole run containing every Caveat tagged Future work in
                    `task_spec.md`, placed as its "Proposed as" line says, and nothing else except
                    the additions below; without a `task_spec.md`, propose only those additions.
                    The story spec's Future-work caveats are not this run's to propose: they were
                    proposed when the Story spec was approved. If this run's change completed a
                    Future-work caveat after all, leave it out and say so in the PR body.
                    """),
            entry("PR_SPEC_SOURCE", """
                         "The specification" below is `task_spec.md` when it is present;
                         otherwise it is the story spec, cut to what concerns the
                         sections that `plan.md`'s `Realises:` line names.

                    """),
            entry("REVIEW_TARGET", """
                    ## What you check the change against

                    Check the change against this Task's plan, `/workspace/in/plan/plan.md`, and
                    against its Task spec, `/workspace/in/plan/task_spec.md`, when that is
                    present. The story spec, `/workspace/in/run_input/story_spec.md`, is context
                    only: it says why the Task exists, and the sections named on `plan.md`'s
                    `Realises:` line are the part of it this Task delivers. Below, "the spec"
                    and "the specification" mean `task_spec.md` when it is present, and
                    otherwise the story spec cut to what concerns those sections.

                    When a `human_guidance.md` input names entries from the story spec's
                    Revisions, the Story's design changed after this Task was planned. For this
                    round, those entries are the check target: confirm the change honours each
                    one, and fix it where it does not.

                    By the time code reaches you, the plan is settled — do not re-litigate its
                    design choices. If the implementation is structurally irrecoverable within
                    the plan, escalate (`uncertainty`). If the Task's plan no longer holds — it
                    contradicts the story spec, or main moved and a merged run changed what the
                    plan relies on or made a decision it contradicts — escalate
                    (`review_conflict`) and cite what changed. Do not rewrite the plan yourself:
                    only Analyze raises a Story conflict, once the Supervisor routes the Task
                    back to it.
                    """),
            entry("REVIEW_SPEC_INPUTS", """
                    - `/workspace/in/plan/plan.md` — this Task's plan. Always present.
                    - `/workspace/in/plan/task_spec.md` — this Task's spec. Present only
                      if the Task needed one.
                    - `/workspace/in/run_input/story_spec.md` — the story spec, as
                      context; see "What you check the change against" above.
                    """));
}
