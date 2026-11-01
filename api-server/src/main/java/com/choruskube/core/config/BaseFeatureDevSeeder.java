package com.choruskube.core.config;

import com.choruskube.core.config.DeliveryPrompts.SpecSource;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.TemplateEdge;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(1)
public class BaseFeatureDevSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BaseFeatureDevSeeder.class);

    static final String GRAPH_ID = GraphIds.FEATURE_DEVELOPMENT;

    // Versioning model: each bump of CURRENT_VERSION creates a fresh GraphTemplate
    // row plus its own dedicated set of NodeDefinition rows. Older template versions
    // retain references to their original NodeDefinitions and remain frozen — prompt
    // and executor changes here never retroactively mutate prior versions. To ship a
    // change, edit the constants in this file (prompt, executor, schema) or the shared prompts in
    // DeliveryPrompts, increment CURRENT_VERSION, and the next boot creates the new snapshot.
    // v44: Draft Spec & Plan asks whether the run could finish an item itself before tagging it
    // Future work in its Caveats section.
    // v45: Final Approval merges the run's registered pull requests on approval
    // (merge_pull_requests: squash).
    // v46: Caveats separate out-of-scope work (Accepted) from deferral (Future work with a named
    // criterion); the spec proposes follow-ups, Implement writes them up, Code Review edits them,
    // and Final Approval reads the newest copy.
    // v47: the spec opens with Context and Approach at the level of the idea, records each
    // Technical Decision as an option table, links existing roadmap items to their app pages,
    // and later nodes cite its sections by title.
    // v48: the spec lets markdown (lists, tables, GitHub alerts) carry the shape of its content
    // and states each fact once.
    // v49: Implement names a decision entry YYYY-MM-DD-<slug>.md with its status on a line
    // under the title, and appends no index row.
    // v50: the entry keeps v49's status line and missing index row, but is named
    // YYYY-MM-DD---NN-<slug>.md again, dated by the UTC date it is written.
    // v51: Approve Spec & Plan and Final Approval re-check the run against current main when
    // approved (drift_recheck); pushes use --force-with-lease; reviews escalate drift from what
    // merged.
    // v52: Approve Spec & Plan re-applies its held approval after Spec Review revises the spec
    // (stamp: always), as Final Approval does; Spec Review is told its approval stands in for the
    // human's. See docs/decisions/2026-10-31---01-spec-gate-reapplies-a-revised-approval.md.
    // v53: Context and Approach draw the same mechanism before and after the change, with
    // components named and flaws and fixes numbered alike; Runtime Flow draws the end state.
    // PR bodies follow the same shape, list operator actions only when there are some, and
    // fold the remaining caveats into a collapsed block.
    static final int CURRENT_VERSION = 53;

    private static final String TEMPLATE_NAME = "Feature Development";

    private static final String CURRENT_INPUT_SCHEMA = """
            [
              {"name":"software_project_id","label":"Software Project","type":"software_project_id","required":true},
              {"name":"feature_request","label":"Feature Request","type":"textarea","required":true}
            ]
            """;

    private static final String SPEC_AND_PLAN_PROMPT = """
            You are drafting a technical specification and implementation plan for a
            feature request that spans one or more Git repositories.

            Feature request:
            {run.feature_request}

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
            cross-cutting tradeoffs. Use your own assessment of each sub-topic — there is
            no fixed rule beyond "the harder the sub-topic, the more capable the model."

            Your output is a SINGLE document with two clearly separated parts:

            - Part 1: Specification — for human reviewers. A coherent cross-repo
              narrative they use to understand the problem and the idea, then to
              evaluate architecture, decisions, repo collaboration, real-world
              risks, and operational handoff.
            - Part 2: Implementation Plan — for AI implementers. Per-repo, file-level,
              ordered tasks the downstream Implement node will execute.

            The two parts have different audiences, structures, and rules. Do not mix
            them. Implementation details belong in Part 2; design rationale belongs in
            Part 1.

            Write the spec so that a later per-repo split is a filter, not a rewrite. The
            Implement node performs the actual split when it writes into each repo, so it
            must be able to tell from the spec alone:
              - privacy — which content may appear in a PUBLIC repo. A public repo's slice
                must never name a non-public repo or disclose that one exists; mark any
                content that must be generalised before it lands there.
              - layer — which repo each statement belongs to. Keep the Architecture section
                organised by seam, with every bullet labelled by the repo it concerns, so a
                slice is a filter over bullets.
              - relevance — what someone editing only that repo needs, versus what is
                context for the change as a whole.
            Do not emit separate per-repo files. The cross-repo spec stays the single
            artifact; the split happens downstream. Whoever later omits a section resolves
            or deletes every reference into it — a reference to something the reader cannot
            open is worse than no reference.

            ═══════════════════════════════════════════════════════════════════════
            Part 1: Specification (for human reviewers)
            ═══════════════════════════════════════════════════════════════════════

            Write Part 1 as a single cross-repo narrative a reviewer reads top to
            bottom: the idea first, then the shape, then the detail. Per-repo
            grouping appears ONLY in Expected Changed Files, where reviewers need to
            scan their own repo's surface; every other section describes the system
            as a whole.

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

            Use the following EIGHT sections, in this order. Section numbers and
            titles must match exactly.

            ## 1. Context

            Your reader knows the system and is about to judge a change to it. Show
            them how the part this change touches works today, and what is wrong
            with it, before any solution appears. One or two sentences may frame the
            need before the diagram.

            Draw that mechanism as it works today, as one mermaid `flowchart` of
            about ten nodes or fewer. Its nodes are the components a reviewer knows
            by name (a service, a Worker, a queue, an agent container, a user),
            named as the codebase names them; edge labels say in words what passes
            between them. Endpoints, classes, fields and tables belong in
            Architecture. Draw what the change touches plus one step of context on
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

            ## 2. Approach

            Redraw the Context diagram as it works after the change, keeping its
            node ids and their order so mermaid lays both out alike and the reader
            sees a before and an after. Mark what the change removes as dashed and
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
            components and stored data correct. Name the pattern when a real one applies, together with what
            makes it that pattern ("the gate is an outbox: it records the intent in
            the same transaction, and a later step delivers it"). When none applies,
            leave the slot out: never invent a name, and never write that none fits.

            The numbers pair the two sections. Every flaw has a fix, or a Caveat
            that says why it stays; a fix with no flaw behind it is scope the reader
            was never shown a need for. One fix may close several flaws: give it
            each of their numbers. A change made only because a fix requires it (a
            field that carried what a fix removes, a library only the removed code
            used) rides on that fix's number rather than getting a flaw of its own.

            The diagrams carry the mechanism, so the lines under them do not retell
            it. A reviewer who reads only the two diagrams and their numbered lines
            should know what changes and why.

            ## 3. Architecture

            The concrete shape: which components do what, then how they interact at
            runtime.

            Organize the seams BY RESPONSIBILITY, NOT by repo. Each seam is a
            `### 3.x <Seam Name>` subsection containing:

            - A 2–4 sentence prose description of the seam's purpose and runtime
              contract.
            - A bulleted "who does what" listing each affected repo's component and
              its role IN THIS SEAM. The same repo may appear under multiple seams.
            - A reference to a Technical Decision by number when the seam embodies
              that choice (e.g., "Reflects Decision 2").
            - OPTIONAL: a small mermaid `flowchart` showing static topology, ONLY
              when the seam involves ≥3 components OR crosses a trust boundary, and
              only to zoom into the seam: never redraw the Approach diagram. Skip
              it if prose suffices.

            Close the section with one more subsection, `### 3.x Runtime Flow`,
            numbered after the last seam: end-to-end runtime behavior across all
            affected repos, as one or more mermaid `sequenceDiagram` blocks.
            REQUIRED, not optional. It is the one diagram that outlives the run:
            Context and Approach tell the story of this change and are dropped,
            while Runtime Flow merges into each repo's architecture doc as the
            present-tense design. So draw the end state one level below Approach —
            the calls in order, who authenticates to whom, what is asynchronous —
            rather than the Approach flowchart redrawn, and carry none of its
            flaw numbers.

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

            NO code snippets, NO file paths in this section. Those belong in
            Expected Changed Files or in Part 2.

            ## 4. Technical Decisions

            The forks: places where at least two approaches were viable and you
            picked one. The reader now knows the idea and the shape; this section
            shows what else was on the table and why it lost. Record a fork when it
            shapes the design a reviewer evaluates, or constrains future work or
            other repos. A choice with only one viable option is not a decision: if
            it introduces a new component or term, explain it in Architecture.
            Repo-local style choices (e.g., "use record vs. class") do NOT belong
            here unless they shape an interface another repo depends on.

            For EACH decision, use this exact block:

              ### Decision N: <the question it settles>

              | Option | Gives us | Costs us |
              |---|---|---|
              | ✅ **A. <chosen option>** | <one phrase> | <one phrase> |
              | B. <alternative> | <one phrase> | <one phrase> |

              **Decided by:** <the one factor that tipped it, in one line>

            - Title the decision as the question it settles ("Where does the retry
              state live?"), not as its answer.
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

            ## 5. Caveats

            Things the spec does NOT handle. If a risk has a mitigation IN the spec,
            it is part of the design, not a caveat — drop it.

            Every gap you find is in scope unless it is clearly not. Anything this
            change needs to be correct, safe or complete goes into Part 2, not here:
            edge cases and failure modes of what it builds, tests, docs, a defect it
            creates or exposes, a small adjacent fix that completes it. Size alone is
            never a reason to defer. If it is out of reach only because it needs a
            setting or access you lack, it is a step for the human: name exactly
            what to change rather than deferring it.

            For EACH caveat, use this exact block:

              ### Caveat N: <short title>
              - **What's not handled:** one line
              - **Disposition:** Accepted | Future work | Needs human decision
              - **Reasoning:** why this disposition; for Future work, which deferral
                criterion it meets; for Accepted, what would trigger revisiting
              - **Proposed as:** (Future work only) <Task title> — under <an existing
                Epic or Story, linked | a new Story "<title>" in <Epic, linked> | a
                new Epic "<title>">; blocked by <a Task: linked when it exists, by
                title when this spec proposes it>, if any

            Dispositions:
            - **Accepted** — out of scope: not asked for by the feature request and
              not needed for this change to be correct, safe or complete.
              Enhancements past the request, speculative measurement, and anything
              that waits on an event (below). An Accepted caveat creates nothing.
            - **Future work** — belongs to this change's purpose but is too
              complicated for this run. Use it only when at least one deferral
              criterion holds, and name which in Reasoning:
                (a) it needs its own design decision with real alternatives, one
                    this request did not ask you to make;
                (b) it changes a contract, schema or component this change does not
                    otherwise touch;
                (c) it needs an investigation or measurement, one that can run now,
                    before it can be designed.
            - **Needs human decision** — an alarm bell: use it sparingly, only when
              the choice genuinely cannot be defaulted.

            Most caveats are Accepted. Most runs defer nothing.

            For an item to revisit "when X happens", ask whether a run could do X and
            finish it.
            - Yes, X is work: Future work that depends on X. If X is already on the
              roadmap (run get-roadmap-graph), link its Task in "blocked by"; if not,
              propose X as well, and X must meet a deferral criterion on its own. The
              follow-up then cannot start until X is done.
            - No, X is an event (data grows past a size, users complain, a business
              decision): Accepted, with X as the trigger. No Task can stand for an
              event: a Task for it would start at once and invent work, and a blocker
              nobody does would never clear.

            Calibration:
            - A field the change already writes next to is not recorded: Part 2.
            - Re-check a threshold once real data passes it, and none has yet:
              Accepted.
            - Switch a caller to a new API once a planned migration ships: Future
              work, blocked by the migration's Task.
            - An opt-out nobody asked for: Accepted.
            - A crash mid-operation strands state, and recovery needs a new lease
              column plus a takeover rule: Future work, (a) and (b).

            Place a proposed item under the triggering Task's Epic when this run has
            one; propose a new Epic only for a genuinely separate initiative. The
            "Proposed as" lines are this run's roadmap proposal: the human approves
            them with the spec, and the Implement node turns them into the roadmap
            document. Do not run propose-roadmap yourself, even if your system prompt
            suggests it.

            Link every roadmap item that already exists to its page in the app, as
            `[<title>](<path>)`, using the ids get-roadmap-graph returns:
              Epic   /roadmap/epics/<epicId>
              Story  /roadmap/epics/<epicId>/stories/<storyId>
              Task   /tasks/<taskId>
            An item this spec proposes has no id until Final Approval creates it:
            name it by title, and link the existing Epic or Story it goes under.

            When a caveat's reason is that another ticket handles it, link that
            ticket in Reasoning, or name the Task this spec proposes for it. Never
            write "handled separately" or "tracked elsewhere" without saying where.
            If no ticket exists and this spec proposes none, nothing else handles
            it: decide its disposition by the rules above.

            The "Out of scope" content traditionally listed separately belongs here
            with disposition "Accepted".

            ## 6. Expected Changed Files

            The interface-level surface the implementation will touch, grouped BY
            REPO. This is the ONE section where per-repo grouping is preserved —
            reviewers need to scan their own repo's footprint. Use `### <repo-name>`
            subheadings.

            For each file, use ONE LINE in this format:

              - `<relative path>` — NEW | MODIFY | DELETE — interface-level
                description of what changes.

            A succinct declarative signature is permitted (e.g.,
            `record(long totalRuns, long totalRepos)`); a method body is not. If
            the description needs more than one line, you have leaked implementation
            detail — push it to Part 2.

            ## 7. Testing Strategy

            Describe WHAT FLOWS AND BEHAVIORS will be tested, not which files or
            test methods will be added. Audience is the human reviewer assessing
            coverage, not the agent writing the tests.

            - E2E: user-facing flows that must work after the change.
            - Integration: cross-component contracts (API shape, headers, status
              codes, error semantics).
            - Behavioral: failure modes, fallbacks, edge conditions.
            - Negative / security: explicitly state what should be rejected.

            Files and test method names go in Part 2, not here.

            ## 8. Manual Operations

            The system-administrator handoff: actions outside the implementing
            agent's reach. Audience: a human deploying this change.

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
            block for ordering constraints, hot-reload caveats, or rollback
            procedure that don't fit a single row. Omit it when there are none.

            ═══════════════════════════════════════════════════════════════════════
            Part 2: Implementation Plan (for AI implementers)
            ═══════════════════════════════════════════════════════════════════════

            Per-repo, file-level, ordered. The downstream Implement node consumes
            this section directly.

            - **Task ordering:** number tasks in dependency order. Mark which can
              run in parallel (across repos or within a repo).
            - **Cross-repo sync points:** where one repo's change must be in place
              before another's can proceed; which symbols / paths must match
              exactly across repos.
            - **File-level test cases per repo:** for each test file, what it
              covers. This is where file/method-level test detail lives — NOT
              in Testing Strategy.
            - **Migrations:** exact SQL or schema changes per repo, if applicable.
              If none, say "None" and explain why.
            - **Verification commands:** a small table of (command, repo) that the
              Implement node can run after each major task group.

            ═══════════════════════════════════════════════════════════════════════
            Quality bar before saving
            ═══════════════════════════════════════════════════════════════════════

            Before writing the file, sanity-check yourself:

            - Every Caveat is something the spec does NOT handle. If you can point
              to a section that handles it, it's design, not a caveat.
            - Context and Approach each hold one flowchart of the same mechanism,
              before and after, with the same node ids. Every numbered flaw has a
              fix at its number or a Caveat; every fix answers a flaw. Their nodes
              are components; no endpoint, class, field or table name appears in
              either section.
            - Someone who reads only those two diagrams and their numbered lines
              knows what changes and why. No later diagram redraws them.
            - Architecture contains no per-repo subsections. Expected Changed Files
              contains nothing but per-repo one-line file lists. They have inverted
              structures by design.
            - Every Technical Decision is a table with at least two viable options
              and exactly one ✅ row, followed by its "Decided by" line.
            - All diagrams are inside ```mermaid fences. No raw graph/edge JSON.
            - Part 1 contains no code bodies. Part 2 contains no design rationale.
            - A typical feature spec runs 200–500 lines. If yours is much longer,
              Part 2 has likely leaked into Part 1, or Caveats contains "risks" that
              are actually handled in the design.

            If the feature request is vague or underspecified, make reasonable
            assumptions based on the codebases and document them as Caveats with
            disposition "Needs human decision". Do not ask for clarification.

            {review_history}

            If the request is self-contradictory, or contradicts the codebase in a way no
            reasonable assumption resolves, escalate rather than guess. Ordinary vagueness is
            not grounds for escalation — make an assumption and record it as a Caveat.

            Save the document as /workspace/out/spec_and_plan.md.""";

    private static final String SPEC_REVIEW_PROMPT = """
            You are a self-iterating spec reviewer. You FIND flaws AND FIX them in
            the same session, then submit the appropriate decision. The bouncing
            review pattern (reject → re-author → re-review) is gone — when you find
            something fixable, you fix it here, in this invocation.

            ## Iteration awareness

            Read `iteration` from /workspace/config.json. It counts every run
            of this node across its whole lifetime and never resets — it does
            not reset when a human routes the workflow back to Spec Review, and
            it is not a budget. There is no iteration cap. The self-loop on
            `revised` ends only when you emit `approved`, or when you escalate
            to the Supervisor. Convergence is expected to happen through
            genuine resolution, not through running out of attempts.

            ## Inputs

            - `/workspace/in/draft_spec_and_plan/spec_and_plan.md` — the original
              draft spec from the first author. Always present.
            - `/workspace/in/spec_review/spec_and_plan.md` — the prior iteration's
              revised spec. Present only if iteration > 1.
            - `/workspace/in/spec_review/spec_review.md` — the prior iteration's
              review notes (including the "Reasoning for fixes" section). Present
              only if iteration > 1. Read this carefully so you build on prior
              decisions instead of reverting them.
            - `/workspace/in/run_log.md` — accumulated history of all prior nodes.
            - `/workspace/in/<gate_label>/human_guidance.md` — present only if a
              downstream human gate or the Supervisor sent this back via
              `rereview` or `redraft`. When present, this is direction from the
              reviewer; honor it.
            - Repositories are cloned under `/workspace/repo/<name>/`. You may
              dispatch Task subagents to examine multiple repos in parallel.

            **Iteration-1 special case:** if `/workspace/in/spec_review/` is empty
            or absent, this is iteration 1. There is no prior iteration to read;
            you are reviewing the original draft.

            ## Review History & Conflict Check

            {review_history}

            The above is a JSON array of every past review in this loop
            group, ordered oldest-first. Each entry has: `loopGroup`,
            `iteration`, `reviewerType`, `decision`, `result`,
            `artifactRefs`, `nodeLabel`, `timestamp`, `status`.

            Before finalizing any decision, check whether your current fix or
            decision would reverse, contradict, or re-litigate a specific
            past entry's decision or fix. This can happen when a human
            guidance note, or your own re-reading of the spec, pushes you
            toward undoing something a prior iteration deliberately decided.

            If you detect such a conflict, do NOT apply the fix. Escalate instead, with
            `category: review_conflict`, and put the conflicting decisions, your proposal,
            and the tradeoff between them in `escalation.md`.

            ## Outputs (REQUIRED on every invocation)

            - `/workspace/out/spec_and_plan.md` — REQUIRED. If you applied fixes
              (decision = revised), write the updated spec. If you found no flaws
              (decision = approved), copy the input spec verbatim. This invariant
              ensures downstream nodes always read the spec from Spec Review's
              output prefix, no matter the decision.
            - `/workspace/out/spec_review.md` — REQUIRED. Your review notes. If
              you applied fixes, include a "Reasoning for fixes" section that
              explains WHY each fix was chosen (not just what changed). Subsequent
              iterations read this to build on or challenge prior decisions
              without reverting them.

            ## Spec format reference

            The spec follows a fixed structure:

              Part 1, eight numbered sections: Context, Approach, Architecture
              (seams, then Runtime Flow), Technical Decisions, Caveats, Expected
              Changed Files, Testing Strategy, Manual Operations
              Part 2: Implementation Plan (task ordering, cross-repo sync points,
              file-level test cases, migrations, verification commands)

            ## What to check

            1. **Format conformance**: the eight Part 1 sections + Part 2 in order
               with exact titles; Context and Approach each holding one flowchart;
               Architecture organized by seam/responsibility
               (NOT per-repo) and closing with a Runtime Flow subsection that holds
               a sequence diagram; Expected Changed Files is per-repo, one line per
               file, no method bodies; mermaid diagrams in ```mermaid fences; each
               Technical Decision is titled as a question and is an
               Option / Gives us / Costs us table with at least two rows, exactly
               one ✅ row, and a "Decided by" line; Caveat blocks have a
               Disposition tag; Manual Operations has a Local/Production row only
               for an action, plus its "Checked, nothing needed" line.
            2. **Internal consistency**: Part 2 file changes match Expected Changed
               Files; migrations match Architecture and Technical Decisions;
               Testing Strategy matches Part 2 file-level tests.
            3. **Cross-repo contract coherence**: shared surfaces defined
               identically on both sides (method/path/body/response).
            4. **Decision soundness**: "Costs us" honest on every row, the chosen
               one included; no rejected option is a strawman nobody would pick;
               "Decided by" names the deciding factor rather than restating the
               chosen row.
            5. **Missing details**: failure modes without coverage in Architecture,
               Testing Strategy or Caveats (with disposition) are gaps.
            6. **Caveat hygiene**: a "Caveat" pointing to a mitigation already in
               the spec is design, not a caveat. A Future-work caveat that names no
               deferral criterion, waits on an event, or lacks a "Proposed as" line
               is a gap: move it to Part 2 when the change needs it, otherwise to
               Accepted. Check each "Proposed as" placement too. A caveat whose
               reason is that another ticket handles it must link that ticket, or
               name the Task this spec proposes; every existing roadmap item the
               spec mentions is linked to its page in the app.
            7. **Maintainability risk**: couplings, god classes, brittle
               abstractions — per repo and at the seams.
            8. **Scope creep**: Part 2 work not justified by Part 1; Part 1
               promises Part 2 doesn't deliver. Part 2 work that closes a gap this
               change creates or exposes is justified, not scope creep.
            9. **Convention violations per repo**: package structure, naming,
               test style.
            10. **Deployment gaps**: env vars, secrets, migrations, config absent
                from Manual Operations or Part 2 migrations.
            11. **Before and after**: Context draws today's mechanism with each
                flaw numbered on it and its cost stated; Approach redraws the same
                nodes after the change, each fix at its flaw's number. Every flaw
                has a fix or a Caveat, and every fix answers a flaw. Nodes are
                components: endpoint, class, field and table names move to
                Architecture. The lines under each diagram do not retell it.
                Approach names a pattern only when one really applies, says what
                makes it that pattern, and otherwise leaves the slot out.
                Architecture's diagrams zoom in: none redraws the Approach diagram,
                and Runtime Flow carries no flaw numbers.

            ## Deferral criteria (for checklist items 6 and 8)

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

            ## Decision tree

            Pick exactly one of:

            - **`approved`** — No flaws found. Copy the input spec verbatim to
              `/workspace/out/spec_and_plan.md`. Write a short
              `/workspace/out/spec_review.md` confirming approval.

            - **`revised`** — Flaws found AND fixable within the current
              decomposition/architecture. Apply the
              fixes to `/workspace/out/spec_and_plan.md`. In
              `/workspace/out/spec_review.md` include a "Reasoning for fixes"
              section explaining WHY each fix was chosen. The orchestrator will
              route this back to Spec Review for a fresh-session re-review on
              the next iteration.

            ## When to escalate

            Your system prompt describes the escalation mechanism. Escalate from this node when:

            - your fix would reverse a prior iteration's decision (`review_conflict`);
            - the flaw is real but no candidate fix is clearly correct (`uncertainty`);
            - a fundamentally different architecture would be better — do NOT apply it, propose
              it (`alternative_proposal`). Applying fixes is normal review work only while they
              preserve the decomposition (which components, repos, boundaries) and the
              architecture (sync vs async, data flow shape, ownership). If a fix would change
              either, escalate rather than apply.
            - the default branch moved, and reconciling the spec with what merged would change
              its decomposition or architecture, or reverse a decision a merged run made
              (`review_conflict`; `alternative_proposal` if a different architecture is now
              better) — cite the merged run's pull request.

            Still write `spec_and_plan.md` verbatim when you escalate — no partial fixes.

            If `human_guidance.md` is the system's re-check note (it starts `# Re-check against
            current main`), the spec is already approved, and that approval is re-applied to
            whatever you approve without a human seeing it again. Change only what reconciling the
            spec with what merged requires; the rules above decide when that needs a human instead.

            ## Reasoning requirement

            Whenever you apply a fix, your `spec_review.md` MUST include a
            "Reasoning for fixes" section explaining WHY each fix was chosen.
            Subsequent iterations read this to build on or challenge prior
            decisions; without it they may revert your work.

            ## Tools available

            - `list-decisions` — Print the valid decisions for this node
              execution. The same set is also appended to your system prompt at
              agent start, but you can re-verify here at runtime.
            - `report-result <decision>` — Submit your decision. Decision is
              final once submitted.
            - `artifact get <object-path> <local-path>` / `artifact put` — Pull
              and push files from/to object storage.

            ## Final reminder

            Do NOT call `report-result` until both `/workspace/out/spec_and_plan.md`
            and `/workspace/out/spec_review.md` are written. The decision is final
            once submitted. Your task is not complete until you call
            `report-result`.""";

    private final GraphTemplateRepository templateRepo;
    private final NodeDefinitionRepository nodeDefRepo;
    private final TemplateNodeRepository templateNodeRepo;
    private final TemplateEdgeRepository edgeRepo;
    private final ObjectMapper objectMapper;

    public BaseFeatureDevSeeder(
            GraphTemplateRepository templateRepo,
            NodeDefinitionRepository nodeDefRepo,
            TemplateNodeRepository templateNodeRepo,
            TemplateEdgeRepository edgeRepo,
            ObjectMapper objectMapper) {
        this.templateRepo = templateRepo;
        this.nodeDefRepo = nodeDefRepo;
        this.templateNodeRepo = templateNodeRepo;
        this.edgeRepo = edgeRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        var existing = templateRepo.findByGraphIdAndVersion(GRAPH_ID, CURRENT_VERSION);
        if (existing.isPresent()) {
            assertSchemaUnchanged(existing.get().getInputSchema(), CURRENT_INPUT_SCHEMA, CURRENT_VERSION);
            log.info(
                    "BaseFeatureDevSeeder: template graphId='{}' v{} already exists — skipping seed",
                    GRAPH_ID,
                    CURRENT_VERSION);
            return;
        }

        log.info("BaseFeatureDevSeeder: seeding template graphId='{}' v{}", GRAPH_ID, CURRENT_VERSION);
        Map<String, NodeDefinition> nodeDefs = seedNodeDefinitions();
        seedTemplate(CURRENT_VERSION, CURRENT_INPUT_SCHEMA, nodeDefs);
    }

    private void assertSchemaUnchanged(String storedRaw, String expectedRaw, int version) throws Exception {
        var stored = objectMapper.readValue(storedRaw, new TypeReference<Object>() {});
        var expected = objectMapper.readValue(expectedRaw, new TypeReference<Object>() {});
        if (!stored.equals(expected)) {
            throw new IllegalStateException("BaseFeatureDevSeeder: inputSchema has diverged for graphId='"
                    + GRAPH_ID + "' version=" + version
                    + ". Update the version or reconcile the schema.");
        }
    }

    private Map<String, NodeDefinition> seedNodeDefinitions() {
        Map<String, NodeDefinition> defs = new HashMap<>();
        NodeDefinition draftSpecAndPlan =
                createNodeDef("Draft Spec & Plan", ExecutorType.ai, SPEC_AND_PLAN_PROMPT, 3600);
        draftSpecAndPlan.setModel(ModelIds.MODEL_OPUS);
        draftSpecAndPlan.setOutputSpec(
                "{\"files\":[{\"name\":\"spec_and_plan.md\",\"required\":true,\"description\":\"Technical specification and implementation plan\"}]}");
        nodeDefRepo.save(draftSpecAndPlan);
        defs.put("Draft Spec & Plan", draftSpecAndPlan);

        NodeDefinition specReview = createNodeDef("Spec Review", ExecutorType.ai, SPEC_REVIEW_PROMPT, 1800);
        specReview.setOutputSpec(
                "{\"files\":[{\"name\":\"spec_review.md\",\"required\":true,\"description\":\"AI reviewer assessment and recommendations\"}]}");
        // No iteration cap: the revised self-loop terminates only via `approved` or by
        // escalating to the Supervisor (when the reviewer detects its current fix would
        // reverse a prior decision from {review_history}, or is otherwise uncertain). There
        // is no counter-based forced escalation — the prompt is the only thing driving
        // convergence.
        nodeDefRepo.save(specReview);
        defs.put("Spec Review", specReview);

        defs.put("Approve Spec & Plan", createNodeDef("Approve Spec & Plan", ExecutorType.human, null, 86400));

        NodeDefinition implement = createNodeDef(
                "Implement", ExecutorType.ai, DeliveryPrompts.implement(SpecSource.FEATURE_DEVELOPMENT), 10800);
        implement.setModel(ModelIds.MODEL_SONNET);
        implement.setOutputSpec(
                "{\"files\":[{\"name\":\"summary.md\",\"required\":true,\"description\":\"Implementation summary describing changes made\"},"
                        + "{\"name\":\"roadmap_candidates.json\",\"required\":false,\"description\":\"Proposed roadmap extension for Final Approval (only when deferred work is proposed)\"}]}");
        nodeDefRepo.save(implement);
        defs.put("Implement", implement);

        defs.put("Test", createNodeDef("Test", ExecutorType.script, null, 7200));

        NodeDefinition codeReview = createNodeDef(
                "Code Review", ExecutorType.ai, DeliveryPrompts.codeReview(SpecSource.FEATURE_DEVELOPMENT), 10800);
        codeReview.setOutputSpec(
                "{\"files\":[{\"name\":\"review.md\",\"required\":true,\"description\":\"Code review findings and approve/reject recommendation\"},"
                        + "{\"name\":\"roadmap_candidates.json\",\"required\":false,\"description\":\"Reviewed roadmap proposal for Final Approval (only when one exists)\"}]}");
        // No iteration cap: same self-detected review-conflict escalation as Spec Review
        // (see the comment above specReview).
        nodeDefRepo.save(codeReview);
        defs.put("Code Review", codeReview);

        // The Supervisor: the template's single routing hub. It has no edges — every AI node
        // reaches it via the implicit `escalate` decision and it leaves via `route:<label>`.
        // Nothing about it is Feature-Dev-specific; it is the platform primitive.
        defs.put("Supervisor", createNodeDef("Supervisor", ExecutorType.human, null, 86400));

        // Terminal node (v35): its `approved` decision is declared via
        // terminal_decisions in seedTemplate() instead of routing to a
        // now-retired Push & Create PR node.
        defs.put("Final Approval", createNodeDef("Final Approval", ExecutorType.human, null, 86400));

        return defs;
    }

    private void seedTemplate(int version, String inputSchema, Map<String, NodeDefinition> nodeDefs) {
        // Create template
        GraphTemplate template = new GraphTemplate();
        template.setGraphId(GRAPH_ID);
        template.setVersion(version);
        template.setName(TEMPLATE_NAME);
        template.setDescription(
                "End-to-end multi-repository feature development workflow with AI drafting, per-repo implementation via subagents, cross-repo code review, and one PR per repo with bidirectional cross-links.");
        template.setInputSchema(inputSchema);
        template.setPromptInputKey("feature_request");
        template.setSystem(true);
        template = templateRepo.save(template);

        // v37 layout. The graph is the happy path and nothing else; every exception route left
        // the topology and is handled by the edgeless [Supervisor] node (config_overrides
        // routing_hub), which any AI node pages with `escalate` and which leaves via
        // `route:<label>` to any node.
        //
        //   [Draft S&P] → [Spec Review] ⇄ revised → [Approve S&P] ─approved→ [Implement]
        //                                             ├─rereview→ [Spec Review]
        //                                             └─redraft → [Draft S&P]
        //   [Implement] → [Code Review] ⇄ revised ─approved→ [Test]
        //   [Test] ├─passed→ [Final Approval] ├─approved→ (terminal — run ends)
        //          │                          └─rereview→ [Code Review]
        //          └─failed→ [Implement]      (a code-caused failure is a mechanical retry;
        //                                      only Code Review judging it environmental escalates)
        //
        //   [Supervisor]  no edges. escalate ↑ from any AI node, route:<label> ↓ to any node.

        TemplateNode tnDraftSpecAndPlan = createNode(
                template,
                nodeDefs.get("Draft Spec & Plan"),
                "draft_spec_and_plan",
                true,
                "{\"loop_group\": \"spec-review\", \"effort\": \"xhigh\"}");
        // Spec Review reads (a) the original draft, (b) its own prior iteration's
        // outputs (only present when iteration > 1), so it can build on prior
        // reasoning without reverting decisions. The self-reference is what makes
        // self-iteration possible — ArtifactResolutionService picks max(iteration)
        // of completed executions, which for the running iteration N resolves to
        // iteration N-1's outputs.
        TemplateNode tnSpecReview = createNode(
                template,
                nodeDefs.get("Spec Review"),
                "spec_review",
                false,
                "{\"loop_group\": \"spec-review\", "
                        + "\"model_first_iteration\": \"" + ModelIds.MODEL_OPUS + "\", "
                        + "\"effort_first_iteration\": \"xhigh\", "
                        + "\"model_subsequent_iteration\": \"" + ModelIds.MODEL_SONNET + "\", "
                        + "\"effort_subsequent_iteration\": \"high\"}",
                "[{\"template_node_label\":\"draft_spec_and_plan\",\"artifacts\":[{\"name\":\"spec_and_plan.md\",\"description\":\"Original draft spec from the first author\",\"required\":true}]},{\"template_node_label\":\"spec_review\",\"artifacts\":[{\"name\":\"spec_and_plan.md\",\"description\":\"Prior iteration's revised spec (only present if iteration > 1)\",\"required\":false},{\"name\":\"spec_review.md\",\"description\":\"Prior iteration's review notes including Reasoning for fixes (only present if iteration > 1)\",\"required\":false}]}]");
        // Spec ownership transfer (v23): Approve Spec & Plan reads spec_and_plan.md
        // from Spec Review, NOT from Draft Spec & Plan. Spec Review always writes
        // the spec to its own output (verbatim copy on first-pass approve, revised
        // copy on `revised`), so the resolution layer never needs to fall back to
        // Draft Spec.
        TemplateNode tnApproveSpecAndPlan = createNode(
                template,
                nodeDefs.get("Approve Spec & Plan"),
                "approve_spec_and_plan",
                false,
                "{\"loop_group\": \"spec-review\", \"drift_recheck\": {\"route\": \"rereview\", \"stamp\": \"always\"}}",
                "[{\"template_node_label\":\"spec_review\",\"artifacts\":[{\"name\":\"spec_and_plan.md\",\"description\":\"The reviewed (and possibly revised) spec to approve\",\"required\":true},{\"name\":\"spec_review.md\",\"description\":\"reviewer notes\",\"required\":true}]}]");

        // Implement reads spec_and_plan.md from Spec Review (ownership transfer),
        // not from Draft Spec.
        TemplateNode tnImplement = createNode(
                template,
                nodeDefs.get("Implement"),
                "implement",
                false,
                "{\"loop_group\": \"impl-review\", \"needs_branch\": \"true\", \"effort\": \"high\", \"needs_pr\": \"true\"}",
                "[{\"template_node_label\":\"spec_review\",\"artifacts\":[{\"name\":\"spec_and_plan.md\",\"description\":\"The approved spec to implement\",\"required\":true}]},"
                        + "{\"template_node_label\":\"implement\",\"artifacts\":[{\"name\":\"roadmap_candidates.json\",\"description\":\"Prior iteration's roadmap proposal (only present if iteration > 1 and one was proposed)\",\"required\":false}]}]");
        // Test runs run-all-tests (a script in the agent image) which iterates each
        // repo's test_command from /workspace/config.json. Single-repo runs read the
        // top-level test_command instead. Exit code 0 → "passed", non-zero → "failed".
        TemplateNode tnTest = createNode(
                template,
                nodeDefs.get("Test"),
                "test",
                false,
                "{\"loop_group\": \"impl-review\", \"needs_branch\": \"true\", \"command\": \"run-all-tests\"}");
        // Code Review's source-of-truth for code is the working git branch (its
        // commits push directly there), so no object storage ownership transfer is needed.
        // The self-reference here is just the prior iteration's review.md, so a
        // re-review can build on or challenge prior reasoning without reverting.
        // Implement's proposal is read so Code Review can rebuild and re-install it;
        // Final Approval takes whichever of the two copies was written last.
        TemplateNode tnCodeReview = createNode(
                template,
                nodeDefs.get("Code Review"),
                "code_review",
                false,
                "{\"loop_group\": \"impl-review\", \"needs_branch\": \"true\", \"needs_pr\": \"true\", "
                        + "\"model_first_iteration\": \"" + ModelIds.MODEL_OPUS + "\", "
                        + "\"effort_first_iteration\": \"xhigh\", "
                        + "\"model_subsequent_iteration\": \"" + ModelIds.MODEL_SONNET + "\", "
                        + "\"effort_subsequent_iteration\": \"high\"}",
                "[{\"template_node_label\":\"code_review\",\"artifacts\":[{\"name\":\"review.md\",\"description\":\"Prior iteration's code review notes including Reasoning for fixes (only present if iteration > 1)\",\"required\":false}]},"
                        + "{\"template_node_label\":\"implement\",\"artifacts\":[{\"name\":\"roadmap_candidates.json\",\"description\":\"Implement's latest roadmap proposal (only present if one was proposed)\",\"required\":false}]}]");
        TemplateNode tnSupervisor =
                createNode(template, nodeDefs.get("Supervisor"), "supervisor", false, "{\"routing_hub\": true}");
        // Terminal node (v35): `approved` has no outgoing edge — it's a
        // terminal_decisions entry that ends the run instead of
        // routing to the now-retired Push & Create PR node.
        TemplateNode tnFinalApproval = createNode(
                template,
                nodeDefs.get("Final Approval"),
                "final_approval",
                false,
                "{\"loop_group\": \"impl-review\", \"terminal_decisions\": [\"approved\"], \"materialize\": \"roadmap_extension\", \"merge_pull_requests\": \"squash\", \"drift_recheck\": {\"route\": \"rereview\", \"stamp\": \"always\"}}",
                "[{\"template_node_label\":\"implement\",\"artifacts\":[{\"name\":\"summary.md\",\"description\":\"Implementation summary describing changes made\",\"required\":true},{\"name\":\"roadmap_candidates.json\",\"description\":\"Proposed roadmap extension (optional)\",\"required\":false}]},"
                        + "{\"template_node_label\":\"code_review\",\"artifacts\":[{\"name\":\"review.md\",\"description\":\"Code review findings and approve/reject recommendation\",\"required\":true},{\"name\":\"roadmap_candidates.json\",\"description\":\"Reviewed roadmap proposal (optional; the newer of this and Implement's copy is used)\",\"required\":false}]}]");

        // Create edges. v37: the graph is happy-path-only. Review nodes still self-loop on
        // `revised` (find AND fix in one session), but every human-escalation edge is gone —
        // escalation now leaves the topology entirely via the Supervisor's implicit
        // `escalate`/`route:<label>` decisions (see the [Supervisor] comment above), so the
        // Supervisor itself gets no createEdge calls at all. Approve Spec & Plan still splits
        // its rejection action into `rereview` (re-run Spec Review with human guidance) and
        // `redraft` (full re-author). Final Approval gets only `rereview` — once a spec is
        // approved, discarding the implementation entirely is rare enough not to be a
        // routable action.
        // Spec-review loop
        createEdge(template, tnDraftSpecAndPlan, tnSpecReview, null);
        createEdge(template, tnSpecReview, tnApproveSpecAndPlan, "approved");
        createEdge(template, tnSpecReview, tnSpecReview, "revised");
        createEdge(template, tnApproveSpecAndPlan, tnImplement, "approved");
        createEdge(template, tnApproveSpecAndPlan, tnSpecReview, "rereview");
        createEdge(template, tnApproveSpecAndPlan, tnDraftSpecAndPlan, "redraft");
        // Impl-review loop
        createEdge(template, tnImplement, tnCodeReview, null);
        createEdge(template, tnCodeReview, tnCodeReview, "revised");
        createEdge(template, tnCodeReview, tnTest, "approved");
        createEdge(template, tnTest, tnFinalApproval, "passed");
        createEdge(template, tnTest, tnImplement, "failed");
        // No `approved` edge for tnFinalApproval: it's a terminal_decisions entry
        // (declared on tnFinalApproval's config-overrides above) that ends the run.
        createEdge(template, tnFinalApproval, tnCodeReview, "rereview");

        log.info(
                "BaseFeatureDevSeeder: seeded template graphId='{}' v{}: 8 template nodes, 12 edges (node defs shared)",
                GRAPH_ID,
                version);
    }

    private NodeDefinition createNodeDef(
            String name, ExecutorType executorType, String promptTemplate, int timeoutSeconds) {
        NodeDefinition nd = new NodeDefinition();
        nd.setName(name);
        nd.setExecutorType(executorType);
        nd.setPromptTemplate(promptTemplate);
        nd.setTimeoutSeconds(timeoutSeconds);
        nd.setSkills("[]");
        nd.setInputSpec("{}");
        nd.setOutputSpec("{}");
        nd.setSecrets("[]");
        return nodeDefRepo.save(nd);
    }

    private TemplateNode createNode(
            GraphTemplate template, NodeDefinition nd, String label, boolean entrypoint, String configOverrides) {
        return createNode(template, nd, label, entrypoint, configOverrides, null);
    }

    private TemplateNode createNode(
            GraphTemplate template,
            NodeDefinition nd,
            String label,
            boolean entrypoint,
            String configOverrides,
            String requiredInputArtifacts) {
        TemplateNode tn = new TemplateNode();
        tn.setGraphTemplateId(template.getId());
        tn.setNodeDefinitionId(nd.getId());
        tn.setLabel(label);
        tn.setEntrypoint(entrypoint);
        tn.setConfigOverrides(configOverrides);
        tn.setRequiredInputArtifacts(requiredInputArtifacts);
        return templateNodeRepo.save(tn);
    }

    private TemplateEdge createEdge(
            GraphTemplate template, TemplateNode source, TemplateNode target, String condition) {
        TemplateEdge te = new TemplateEdge();
        te.setGraphTemplateId(template.getId());
        te.setSourceNodeId(source.getId());
        te.setTargetNodeId(target.getId());
        te.setCondition(condition);
        return edgeRepo.save(te);
    }
}
