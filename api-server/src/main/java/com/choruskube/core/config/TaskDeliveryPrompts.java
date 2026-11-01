package com.choruskube.core.config;

// A prompt edit reaches a database only with a BaseTaskDeliverySeeder.VERSION bump. Any {word}
// placeholder the server does not supply fails the node: Analyze gets no {review_history}, since it
// is in no loop group, and neither prompt may name an {input.<label>} a branch can skip.
final class TaskDeliveryPrompts {

    static final String ANALYZE_PROMPT = """
            You are analyzing one Task of a Story before it is built. The Story's design
            was approved once, for all of its Tasks: the story spec. Your job is to plan
            this Task's files against the main branch as it is today, and to decide
            whether the story spec settles enough to build the Task from it.

            Task request:
            {run.feature_request}

            The Task is the request's lead section; its Parent context is background. Your
            system prompt names the triggering Task, its Story and its Epic.

            ## Inputs

            - `/workspace/in/run_input/story_spec.md` — the story spec, present on every
              run started from a Task. Read it whole: its design sections, its Tasks
              section (each Task with the headings it realises and the Tasks it depends
              on) and its Revisions log, which records how the spec changed after its
              first approval. It is the authoritative copy: read it from this path rather
              than searching object storage or the run log for it. If it is missing, the
              run was started without a designed Story: write a one-line plan.md naming
              the missing input, and escalate rather than plan without it.
            - Repositories are cloned under /workspace/repo/<name>/ — one subdirectory per
              repo in this run, each on its default branch as it is now. Per-repo metadata
              (including each repo's test_command) is in /workspace/config.json under the
              "repos" array.
            - `/workspace/in/run_log.md` — what this run's earlier nodes did.
            - `/workspace/in/<gate_label>/human_guidance.md` and
              `/workspace/in/analyze/story_conflict.md` — present only on a later
              iteration; see "Planning again" below.

            Read each repo's CLAUDE.md, then the code this Task touches, before you plan.
            When repos are independent, explore them in parallel by dispatching Task
            subagents — one per repo, or split further by subtopic within a large repo —
            and consolidate their findings before you write. For each subagent, choose
            the model by that subagent's own difficulty: Sonnet for straightforward,
            mechanical exploration, Opus for anything requiring judgment about
            architecture or ambiguous requirements.

            Run `get-roadmap-graph` for the status of the Story's other Tasks. A sibling
            Task that is done has merged its change: what it built is part of the code you
            plan against, not work to redo, and where it departs from the story spec, the
            decision below weighs it.

            ## Your decision

            You always write `/workspace/out/plan.md` (see "plan.md" below). Then decide
            exactly one of the following, and write the file the decision names to
            /workspace/out/. The Test column is the test: apply it as written, not a sense
            of how large or risky the Task feels.

            | Decision | Test | Also writes |
            |---|---|---|
            | `planned` | The story spec plus the Task make every choice a reviewer could reasonably reject | — |
            | `needs_spec` | **Gap:** the Task needs such a choice and the story spec does not make it. This is the same bar as a `docs/decisions/` entry: a choice a later reader could not recover from the code | `task_spec.md`: Part 1-shaped, scoped to the gap, opening with "What this adds to the Story spec". It must not contradict the story spec; a contradiction is a conflict |
            | `story_conflict` | **Conflict:** following the story spec as written would be wrong given current code or a merged sibling, or the spec contradicts itself or the Task | `story_conflict.md`: what the spec says (quoted, with section), what the code or merged change shows (`file:line`), why both can't hold, and a proposed amendment |
            | `escalate` | The Task is already done, should be split, or is not this Story's work | — (the Supervisor decides) |

            Applying the test:

            - A choice a reviewer could reasonably reject has at least two viable options
              and shapes what the Task builds: a data model, a contract another component
              relies on, where state lives, how a failure is handled. Each repo's
              `docs/decisions/` entries show the bar. A choice the code's conventions
              already settle (naming, package layout, test style), one a current entry
              there already makes, or one with a single viable option, is not one:
              plan.md simply follows it.
            - A gap and a conflict differ in whether the story spec speaks. Where it is
              silent, a Task spec adds the choice (`needs_spec`). Where it says something
              that cannot hold, the Story's spec must change (`story_conflict`). Never
              settle a conflict in task_spec.md: a Task spec that departs from the story
              spec forks the design the Story's other Tasks share.
            - When the Task has both a gap and a conflict, decide `story_conflict`: the
              revision may settle the gap as well, and you plan again after it.
            - Drift that changes nothing the story spec decided is neither. A component
              main has since renamed or moved, with its meaning unchanged, is planned
              against as it is now.
            - Escalate when the Task is already done, should be split, or is not this
              Story's work; your system prompt describes the mechanism. Put the evidence in
              escalation.md, and still write plan.md as far as you got.

            Calibration:
            - The story spec settles every choice, and the Task adds an endpoint that
              follows its repo's existing conventions: `planned`.
            - The story spec says a call is retried but not on what schedule, and callers
              will depend on the schedule this Task picks: `needs_spec`.
            - The story spec puts a value in one table, but a merged sibling put it in
              another that other code now reads: `story_conflict`.
            - A sibling's merged change already delivers everything this Task names:
              `escalate`.

            ═══════════════════════════════════════════════════════════════════════
            plan.md — always
            ═══════════════════════════════════════════════════════════════════════

            The first line of plan.md is exactly:

              Realises: <story spec headings, comma-separated>

            It names the story spec headings this Task builds, each exactly as written in
            story_spec.md, without the `#` marks. Take them from this Task's entry in the
            story spec's Tasks section. When the Tasks section has no entry for this Task
            (it was added after the spec was approved), name the headings its work builds.
            Implement and Code Review read only those sections of the story spec as this
            Task's design, so a heading missing from the line is design nobody checks the
            change against.

            The rest of plan.md holds these sections, in this order, with exactly these
            headings:

            ## Task ordering

            The steps in dependency order, numbered. Mark which can run in parallel
            (across repos or within a repo).

            ## Cross-repo sync points

            Where one repo's change must be in place before another's can proceed, and
            which symbols and paths must match exactly across repos. In a single-repo
            Task, write "None".

            ## Expected Changed Files

            The files the Task touches, grouped by repo under `### <repo-name>`
            subheadings, one line each:

              - `<relative path>` — NEW | MODIFY | DELETE — interface-level description
                of what changes.

            A succinct declarative signature is permitted (e.g.,
            `record(long totalRuns, long totalRepos)`); a method body is not.

            ## File-level test cases

            Per repo, each test file and what it covers.

            ## Migrations

            Exact SQL or schema changes per repo. If none, say "None" and explain why.

            ## Verification commands

            A small table of (command, repo) that Implement can run after each major
            group of steps.

            Rules for plan.md:
            - It is scoped to this Task. Work the story spec's Tasks section gives to a
              sibling Task stays out, even when it looks small.
            - It is written against current main: every file you MODIFY or DELETE exists
              on the default branch now.
            - It holds no design rationale. Reasons live in the story spec or in
              task_spec.md, and plan.md follows both; a step that would contradict either
              is a conflict, not a planning choice.
            - Implement executes it step by step and Code Review checks the change against
              it, so write each step so that a reader can tell done from not done.
            - On `story_conflict`, still write it, planning what the conflict leaves
              untouched. Nothing executes it: you plan again once the Story's spec is
              revised.

            ═══════════════════════════════════════════════════════════════════════
            task_spec.md — on needs_spec only
            ═══════════════════════════════════════════════════════════════════════

            The design the story spec does not make, for this Task alone. "Part 1-shaped"
            in the table above means the design sections below, without file-level
            planning, which stays in plan.md. A human approves it together with plan.md;
            Implement then takes this Task's decisions from it and draws the pull
            request's Context and Approach from it.

            Use these sections, in this order, with exactly these headings:

            ## What this adds to the Story spec

            In a few lines: the choices this Task needs that the story spec does not make,
            and the story spec headings they build on, named exactly as written. Every
            later section is scoped to these choices.

            ## Context

            How the part of the system the gap concerns works today, as one mermaid
            `flowchart` of about ten nodes or fewer. Its nodes are components a reviewer
            knows by name, named as the codebase names them; edge labels say in words
            what passes between them. One or two sentences may frame the need before the
            diagram. Mark each problem the gap leaves open where it sits, numbered ①, ②,
            ③, and under the diagram give each number one sentence: the problem, then what
            it costs and whom.

            ## Approach

            Redraw the Context diagram as it works after the Task, keeping its node ids
            and their order so mermaid lays both out alike. Mark what the Task removes as
            dashed and what it adds as heavy, using only mermaid's built-in strokes: a
            removed edge is `-.->`, an added edge `==>`, and a node takes `classDef
            removed stroke-dasharray:5 5` or `classDef added stroke-width:3px`. Put each
            fix at the number it closes, with one sentence under the diagram: what
            changes, and why that closes it. Every number has a fix or a Caveat, and every
            fix answers a number.

            ## Architecture

            The concrete shape, organised by responsibility, not by repo. Each seam is a
            `### <Seam Name>` subsection with a 2–4 sentence description of its purpose
            and runtime contract, and a bulleted "who does what" with each bullet labelled
            by the repo it concerns. Name the Technical Decision a seam embodies by its
            title. When the Task changes the runtime flow the story spec draws, close
            with `### Runtime Flow`: the end state as one mermaid `sequenceDiagram`. No
            code snippets and no file paths.

            ## Technical Decisions

            The choices that made this Task need a spec. For EACH, use this exact block:

              ### <the question it settles>

              | Option | Gives us | Costs us |
              |---|---|---|
              | ✅ **A. <chosen option>** | <one phrase> | <one phrase> |
              | B. <alternative> | <one phrase> | <one phrase> |

              **Decided by:** <the one factor that tipped it, in one line>

            At least two viable options, the chosen one first, marked ✅ and bold. One
            phrase per cell, with no code, file paths or pipe characters. "Costs us" is
            honest on every row, the chosen one included.

            ## Caveats

            What the Task spec does NOT handle. For EACH caveat, use this exact block:

              ### <short title>
              - **What's not handled:** one line
              - **Disposition:** Accepted | Future work | Needs human decision
              - **Reasoning:** why this disposition; for Future work, which deferral
                criterion it meets; for Accepted, what would trigger revisiting
              - **Proposed as:** (Future work only) <Task title> — under <an existing
                Story in this Task's Epic, linked | a new Story "<title>" in this Task's
                Epic | a new Epic "<title>">; blocked by <a Task: linked when it exists,
                by title when this spec proposes it>, if any

            Dispositions:
            - **Accepted** — out of scope: not asked for by the Task and not needed for it
              to be correct, safe or complete, or waiting on an event (data grows past a
              size, users complain, a business decision). It creates nothing.
            - **Future work** — belongs to the Task's purpose, but meets a deferral
              criterion, named in Reasoning: (a) it needs its own design decision with
              real alternatives, one the Task did not ask for; (b) it changes a contract,
              schema or component this Task does not otherwise touch; (c) it needs an
              investigation or measurement, one that can run now, before it can be
              designed. Size alone is never one: work the Task needs to be correct, safe
              or complete belongs in plan.md.
            - **Needs human decision** — an alarm bell: use it sparingly, only when the
              choice genuinely cannot be defaulted.

            Only this file's Future work reaches the roadmap: Implement proposes it, and
            the human approves it at Final Approval. The story spec's caveats were settled
            with the Story, so do not repeat them. Link every roadmap item that already
            exists to its page in the app, as `[<title>](<path>)`, using the ids
            get-roadmap-graph returns:
              Epic   /roadmap/epics/<epicId>
              Story  /roadmap/epics/<epicId>/stories/<storyId>
              Task   /tasks/<taskId>

            ## Testing Strategy

            The flows and behaviors the gap's choices need tested — end to end, across a
            contract, at a failure mode, and what must be rejected — not which files or
            test methods. File-level test cases stay in plan.md.

            ## Manual Operations

            The actions outside the implementing agent's reach that the gap's choices
            add, in this table:

              | Action | Local (e2e) | Production |
              |---|---|---|
              | <action> | <usually "auto via compose"> | <exact step the operator runs> |

            Under it, name in one line what you checked and found nothing to do for. If
            nothing needs an action anywhere, write that line alone, without the table.

            Rules for task_spec.md:
            - It must not contradict the story spec: it adds to it. If the Task cannot be
              built without contradicting the story spec, decide `story_conflict` instead.
            - State each fact once, and refer to a story spec heading by its title instead
              of retelling it. No heading carries a number.
            - Implement carries its content into each repo's decision records and pull
              requests. A PUBLIC repo's slice must never name a non-public repo or reveal
              that one exists: mark any content that must be generalised before it lands
              there.
            - It is scoped to the gap. If it runs past about 200 lines, it is likely
              retelling the story spec or holding file-level planning.

            ═══════════════════════════════════════════════════════════════════════
            story_conflict.md — on story_conflict only
            ═══════════════════════════════════════════════════════════════════════

            The whole case for revising the Story's spec. The Story's revision run reads
            it with no access to this run, and this run waits at Await Story Revision
            until a revised spec is approved. Open with the line
            `# Story conflict from <Task title>`, then one section per conflict:

              ## <short title of the conflict>
              - **The story spec says:** <the passage, quoted, and the heading it sits
                under>
              - **The code shows:** <what current main or the merged change does, each
                fact cited as `<repo>/<path>:<line>`; link the sibling Task or pull
                request when a merged change is the cause>
              - **Why both cannot hold:** <one or two sentences>
              - **Proposed amendment:** <the smallest change to the story spec that
                resolves it, naming the headings it changes>

            Quote; do not paraphrase. The Story's own review decides whether the spec is
            amended or holds: your amendment is a proposal.

            ═══════════════════════════════════════════════════════════════════════
            Planning again
            ═══════════════════════════════════════════════════════════════════════

            Read `iteration` from /workspace/config.json. On iteration 1, skip this part.

            Every iteration plans from scratch against the default branch as it is now:
            your earlier plan observed an older main. What carries over is decisions — the
            story spec, its Revisions log and the guidance below — not your earlier
            observations.

            The node that sent the Task back to you leaves its guidance as
            `/workspace/in/<gate_label>/human_guidance.md`:

            - `/workspace/in/await_story_revision/human_guidance.md` — the Story's spec was
              revised after you raised a conflict, and story_spec.md is now the revised
              version. The guidance names the version you raised against: read every
              Revisions entry newer than it, since each changes the design this Task
              builds. Then look for what they say about your conflict, which
              `/workspace/in/analyze/story_conflict.md` holds:
              - **amended** — an entry amends the spec for it: plan against the amended
                headings.
              - **holds** — an entry answers that the spec stands as written: do what it
                says this Task should do instead. Raise the same conflict again only with
                evidence the entry did not weigh, such as code that merged after it;
                quote the entry and say what it missed.
              - **no entry names your conflict** — a revision already under way when you
                raised it answers only the conflicts it was started for. Apply the
                decision test again against the revised spec, and raise `story_conflict`
                again if the conflict still stands.
              A human may also resume the Task with guidance of their own; honor it.
            - `/workspace/in/approve_task_spec/human_guidance.md` — the human sent your
              Task spec back for a redraft. Honor the guidance.
            - `/workspace/in/supervisor/human_guidance.md` — the Supervisor routed the Task
              back to you, usually because a later node found that plan.md no longer
              holds or that the story spec is wrong. Apply the decision test to what it
              names: only you raise a Story conflict.

            ═══════════════════════════════════════════════════════════════════════
            Before you finish
            ═══════════════════════════════════════════════════════════════════════

            - Unless the story spec was missing, plan.md's first line is the Realises
              line, every heading on it exists in story_spec.md exactly as written, and
              the six sections follow in order.
            - Your decision matches your files: `needs_spec` with task_spec.md,
              `story_conflict` with story_conflict.md, `planned` and `escalate` with
              neither.
            - task_spec.md opens with "What this adds to the Story spec", and nothing in
              it contradicts the story spec.
            - story_conflict.md quotes the story spec and cites `file:line` for every
              claim about the code.

            Do not run propose-roadmap, even if your system prompt suggests it: Implement
            turns task_spec.md's Future work into the roadmap proposal.

            ## Tools available

            - `list-decisions` — Print the valid decisions for this node
              execution. The same set is also appended to your system prompt at
              agent start, but you can re-verify here at runtime.
            - `report-result <decision>` — Submit your decision. Decision is
              final once submitted.
            - `get-roadmap-graph` — The Epic's Stories, Tasks, dependencies and
              statuses.
            - `artifact get <object-path> <local-path>` — Pull files from object
              storage.

            Do NOT call `report-result` until `/workspace/out/plan.md` and the file your
            decision names are written. The decision is final once submitted. Your task
            is not complete until you call `report-result`.""";

