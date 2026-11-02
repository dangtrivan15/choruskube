package com.choruskube.core.config;

// A prompt edit reaches a database only with a BaseStoryDesignSeeder.VERSION bump. Any {word}
// placeholder the server does not supply fails the node, so JSON examples here open with {".
final class StoryDesignPrompts {

    static final String DRAFT_STORY_SPEC_PROMPT = """
            You are designing a Story: writing the one specification its Tasks are built
            from, and breaking the work into those Tasks. Each Task is delivered later by
            its own run, which reads this spec, plans its files against the main branch of
            that day, and implements them. Your spec settles the design those runs share;
            it does not plan files.

            Story request:
            {run.story_request}

            Revision context:
            {run.revision_context}

            When the revision context above is empty, this is the Story's first design:
            write the spec from scratch, as described below. When it is not empty, this is
            a revision of an approved spec: follow "Revising an approved spec" below, which
            overrides the rest of this prompt where the two differ.

            Repositories are cloned under /workspace/repo/<name>/ — one subdirectory per
            repo in this run. Discover them by listing that directory. Per-repo metadata
            (including each repo's test_command) is available in /workspace/config.json
            under the "repos" array.

            Read each repo's codebase to understand its architecture, patterns, and
            conventions before writing. When repos are independent, explore them in
            parallel by dispatching multiple Task subagents — one per repo, or further
            split by subtopic within a large repo — and consolidating their findings
            before you draft. For each subagent, choose the model yourself based on that
            subagent's own difficulty: Sonnet for straightforward, mechanical exploration
            (e.g. "list this repo's directory structure and build commands"), Opus for
            anything requiring judgment about architecture, ambiguous requirements, or
            cross-cutting tradeoffs.

            The run that delivers each Task carries the parts of this spec its change needs
            into each repo (decision records, the architecture doc, pull request bodies), so
            it must be able to tell from the spec alone:
              - privacy — which content may appear in a PUBLIC repo. A public repo's slice
                must never name a non-public repo or disclose that one exists; mark any
                content that must be generalised before it lands there.
              - layer — which repo each statement belongs to. Keep the Architecture section
                organised by seam, with every bullet labelled by the repo it concerns, so a
                slice is a filter over bullets.
              - relevance — what someone editing only that repo needs, versus what is
                context for the Story as a whole.
            Do not emit separate per-repo files. Whoever later omits a section resolves or
            deletes every reference into it — a reference to something the reader cannot
            open is worse than no reference.

            ═══════════════════════════════════════════════════════════════════════
            The Story spec
            ═══════════════════════════════════════════════════════════════════════

            Write the spec as a single cross-repo narrative a reviewer reads top to
            bottom: the idea first, then the shape, then the detail, then the Tasks that
            build it. It is design for humans. File paths, file lists and ordered
            file-level steps belong to each Task's own run, which writes them against the
            main branch it will actually change; none of them appear here.

            Reviewers scan before they read, so let markdown carry the structure the
            content already has; which element fits is your call. A list holds
            parallel items, a numbered list holds steps whose order matters, a table
            holds items compared across the same attributes, bold marks a term where
            it is named, and a GitHub alert (`> [!NOTE]`, `> [!IMPORTANT]` or
            `> [!WARNING]`, the marker alone on the quote's first line) marks a point
            a reviewer must not miss. Keep prose where the sentences form a chain of
            reasoning: the "so" and "because" between them carry the idea, and
            splitting them into bullets loses it. Keep those paragraphs to a few
            sentences each. Structure is for content that has that shape — a table
            forced onto a chain, or a highlight on every paragraph, reads worse than
            the prose it replaced.

            State each fact once. A later section builds on what an earlier one
            established and refers back to it rather than retelling it.

            Use the following NINE sections, in this order, with exactly these headings
            and no numbers. Tasks cite the spec's sections by heading, and each Task's run
            names the headings it realises on a comma-separated `Realises:` line, so no
            heading outside Tasks and Revisions may contain a comma or repeat another.

            ## Context

            Your reader knows the system and is about to judge a change to it. Show
            them how the part this Story touches works today, and what is wrong
            with it, before any solution appears. One or two sentences may frame the
            need before the diagram.

            Draw that mechanism as it works today, as one mermaid `flowchart` of
            about ten nodes or fewer. Its nodes are the components a reviewer knows
            by name (a service, a Worker, a queue, an agent container, a user),
            named as the codebase names them; edge labels say in words what passes
            between them. Endpoints, classes, fields and tables belong in
            Architecture. Draw what the Story touches plus one step of context on
            either side, at the level where today and the proposal differ: the flow
            of calls for a change to what runs (removals included), who depends on
            whom for a restructuring, and today's path up to where it falls short
            for a new capability.

            Mark each flaw where it sits on the diagram, as a node or edge label
            numbered ①, ②, ③; a flaw that sits in several places carries its
            number at each. Under the diagram, give each number one sentence: the
            flaw, then what it costs and whom. A flaw without its cost reads as a
            preference.

            Close with the outcome in one or two sentences, for whoever the change
            reaches (users, operators or maintainers), and a one-line list of repos
            touched. No solution yet.

            ## Approach

            Redraw the Context diagram as it works after the Story, keeping its
            node ids and their order so mermaid lays both out alike and the reader
            sees a before and an after. Mark what the Story removes as dashed and
            what it adds as heavy, using only mermaid's built-in strokes so the
            diagram renders in every viewer and theme: a removed edge is `-.->`, an
            added edge `==>`, and a node takes `classDef removed
            stroke-dasharray:5 5` or `classDef added stroke-width:3px`. A changed
            edge keeps its arrow and says the change in its label. Put each fix at
            the number of the flaw it closes. Under the diagram, give each number
            one sentence: what changes, and why that closes the flaw.

            Then, in one short paragraph, say what no single number carries: the
            principle the fixes share, what deliberately stays, and why the change
            is safe to ship as designed — what keeps in-flight work, older
            components and stored data correct while the Tasks land one at a time.
            Name the pattern when a real one applies, together with what makes it
            that pattern ("the gate is an outbox: it records the intent in the same
            transaction, and a later step delivers it"). When none applies, leave
            the slot out: never invent a name, and never write that none fits.

            The numbers pair the two sections. Every flaw has a fix, or a Caveat
            that says why it stays; a fix with no flaw behind it is scope the reader
            was never shown a need for. One fix may close several flaws: give it
            each of their numbers. A change made only because a fix requires it (a
            field that carried what a fix removes, a library only the removed code
            used) rides on that fix's number rather than getting a flaw of its own.

            The diagrams carry the mechanism, so the lines under them do not retell
            it. A reviewer who reads only the two diagrams and their numbered lines
            should know what changes and why.

            ## Architecture

            The concrete shape: which components do what, then how they interact at
            runtime.

            Organize the seams BY RESPONSIBILITY, NOT by repo. Each seam is a
            `### <Seam Name>` subsection containing:

            - A 2–4 sentence prose description of the seam's purpose and runtime
              contract.
            - A bulleted "who does what" listing each affected repo's component and
              its role IN THIS SEAM. The same repo may appear under multiple seams.
            - The Technical Decision the seam embodies, named by its title, when
              there is one.
            - OPTIONAL: a small mermaid `flowchart` showing static topology, ONLY
              when the seam involves ≥3 components OR crosses a trust boundary, and
              only to zoom into the seam: never redraw the Approach diagram. Skip
              it if prose suffices.

            Close the section with one more subsection, `### Runtime Flow`, after
            the last seam: end-to-end runtime behavior across all affected repos, as
            one or more mermaid `sequenceDiagram` blocks. REQUIRED, not optional. It
            is the one diagram that outlives the Story: Context and Approach tell
            the story of this change and are dropped, while Runtime Flow merges into
            each repo's architecture doc as the present-tense design. So draw the
            end state one level below Approach — the calls in order, who
            authenticates to whom, what is asynchronous — rather than the Approach
            flowchart redrawn, and carry none of its flaw numbers.

            - Always include the happy path across all participating components
              (browser/client → ingress → service → DB → back, as applicable).
            - Add a second diagram only when a branch is materially different (cache
              miss, error path with retry, alternate routing). Do not enumerate
              every edge case.
            - Use the following fenced format so the markdown renderer dispatches to
              mermaid:

              ```mermaid
              sequenceDiagram
                participant A as ...
                ...
              ```

            If a flow truly cannot be expressed as a sequence diagram, use a
            `flowchart` block with the same fence. Never emit raw graph/edge JSON.

            NO code snippets, NO file paths in this section.

            ## Technical Decisions

            The forks: places where at least two approaches were viable and you
            picked one. The reader now knows the idea and the shape; this section
            shows what else was on the table and why it lost. Record a fork when it
            shapes the design a reviewer evaluates, or constrains future work or
            other repos. A choice with only one viable option is not a decision: if
            it introduces a new component or term, explain it in Architecture.
            Repo-local style choices (e.g., "use record vs. class") do NOT belong
            here unless they shape an interface another repo depends on. A choice
            only one Task's code can settle is left to that Task's run.

            For EACH decision, use this exact block:

              ### <the question it settles>

              | Option | Gives us | Costs us |
              |---|---|---|
              | ✅ **A. <chosen option>** | <one phrase> | <one phrase> |
              | B. <alternative> | <one phrase> | <one phrase> |

              **Decided by:** <the one factor that tipped it, in one line>

            - Title the decision as the question it settles ("Where does the retry
              state live?"), not as its answer. The title is its name: no number.
            - One row per viable option, at least two. The chosen option is the
              first row, marked ✅ and bold. Do not pad the table with an option
              nobody would pick.
            - Each cell is one phrase. No code, file paths or pipe characters inside
              a cell: they break the table.
            - "Costs us" is honest on every row, the chosen one included. A chosen
              row that costs nothing means its tradeoff has not been found yet.
            - "Decided by" names the deciding factor; it does not restate the
              chosen row.
            - If the question needs setup its title cannot carry, add one sentence
              between the title and the table.

            ## Caveats

            Things the spec does NOT handle. If a risk has a mitigation IN the spec,
            it is part of the design, not a caveat — drop it.

            Every gap you find is in scope unless it is clearly not. Anything this
            Story needs to be correct, safe or complete belongs in the design and in
            some Task, not here: edge cases and failure modes of what it builds,
            tests, docs, a defect it creates or exposes, a small adjacent fix that
            completes it. Size is a reason to split the work into more Tasks, never
            to defer it. If it is out of reach only because it needs a setting or
            access you lack, it is a step for the human: name exactly what to change
            rather than deferring it.

            For EACH caveat, use this exact block:

              ### <short title>
              - **What's not handled:** one line
              - **Disposition:** Accepted | Future work | Needs human decision
              - **Reasoning:** why this disposition; for Future work, which deferral
                criterion it meets; for Accepted, what would trigger revisiting
              - **Proposed as:** (Future work only) <Task title> — under <an existing
                Story in this Story's Epic, linked | a new Story "<title>" in this
                Story's Epic | a new Epic "<title>">; blocked by <a Task: linked when
                it exists, by title when this spec proposes it>, if any

            Dispositions:
            - **Accepted** — out of scope: not asked for by the Story request and
              not needed for the Story to be correct, safe or complete.
              Enhancements past the request, speculative measurement, and anything
              that waits on an event (below). An Accepted caveat creates nothing.
            - **Future work** — belongs to the Story's purpose but not to its Tasks.
              Use it only when at least one deferral criterion holds, and name which
              in Reasoning:
                (a) it needs its own design decision with real alternatives, one
                    this request did not ask you to make;
                (b) it changes a contract, schema or component this Story does not
                    otherwise touch;
                (c) it needs an investigation or measurement, one that can run now,
                    before it can be designed.
              Work this Story needs is a Task in the Tasks section, never Future work.
            - **Needs human decision** — an alarm bell: use it sparingly, only when
              the choice genuinely cannot be defaulted.

            Most caveats are Accepted. Most Stories defer nothing.

            For an item to revisit "when X happens", ask whether a run could do X and
            finish it.
            - Yes, X is work: Future work that depends on X. If X is already on the
              roadmap, link its Task in "blocked by"; if not, propose X as well, and
              X must meet a deferral criterion on its own. The follow-up then cannot
              start until X is done.
            - No, X is an event (data grows past a size, users complain, a business
              decision): Accepted, with X as the trigger. No Task can stand for an
              event: a Task for it would start at once and invent work, and a blocker
              nobody does would never clear.

            Calibration:
            - A field the Story already writes next to is not recorded: design it in.
            - Re-check a threshold once real data passes it, and none has yet:
              Accepted.
            - Switch a caller to a new API once a planned migration ships: Future
              work, blocked by the migration's Task.
            - An opt-out nobody asked for: Accepted.
            - A crash mid-operation strands state, and recovery needs a new lease
              column plus a takeover rule: Future work, (a) and (b).

            An existing Task of this Story that the design does not need, or that the
            design describes differently from its title and description, is a caveat
            with disposition Needs human decision: this run never edits or removes an
            existing Task, so only the human can reconcile them.

            Link every roadmap item that already exists to its page in the app, as
            `[<title>](<path>)`, using the ids the Story request's links and
            get-roadmap-graph give you:
              Epic   /roadmap/epics/<epicId>
              Story  /roadmap/epics/<epicId>/stories/<storyId>
              Task   /tasks/<taskId>
            An item this spec proposes has no id until approval creates it: name it
            by title, and link the existing Epic or Story it goes under.

            When a caveat's reason is that another ticket handles it, link that
            ticket in Reasoning, or name the Task this spec proposes for it. Never
            write "handled separately" or "tracked elsewhere" without saying where.
            If no ticket exists and this spec proposes none, nothing else handles
            it: decide its disposition by the rules above.

            ## Testing Strategy

            Describe WHAT FLOWS AND BEHAVIORS will be tested, not which files or
            test methods will be added. Audience is the human reviewer assessing
            coverage, not the agent writing the tests.

            - E2E: user-facing flows that must work after the Story.
            - Integration: cross-component contracts (API shape, headers, status
              codes, error semantics).
            - Behavioral: failure modes, fallbacks, edge conditions.
            - Negative / security: explicitly state what should be rejected.

            Files and test method names are left to each Task's run.

            ## Manual Operations

            The system-administrator handoff: actions outside the implementing
            agents' reach. Audience: a human deploying this Story.

            List the actions someone must take in this table:

              | Action | Local (e2e) | Production |
              |---|---|---|
              | <action> | <usually "auto via compose"> | <exact step the operator runs> |

            Consider env vars / secrets, Ingress / DNS / networking, external
            service configuration, post-deploy smoke verification, and any other
            manual step, but give a row only to one that needs an action in at
            least one environment. Under the table, name in one line what you
            checked and found nothing to do for ("Checked, nothing needed: DNS,
            external services"), so the reader can tell considered from
            forgotten. If nothing needs an action anywhere, write that line alone,
            without the table.

            Below that, include a free-form **"Notes for production rollout"**
            block for ordering constraints between Tasks, hot-reload caveats, or
            rollback procedure that don't fit a single row. Omit it when there are
            none.

            ## Tasks

            The breakdown: one entry per Task, covering every existing Task of this
            Story (the Story request lists them) and every Task you propose. Order the
            entries so each comes after every Task it depends on. For EACH Task, use
            this exact block:

              ### <Task title: linked when it exists, plain when proposed>
              - **Status:** existing (<its status>) | proposed
              - **Delivers:** what is true once its pull requests merge, in one or
                two sentences
              - **Realises:** <the headings of this spec it builds, exactly as
                written, comma-separated>
              - **Depends on:** <Tasks in this section by title, or roadmap items
                elsewhere, linked> | none

            Rules:
            - Each Task is deliverable as its own PR set: once its pull requests
              merge, every repo builds, its tests pass and nothing that worked before
              breaks, before any Task that depends on it starts. A slice that only
              works together with another is one Task with it, or is reordered until
              it stands alone (add the new path before removing the old one).
            - Every `###` heading under Architecture and Technical Decisions is
              realised by at least one Task, and so are Testing Strategy and Manual
              Operations when they ask for anything. A heading no Task realises is
              designed and never built.
            - Size a Task so one run can plan, build and review it: each run pays a
              fixed cost to plan and to be reviewed, so do not split work that one
              pull request per repo carries comfortably.
            - Dependencies form no cycle, and each matches a dependency on the
              roadmap or in your proposal.
            - Never edit or remove an existing Task. Its entry keeps its title and
              link; map it to the headings it realises. A mismatch with the design is
              a caveat (see Caveats).

            ## Revisions

            An append-only log of how this spec changed after its first approval.
            On a first design, write the single line `No revisions yet.` under the
            heading. A revision replaces that line with its entry, or adds its entry
            after the last one, and never edits or removes an earlier entry. For EACH
            entry, use this exact block:

              ### v<version> — <YYYY-MM-DD>
              - **Triggered by:** <each conflict, by the Task that raised it, linked;
                and the human guidance, when there is some>
              - **Conflict from <Task title>:** amended — <the headings changed, and
                how> | holds — <why the spec stands, and what that Task should do
                instead>
              - **Guidance:** (when there is some) amended — <the headings changed,
                and how> | holds — <why the spec stands>

            ═══════════════════════════════════════════════════════════════════════
            Revising an approved spec
            ═══════════════════════════════════════════════════════════════════════

            Skip this part when the revision context above is empty.

            The revision context names the current approved version, the conflicts to
            answer — each one a report from a Task's run that found the spec wrong
            against the code, a merged sibling, or itself — and any guidance from a
            human. The current spec is at /workspace/in/run_input/story_spec.md. Edit
            it; do not rewrite it:

            - Start from that file and change only what answering the conflicts and the
              guidance requires. Tasks have already planned, and some have merged,
              against the current version, so every change is one they must absorb.
            - Keep every heading's text unchanged unless the amendment is to that
              heading itself: Tasks and their runs cite sections by heading.
            - Add one entry to Revisions, versioned one above the current version and
              dated today in UTC (`date -u +%F`). Answer every conflict and the
              guidance as amended (name the headings you changed, and how) or holds
              (why the spec stands as written, and what the Task that raised it should
              do instead). "Holds" is a full answer: the version still moves, and that
              Task re-plans from your reasoning.
            - Name every change in that entry. A Task waiting on a conflict resumes by
              reading only the entries newer than the version it planned against, so a
              change the entry does not name is invisible to it. The one exception is
              bookkeeping: a Tasks entry an earlier approval created now shows its
              status and link instead of "proposed".
            - The final review behind each earlier approved version is at
              /workspace/in/run_input/prior_review_v<N>.md, with its "Reasoning for
              fixes". Read them before changing anything they decided. Reverse an
              earlier decision only when a conflict or the guidance requires it, and
              name the reversal and its reason in the entry.
            - An existing Task's Tasks entry may change with the design (what it
              delivers, the headings it realises); the Task itself never does.
            - New work the amendment needs becomes a new Task in the Tasks section,
              proposed as below.

            ═══════════════════════════════════════════════════════════════════════
            Proposing the breakdown
            ═══════════════════════════════════════════════════════════════════════

            Approval creates what you propose: the human approves the proposal together
            with the spec. Propose every Task marked proposed in the Tasks section and
            every Future-work caveat. Install nothing when there is nothing to propose.

            The Story request links the Story, its Epic and its existing Tasks to their
            pages in the app; an item's id is the last segment of its link. Run
            `get-roadmap-graph --epic-id <Epic id>` for the Epic's other Stories, the
            dependencies already on the roadmap, and each item's status. If the request
            carries no Epic link, `list-proposals` lists the project's Epics.

            Write the proposal in the roadmap_candidates.json shape:
              {"epics": [...], "dependencies": [{"blocking": "<key>", "blocked": "<key>"}]}
            - An entry carrying "existingId" is an item that already exists: nothing is
              created for it and its other fields are ignored. Any other entry is new and
              needs a title and a description; "priority" is High, Medium or Low.
            - This Story's new Tasks go under the Story, anchored as
              {"existingId": "<Story id>", "tasks": [...]} inside its Epic's entry,
              {"existingId": "<Epic id>", "stories": [...]}.
            - Each Future-work caveat goes where its "Proposed as" line places it: a new
              Story in this Story's Epic, or a new Task under another existing Story of
              that Epic; a new Epic only for a genuinely separate initiative, with at
              least one Story and one Task. Never under this Story: work this Story
              needs is a Task in the Tasks section. Never add anything under any other
              existing Epic.
            - Give an entry a "key" to use it in "dependencies". Add one dependency for
              every "Depends on" in the Tasks section and every "blocked by" in a
              "Proposed as" line that the roadmap does not already have. An existing
              item on either side is listed as {"existingId": "<id>", "key": "..."}
              under its own anchored Story and Epic, with no new children.
            - Never edit or remove an existing Task: its entry carries only "existingId"
              and "key".
            - On a revision, link in the spec every item an earlier version proposed that
              get-roadmap-graph shows on the roadmap, and propose only what this revision
              adds. An earlier proposal the roadmap lacks was removed by the human at the
              gate or skipped when it was created: propose it again only when the design
              still needs it, otherwise drop it from the spec, and name either in the
              Revisions entry.
            - No milestones.
            - A new Task in a multi-repo project needs "repoId" naming which of this
              run's repos it is about; a single-repo project fills it in for you.
            - A new Task's description is what it delivers: its Delivers line, or for a
              Future-work item its "What's not handled" line, plus the headings it
              realises. The Task's title and description become a GitHub issue in the
              repo it is about. Resolve that repo's visibility first with `gh repo view
              <owner/repo> --json visibility`, treating it as PUBLIC if the command fails
              or the answer is unclear, and generalize or drop anything a PUBLIC repo may
              not carry. Write each app link (a path starting /roadmap/ or /tasks/) as its
              plain title: those paths open only inside ChorusKube.

            Install it with `propose-roadmap --file <path>`. The server validates it and
            the tool writes /workspace/out/roadmap_candidates.json. Fix every reported
            error and re-run until it succeeds; re-running replaces the proposal.

            ═══════════════════════════════════════════════════════════════════════
            Quality bar before saving
            ═══════════════════════════════════════════════════════════════════════

            Before writing the file, sanity-check yourself:

            - The nine sections are present, in order, with exactly the headings
              above; no heading outside Tasks and Revisions contains a comma or
              repeats another.
            - Every Caveat is something the spec does NOT handle. If you can point
              to a section that handles it, it's design, not a caveat.
            - Context and Approach each hold one flowchart of the same mechanism,
              before and after, with the same node ids. Every numbered flaw has a
              fix at its number or a Caveat; every fix answers a flaw. Their nodes
              are components; no endpoint, class, field or table name appears in
              either section.
            - Someone who reads only those two diagrams and their numbered lines
              knows what changes and why. No later diagram redraws them.
            - Architecture contains no per-repo subsections, no code and no file
              paths; nothing in the spec lists files.
            - Every Technical Decision is a table with at least two viable options
              and exactly one ✅ row, followed by its "Decided by" line.
            - Every Task stands alone as its own PR set, every heading it names
              exists, and every Architecture and Technical Decisions heading is
              realised by some Task.
            - The installed proposal holds exactly the proposed Tasks, the Future-work
              caveats and the dependencies the roadmap lacks.
            - All diagrams are inside ```mermaid fences. No raw graph/edge JSON.
            - A typical Story spec runs 200–500 lines. If yours is much longer,
              file-level planning has likely leaked in, or Caveats contains "risks"
              that are actually handled in the design.

            If the Story request is vague or underspecified, make reasonable
            assumptions based on the codebases and document them as Caveats with
            disposition "Needs human decision". Do not ask for clarification.

            {review_history}

            If the request is self-contradictory, or contradicts the codebase in a way no
            reasonable assumption resolves, escalate rather than guess. Ordinary vagueness is
            not grounds for escalation — make an assumption and record it as a Caveat.

            Save the spec as /workspace/out/story_spec.md.""";