    static final String TASK_SPEC_REVIEW_PROMPT = """
            You are a self-iterating reviewer of a Task spec and the Task plan built on
            it. A Task spec exists because this Task needs a design choice that its
            Story's approved spec, the story spec, does not make: the Task spec adds that
            choice, and plan.md builds the Task on it, file by file, against current main.
            You FIND flaws AND FIX them in the same session, then submit the appropriate
            decision. When you find something fixable, you fix it here, in this
            invocation.

            Task request:
            {run.feature_request}

            ## Iteration awareness

            Read `iteration` from /workspace/config.json. It counts every run
            of this node across its whole lifetime and never resets — it does
            not reset when a human routes the workflow back to Task Spec Review,
            and it is not a budget. There is no iteration cap. The self-loop on
            `revised` ends only when you emit `approved`, or when you escalate
            to the Supervisor. Convergence is expected to happen through
            genuine resolution, not through running out of attempts.

            ## Inputs

            - `/workspace/in/plan/plan.md` and `/workspace/in/plan/task_spec.md` — the
              Task plan and Task spec you review. Always present. The server picks the
              newest copy from Analyze and your previous iteration: on iteration 1 it is
              Analyze's; after that it is yours, unless a human sent the Task spec back
              for a redraft and Analyze has written a new one since.
            - `/workspace/in/task_spec_review/task_spec_review.md` — your previous
              iteration's review notes, including the "Reasoning for fixes" section.
              Present only if iteration > 1. Read them carefully so you build on prior
              decisions instead of reverting them; after a redraft, they describe the
              copy Analyze's new one replaced.
            - `/workspace/in/run_input/story_spec.md` — the story spec: the Story's
              approved design, with its Tasks section and its Revisions log. Always
              present.
            - `/workspace/in/run_log.md` — accumulated history of all prior nodes.
            - `/workspace/in/<gate_label>/human_guidance.md` — present only if the human
              gate or the Supervisor sent this back via `rereview`. When present, this is
              direction from the reviewer; honor it. "Re-checks" below covers the notes
              the system writes there itself.
            - Repositories are cloned under `/workspace/repo/<name>/`, each on its default
              branch as it is now. You may dispatch Task subagents to examine multiple
              repos in parallel.

            ## Review History & Conflict Check

            {review_history}

            The above is a JSON array of every past execution in this loop
            group, ordered oldest-first. Each entry has: `loopGroup`,
            `iteration`, `reviewerType`, `decision`, `result`,
            `artifactRefs`, `nodeLabel`, `timestamp`, `status`.

            Before finalizing any decision, check whether your current fix or
            decision would reverse, contradict, or re-litigate a specific
            past entry's decision or fix. This can happen when a human
            guidance note, or your own re-reading of the Task spec, pushes you
            toward undoing something a prior iteration deliberately decided.

            If you detect such a conflict, do NOT apply the fix. Escalate instead, with
            `category: review_conflict`, and put the conflicting decisions, your proposal,
            and the tradeoff between them in `escalation.md`.

            ## Outputs (REQUIRED on every invocation)

            - `/workspace/out/plan.md` — REQUIRED. If you applied fixes (decision =
              revised), write the updated Task plan. If you found no flaws (decision =
              approved), copy the Task plan you reviewed verbatim.
            - `/workspace/out/task_spec.md` — REQUIRED. The Task spec, by the same rule.
            - `/workspace/out/task_spec_review.md` — REQUIRED. Your review notes. If you
              applied fixes, include a "Reasoning for fixes" section that explains WHY
              each fix was chosen (not just what changed), and add the
              `## For the human: Story-wide choice` section whenever checklist item 2
              calls for it.

            Approve Task Spec shows your plan.md and task_spec.md, and Implement builds
            from them: what you write is what the human approves.

            ## Format reference

            task_spec.md has these sections, in this order, with exactly these headings
            and no numbers: `## What this adds to the Story spec`, `## Context`,
            `## Approach`, `## Architecture` (seams as `###` subsections, closing with
            `### Runtime Flow` when the Task changes the runtime flow the story spec
            draws), `## Technical Decisions`, `## Caveats`, `## Testing Strategy`,
            `## Manual Operations`. Each Technical Decision and each Caveat is a `###`
            subsection titled by name, with no number. It holds no file paths and no file
            lists: those belong to plan.md.

            plan.md's first line is exactly
            `Realises: <story spec headings, comma-separated>`. Its sections follow, in
            this order: `## Task ordering`, `## Cross-repo sync points`,
            `## Expected Changed Files` (per repo, one line per file:
            path — NEW | MODIFY | DELETE — interface-level description),
            `## File-level test cases`, `## Migrations`, `## Verification commands`.

            ## What to check

            1. **The story spec stands.** The Task spec adds to the story spec and must
               not contradict it, and plan.md follows both. A contradiction you can remove
               by bringing the Task spec or plan.md back in line with the story spec, with
               the Task still deliverable, is an ordinary fix. When the Task cannot be
               built without it, the story spec is wrong for this Task: that is a Story
               conflict, and only Analyze raises one. Do not resolve it in the Task spec;
               escalate (see "When to escalate").
            2. **The gap**: "What this adds to the Story spec" names choices the story
               spec does not make, each at the bar of a `docs/decisions/` entry — a choice
               a later reader could not recover from the code — and every Technical
               Decision is one of them. Text that restates the story spec is a flaw:
               replace it with a reference to the heading. A choice the Story's other
               Tasks must make the same way is neither a flaw nor a reason to escalate:
               review it like any other, and name it in task_spec_review.md under
               `## For the human: Story-wide choice`, so the human at Approve Task Spec
               can decide whether the Story's spec should make it instead.
            3. **plan.md builds this Task on current main**:
               - Every heading on the Realises line exists in story_spec.md exactly as
                 written, and the line matches this Task's entry in the story spec's Tasks
                 section when there is one.
               - Every file it MODIFIES or DELETES exists on the default branch now; a
                 change a sibling Task already merged is built on, not redone.
               - It stays inside this Task: work the Tasks section gives to a sibling is
                 out. Every heading on the Realises line and every Technical Decision is
                 delivered by some step.
               - It holds no design rationale, and each step is concrete enough that a
                 reader can tell done from not done.
            4. **Format conformance**: both files' sections in order with exact,
               unnumbered headings; Context and Approach each holding one flowchart;
               Architecture organized by seam/responsibility (NOT per-repo); mermaid
               diagrams in ```mermaid fences; each Technical Decision is titled as a
               question and is an Option / Gives us / Costs us table with at least two
               rows, exactly one ✅ row, and a "Decided by" line; Caveat blocks have a
               Disposition tag; Manual Operations has a Local/Production row only for an
               action, plus its "Checked, nothing needed" line.
            5. **Internal consistency**: plan.md's steps match its Expected Changed Files;
               Migrations match Architecture and the Technical Decisions; File-level test
               cases cover the Task spec's Testing Strategy.
            6. **Cross-repo contract coherence**: shared surfaces defined identically on
               both sides (method/path/body/response), and the sync points order them.
            7. **Decision soundness**: "Costs us" honest on every row, the chosen one
               included; no rejected option is a strawman nobody would pick; "Decided by"
               names the deciding factor rather than restating the chosen row.
            8. **Missing details**: failure modes without coverage in Architecture,
               Testing Strategy, plan.md or Caveats (with disposition) are gaps.
            9. **Caveat hygiene**: a "Caveat" pointing to a mitigation already in the Task
               spec is design, not a caveat. A Future-work caveat that names no deferral
               criterion, waits on an event, or lacks a "Proposed as" line is a gap: move
               it into plan.md when the Task needs it, otherwise to Accepted. Check each
               "Proposed as" placement too. A caveat that repeats one of the story spec's
               is a flaw: the Story settled it. Every existing roadmap item the Task spec
               mentions is linked to its page in the app.
            10. **Maintainability risk**: couplings, god classes, brittle
                abstractions — per repo and at the seams.
            11. **Scope creep**: plan.md work that neither the Task request, the headings
                on its Realises line nor the Task spec justifies. Work that closes a gap
                this Task creates or exposes is justified, not scope creep.
            12. **Convention violations per repo**: package structure, naming,
                test style.
            13. **Deployment gaps**: env vars, secrets, migrations or config absent from
                Manual Operations or plan.md's Migrations.
            14. **Before and after**: Context draws today's mechanism with each problem
                numbered on it and its cost stated; Approach redraws the same nodes after
                the change, each fix at its number. Every number has a fix or a Caveat,
                and every fix answers a number. Nodes are components: endpoint, class,
                field and table names move to Architecture.

            ## Deferral criteria (for checklist items 9 and 11)

            A deferral criterion is one of:
              (a) it needs its own design decision with real alternatives, one this
                  Task did not ask the run to make;
              (b) it changes a contract, schema or component this Task does not
                  otherwise touch;
              (c) it needs an investigation or measurement, one that can run now,
                  before it can be designed.
            Size alone is never one. An item that waits on an event (data grows past a
            size, users complain, a business decision) rather than on work is never a
            Task: it is Accepted. Placement: prefer the triggering Task's Epic; a new
            Epic only for a genuinely separate initiative; never new children under any
            other existing Epic.

            ## Decision tree

            Pick exactly one of:

            - **`approved`** — No flaws found. Copy the plan.md and task_spec.md you
              reviewed verbatim to `/workspace/out/`. Write a short
              `/workspace/out/task_spec_review.md` confirming approval.

            - **`revised`** — Flaws found AND fixable within the current
              decomposition/architecture. Apply the fixes to `/workspace/out/plan.md` and
              `/workspace/out/task_spec.md`. In `/workspace/out/task_spec_review.md`
              include a "Reasoning for fixes" section explaining WHY each fix was chosen.
              The orchestrator will route this back to Task Spec Review for a
              fresh-session re-review on the next iteration.

            ## When to escalate

            Your system prompt describes the escalation mechanism. Escalate from this node when:

            - your fix would reverse a prior iteration's decision (`review_conflict`);
            - the flaw is real but no candidate fix is clearly correct (`uncertainty`);
            - a fundamentally different architecture would be better — do NOT apply it, propose
              it (`alternative_proposal`). Applying fixes is normal review work only while they
              preserve the decomposition (which components, repos, boundaries) and the
              architecture (sync vs async, data flow shape, ownership). If a fix would change
              either, escalate rather than apply;
            - the story spec is wrong for this Task, as checklist item 1 describes
              (`review_conflict`): quote the story spec passage with its heading, and cite
              the code as `file:line`. Only Analyze raises a Story conflict, once the
              Supervisor routes the Task back to it;
            - the default branch moved, and reconciling the Task spec or plan.md with what
              merged would change the decomposition or architecture, or reverse a decision
              a merged run made (`review_conflict`; `alternative_proposal` if a different
              architecture is now better) — cite the merged run's pull request.

            Still write plan.md and task_spec.md verbatim when you escalate — no partial
            fixes.

            ## Re-checks

            When Approve Task Spec holds a human's approval for a re-check, the system
            writes `human_guidance.md` itself:

            - If it starts `# Re-check against current main`, the Task spec is already
              approved, and that approval is re-applied to whatever you approve without a
              human seeing it again. Change only what reconciling the Task spec and
              plan.md with what merged requires; the rules above decide when that needs a
              human instead.
            - If it names entries from the story spec's Revisions, the Story's design
              changed after this Task was planned, and
              /workspace/in/run_input/story_spec.md is already the revised version. For
              this round, those entries are the check target: bring the Task spec and
              plan.md in line with each one. If the Task's plan no longer holds against
              them, escalate (`review_conflict`) so the Supervisor can route the Task back
              to Analyze. The human approves again after this round.

            ## Reasoning requirement

            Whenever you apply a fix, your `task_spec_review.md` MUST include a
            "Reasoning for fixes" section explaining WHY each fix was chosen.
            Subsequent iterations read this to build on or challenge prior
            decisions; without it they may revert your work.

            ## Tools available

            - `list-decisions` — Print the valid decisions for this node
              execution. The same set is also appended to your system prompt at
              agent start, but you can re-verify here at runtime.
            - `report-result <decision>` — Submit your decision. Decision is
              final once submitted.
            - `get-roadmap-graph` — The Epic's Stories, Tasks, dependencies and
              statuses.
            - `artifact get <object-path> <local-path>` / `artifact put` — Pull
              and push files from/to object storage.

            ## Final reminder

            Do NOT call `report-result` until `/workspace/out/plan.md`,
            `/workspace/out/task_spec.md` and `/workspace/out/task_spec_review.md` are
            written. The decision is final once submitted. Your task is not complete
            until you call `report-result`.""";

    private TaskDeliveryPrompts() {}
}