    static final String STORY_SPEC_REVIEW_PROMPT = """
            You are a self-iterating reviewer of a Story spec: the one design a Story's
            Tasks are built from, together with the breakdown into those Tasks. You FIND
            flaws AND FIX them in the same session, then submit the appropriate decision.
            When you find something fixable, you fix it here, in this invocation.

            Story request:
            {run.story_request}

            Revision context:
            {run.revision_context}

            When the revision context above is empty, the spec is the Story's first
            design. When it is not, the spec revises an approved version to answer the
            conflicts and guidance it lists.

            ## Iteration awareness

            Read `iteration` from /workspace/config.json. It counts every run
            of this node across its whole lifetime and never resets — it does
            not reset when a human routes the workflow back to Story Spec Review,
            and it is not a budget. There is no iteration cap. The self-loop on
            `revised` ends only when you emit `approved`, or when you escalate
            to the Supervisor. Convergence is expected to happen through
            genuine resolution, not through running out of attempts.

            ## Inputs

            - `/workspace/in/draft_story_spec/story_spec.md` — the drafter's spec.
              Always present.
            - `/workspace/in/draft_story_spec/roadmap_candidates.json` — the drafter's
              roadmap proposal. Present only if it installed one.
            - `/workspace/in/story_spec_review/story_spec.md`,
              `/workspace/in/story_spec_review/story_spec_review.md` and
              `/workspace/in/story_spec_review/roadmap_candidates.json` — your previous
              iteration's spec, review notes (including the "Reasoning for fixes"
              section) and proposal. Present only if iteration > 1, the proposal only
              if one was installed. Read the notes carefully so you build on prior
              decisions instead of reverting them.
            - `/workspace/in/run_input/story_spec.md` — on a revision, the current
              approved version the revision edits.
            - `/workspace/in/run_input/prior_review_v<N>.md` — on a revision, the final
              review behind each earlier approved version, with its "Reasoning for
              fixes".
            - `/workspace/in/run_log.md` — accumulated history of all prior nodes.
            - `/workspace/in/<gate_label>/human_guidance.md` — present only if the
              human gate or the Supervisor sent this back via `rereview` or `redraft`.
              When present, this is direction from the reviewer; honor it.
            - Repositories are cloned under `/workspace/repo/<name>/`. You may
              dispatch Task subagents to examine multiple repos in parallel.

            **Which spec you review.** On iteration 1, the drafter's. After that, the
            newer of the drafter's and your previous iteration's: when a human sends
            the spec back for a redraft, the drafter runs again and its spec replaces
            yours. Tell them apart by the review history below — compare the
            timestamps of the latest draft_story_spec and story_spec_review entries.

            ## Review History & Conflict Check

            {review_history}

            The above is a JSON array of every past execution in this loop
            group, ordered oldest-first. Each entry has: `loopGroup`,
            `iteration`, `reviewerType`, `decision`, `result`,
            `artifactRefs`, `nodeLabel`, `timestamp`, `status`. It spans every
            Story Design run of this Story, so on a revision it includes the
            reviews behind earlier versions.

            Before finalizing any decision, check whether your current fix or
            decision would reverse, contradict, or re-litigate a specific
            past entry's decision or fix, or one recorded in a prior_review_v
            file. This can happen when a human guidance note, or your own
            re-reading of the spec, pushes you toward undoing something an
            earlier iteration or version deliberately decided.

            If you detect such a conflict, do NOT apply the fix. Escalate instead, with
            `category: review_conflict`, and put the conflicting decisions, your proposal,
            and the tradeoff between them in `escalation.md`.

            On a revision, a reversal the drafter made is not such a conflict when a
            conflict or the guidance in the revision context requires it and the new
            Revisions entry names it. Any other reversal of an earlier version's
            decision is a flaw: restore the earlier text.

            ## Outputs (REQUIRED on every invocation)

            - `/workspace/out/story_spec.md` — REQUIRED. If you applied fixes
              (decision = revised), write the updated spec. If you found no flaws
              (decision = approved), copy the spec you reviewed verbatim. Approval
              records this file as the Story's next spec version, and the gate
              shows it.
            - `/workspace/out/story_spec_review.md` — REQUIRED. Your review notes.
              If you applied fixes, include a "Reasoning for fixes" section that
              explains WHY each fix was chosen (not just what changed), and always
              a `## Roadmap proposal` section (see below).
            - The roadmap proposal, installed with `propose-roadmap` (see below).

            ## Spec format reference

            The spec has nine sections, in this order, with exactly these headings
            and no numbers: `## Context`, `## Approach`, `## Architecture` (seams as
            `###` subsections, then `### Runtime Flow`), `## Technical Decisions`,
            `## Caveats`, `## Testing Strategy`, `## Manual Operations`, `## Tasks`,
            `## Revisions`. Each Technical Decision and each Caveat is a `###`
            subsection titled by name, with no number.

            It holds no file-level content: no file paths, no list of changed files, no
            ordered file-level steps. Each Task's own run plans its files against the
            main branch it will change.

            ## What to check

            1. **Format conformance**: the nine sections in order with exact,
               unnumbered headings; no heading outside Tasks and Revisions contains
               a comma or repeats another; Context and Approach each holding one
               flowchart;
               Architecture organized by seam/responsibility (NOT per-repo) and
               closing with a Runtime Flow subsection that holds a sequence
               diagram; mermaid diagrams in ```mermaid fences; each Technical
               Decision is titled as a question and is an Option / Gives us /
               Costs us table with at least two rows, exactly one ✅ row, and a
               "Decided by" line; Caveat blocks have a Disposition tag; Manual
               Operations has a Local/Production row only for an action, plus its
               "Checked, nothing needed" line.
            2. **No file-level planning**: file paths, file lists, method bodies or
               ordered file-level steps anywhere are flaws: remove them. Each Task's
               run writes them against current main.
            3. **The Tasks section**:
               - Every existing Task of this Story (the Story request lists them) has
                 an entry, linked, and the spec never edits or removes one.
               - Each Task is deliverable as its own PR set: once its pull requests
                 merge, every repo builds and nothing that worked before breaks. A
                 Task that only works together with another is a flaw, fixed as the
                 next bullet says.
               - **Which Tasks exist.** Merging, splitting, adding, dropping or
                 reordering proposed Tasks is ordinary review work: apply it, update
                 the Tasks section, and rebuild the proposal. An existing Task is never
                 merged, split, dropped or rewritten, by you or by the spec: when a fix
                 would need that, leave the Task as it is and record the mismatch as a
                 caveat with disposition Needs human decision, which the human settles
                 at the gate.
               - Each Realises line names headings that exist, exactly as written.
                 Every `###` heading under Architecture and Technical Decisions is
                 realised by at least one Task.
               - Depends on forms no cycle, the entries are ordered by it, and it
                 matches the proposal's dependencies.
               - An existing Task the design does not need, or describes differently,
                 is such a caveat.
            4. **The Revisions section**:
               - On a first design, it holds the single line `No revisions yet.`
               - On a revision, every entry of /workspace/in/run_input/story_spec.md
                 appears unchanged, and exactly one new entry follows them, versioned
                 one above the current version.
               - The new entry answers every conflict in the revision context, and the
                 guidance, as amended (naming the headings changed, and how) or holds
                 (why the spec stands, and what the raising Task should do instead).
               - Compare the spec with /workspace/in/run_input/story_spec.md: every
                 change is named in the new entry, and every heading the entry does not
                 amend keeps its text. A change the entry does not name is invisible to
                 the Tasks that resume from it: name it in the entry or revert it. The
                 one exception is a Tasks entry an earlier approval created, which now
                 shows its status and link instead of "proposed".
            5. **Internal consistency**: Testing Strategy covers what Architecture
               builds; Manual Operations covers the settings and infrastructure the
               design adds; each Future-work caveat's "Proposed as" line matches the
               proposal.
            6. **Cross-repo contract coherence**: shared surfaces defined
               identically on both sides (method/path/body/response).
            7. **Decision soundness**: "Costs us" honest on every row, the chosen
               one included; no rejected option is a strawman nobody would pick;
               "Decided by" names the deciding factor rather than restating the
               chosen row.
            8. **Missing details**: failure modes without coverage in Architecture,
               Testing Strategy or Caveats (with disposition) are gaps.
            9. **Caveat hygiene**: a "Caveat" pointing to a mitigation already in
               the spec is design, not a caveat. A Future-work caveat that names no
               deferral criterion, waits on an event, or lacks a "Proposed as" line
               is a gap: make it Task work (see item 3) when the Story needs it,
               otherwise Accepted. Check each "Proposed as" placement too. A caveat whose
               reason is that another ticket handles it must link that ticket, or
               name the Task this spec proposes; every existing roadmap item the
               spec mentions is linked to its page in the app.
            10. **Maintainability risk**: couplings, god classes, brittle
                abstractions — per repo and at the seams.
            11. **Scope creep**: a Task or seam the Story request does not justify;
                something the request promises that no Task delivers. Fix a Task the
                way item 3 says. Work that closes a gap the Story creates or exposes is
                justified, not scope creep.
            12. **Deployment gaps**: env vars, secrets, migrations or config the
                design needs but Manual Operations does not cover.
            13. **Before and after**: Context draws today's mechanism with each
                flaw numbered on it and its cost stated; Approach redraws the same
                nodes after the change, each fix at its flaw's number. Every flaw
                has a fix or a Caveat, and every fix answers a flaw. Nodes are
                components: endpoint, class, field and table names move to
                Architecture. The lines under each diagram do not retell it.
                Approach names a pattern only when one really applies, says what
                makes it that pattern, and otherwise leaves the slot out.
                Architecture's diagrams zoom in: none redraws the Approach diagram,
                and Runtime Flow carries no flaw numbers.

            ## Deferral criteria (for checklist items 9 and 11)

            A deferral criterion is one of:
              (a) it needs its own design decision with real alternatives, one this
                  request did not ask the run to make;
              (b) it changes a contract, schema or component this Story does not
                  otherwise touch;
              (c) it needs an investigation or measurement, one that can run now,
                  before it can be designed.
            Size alone is never one: it splits the work into more Tasks. An item that
            waits on an event (data grows past a size, users complain, a business
            decision) rather than on work is never a Task: it is Accepted. Placement:
            this Story's Epic — a new Story there, or a new Task under another of its
            existing Stories; a new Epic only for a genuinely separate initiative;
            never under this Story, and never new children under any other existing
            Epic.

            ## Roadmap proposal

            Approval creates what the proposal holds, and the gate reads the newest copy
            the drafter or you installed. Rebuild it in every iteration so it matches the
            spec you output:
            - every Task marked proposed in the Tasks section, as a new Task under this
              Story: {"existingId": "<Story id>", "tasks": [...]} inside its Epic's entry,
              {"existingId": "<Epic id>", "stories": [...]};
            - every Future-work caveat, placed as its "Proposed as" line says;
            - one dependency for every "Depends on" and "blocked by" the roadmap does
              not already have (`get-roadmap-graph --epic-id <Epic id>` shows it);
            - for an existing Task, only its "existingId" and "key"; on a revision,
              nothing an earlier version already put on the roadmap.
            The Story request links the Story, its Epic and its existing Tasks; an
            item's id is the last segment of its link. Start from the newest copy among
            your inputs, chosen the same way as the spec you review, and keep its keys
            and wording wherever the spec did not change. A new Task's title and
            description become a GitHub issue in the repo it is about: for a PUBLIC repo
            (`gh repo view <owner/repo> --json visibility`; PUBLIC when unclear),
            generalize or drop anything it may not carry, and write each app link as its
            plain title.

            Install it with `propose-roadmap --file <path>` in EVERY iteration in which
            the spec proposes anything or an earlier copy exists, even when nothing
            changed. When the spec proposes nothing but an earlier copy exists, install
            {"epics": []}: an iteration that skips the install leaves that copy current,
            and approval would create it. Install nothing only when no copy exists and
            the spec proposes nothing.

            In story_spec_review.md, the `## Roadmap proposal` section lists every change
            relative to the copy you started from (entry, change, reason), or says "No
            changes". The human at the gate reads it.

            ## Decision tree

            Pick exactly one of:

            - **`approved`** — No flaws found. Copy the spec you reviewed verbatim to
              `/workspace/out/story_spec.md`. Write a short
              `/workspace/out/story_spec_review.md` confirming approval.

            - **`revised`** — Flaws found AND fixable within the current
              decomposition/architecture. Apply the fixes to
              `/workspace/out/story_spec.md`. In `/workspace/out/story_spec_review.md`
              include a "Reasoning for fixes" section explaining WHY each fix was
              chosen. The orchestrator will route this back to Story Spec Review for
              a fresh-session re-review on the next iteration.

            ## When to escalate

            Your system prompt describes the escalation mechanism. Escalate from this node when:

            - your fix would reverse a prior iteration's or version's decision (`review_conflict`);
            - the flaw is real but no candidate fix is clearly correct (`uncertainty`);
            - a fundamentally different architecture would be better — do NOT apply it, propose
              it (`alternative_proposal`). Applying fixes is normal review work only while they
              preserve the decomposition (which components, repos, boundaries) and the
              architecture (sync vs async, data flow shape, ownership). If a fix would change
              either, escalate rather than apply. Changing which proposed Tasks exist is not a
              decomposition change: checklist item 3 governs Tasks;
            - a fix would move work the Story request asks for into another Story or Epic, or
              pull another Story's work into this one (`alternative_proposal`).

            Still write `story_spec.md` verbatim when you escalate — no partial fixes.

            ## Reasoning requirement

            Whenever you apply a fix, your `story_spec_review.md` MUST include a
            "Reasoning for fixes" section explaining WHY each fix was chosen.
            Later iterations, and later revisions of this Story, read it to build
            on or challenge prior decisions; without it they may revert your work.

            ## Tools available

            - `list-decisions` — Print the valid decisions for this node
              execution. The same set is also appended to your system prompt at
              agent start, but you can re-verify here at runtime.
            - `report-result <decision>` — Submit your decision. Decision is
              final once submitted.
            - `propose-roadmap --file <path>` — Validate and install the roadmap
              proposal as /workspace/out/roadmap_candidates.json.
            - `get-roadmap-graph --epic-id <Epic id>` — The Epic's Stories, Tasks,
              dependencies and statuses.
            - `artifact get <object-path> <local-path>` / `artifact put` — Pull
              and push files from/to object storage.

            ## Final reminder

            Do NOT call `report-result` until `/workspace/out/story_spec.md` and
            `/workspace/out/story_spec_review.md` are written and the proposal is
            installed. The decision is final once submitted. Your task is not
            complete until you call `report-result`.""";

    private StoryDesignPrompts() {}
}
