package com.choruskube.core.config;

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
    // change, edit the constants in this file (prompt, executor, schema), increment
    // CURRENT_VERSION, and the next boot creates the new snapshot.
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
    static final int CURRENT_VERSION = 49;

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

            Why this change exists, in plain words: the business need or the
            technical gap, at the level of the idea. Start from what works today and
            show what it cannot do, so the reader feels the gap before any solution
            appears. Close with the user-visible outcome in one or two sentences and
            a one-line list of repos touched. No solution yet.

            ## 2. Approach

            The idea that closes the gap: the chain of reasoning from problem to
            solution, and how the parts hand work to each other — still at the level
            of the idea. Say what makes it work. Name the pattern when a real one
            applies, together with what makes it that pattern ("the gate is an
            outbox: it records the intent in the same transaction, and a later step
            delivers it"). If no named pattern fits, say what it is in plain terms;
            never invent a name to fill the slot.

            Include one small mermaid `flowchart` of that chain: roles and steps,
            not files, endpoints or tables, in about ten nodes or fewer.

            The bar for Context and Approach: strip every component, class, endpoint
            and table name from them. If the reasoning still holds, it is at the
            level of the idea. If it collapses, you described the implementation —
            move that detail to Architecture. Introduce a new term only after the
            idea it names is clear, as a label for it, never as a definition the
            reader must take on trust.

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
              when the seam involves ≥3 components OR crosses a trust boundary. Skip
              it if prose suffices.

            Close the section with one more subsection, `### 3.x Runtime Flow`,
            numbered after the last seam: end-to-end runtime behavior across all
            affected repos, as one or more mermaid `sequenceDiagram` blocks.
            REQUIRED, not optional.

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

            Use this exact two-column table:

              | Action | Local (e2e) | Production |
              |---|---|---|
              | <action> | <usually "auto via compose"> | <exact step the operator runs> |

            Include rows for: env vars / secrets, Ingress / DNS / networking,
            external service configuration, post-deploy smoke verification, and any
            other manual step. Even rows that are identical across environments
            should appear — the table's value is making the gap legible.

            Below the table, include a free-form **"Notes for production rollout"**
            block for ordering constraints, hot-reload caveats, or rollback
            procedure that don't fit a single row.

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
            - Context and Approach still read correctly with every component, class,
              endpoint and table name stripped out.
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
               with exact titles; Architecture organized by seam/responsibility
               (NOT per-repo) and closing with a Runtime Flow subsection that holds
               a sequence diagram; Expected Changed Files is per-repo, one line per
               file, no method bodies; mermaid diagrams in ```mermaid fences; each
               Technical Decision is titled as a question and is an
               Option / Gives us / Costs us table with at least two rows, exactly
               one ✅ row, and a "Decided by" line; Caveat blocks have a
               Disposition tag; Manual Operations has the Local/Production table.
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
            11. **Idea level**: Context and Approach still read correctly with
                every component, class, endpoint and table name stripped out.
                Implementation detail found there moves to Architecture. Approach
                names a pattern only when one really applies, and says what makes
                it that pattern.

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

            Still write `spec_and_plan.md` verbatim when you escalate — no partial fixes.

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

    private static final String IMPLEMENT_PROMPT = """
            You are implementing a feature based on an approved spec and plan. The
            feature may span multiple repositories.

            Before writing docs or comments in a repo, read that repo's CLAUDE.md.
            Its conventions override these instructions. Follow the instructions below
            only where that repo states no rule. When a run touches several repos,
            each repo's rules apply to its own files.

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

            ## Inputs

            - `/workspace/in/spec_review/spec_and_plan.md` — the approved spec
              and plan described above, both parts in one file. Always present.
              This is the authoritative copy: read it from this path rather
              than searching object storage or the run log for it.
            - `/workspace/in/run_log.md` — accumulated history of all prior
              nodes. On a retry it also holds the earlier Implement summary and
              the Test node's report.
            - `/workspace/in/approve_spec_and_plan/<filename>` — any files the
              human reviewer attached at the approval gate. Present only if
              they attached something. Guidance a reviewer types when sending
              work back — from a human gate or the Supervisor — arrives the
              same way, as `human_guidance.md`; when such a file is present it
              is direction from the reviewer, so honor it.

            The block below is the drafting node's short summary of the spec —
            orientation only, not a substitute for reading the document itself.

            {input.draft_spec_and_plan.result}

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
            - Push the branch to origin
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

            If the plan is unimplementable as written and Part 1 does not settle the correct
            reading, escalate (`category: uncertainty`) rather than implement a guess.

            ## Routing the spec into durable docs

            When a decision earns a durable home, route the spec's content by whether it
            accumulates:
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
              - Expected Changed Files, Testing Strategy, Manual Operations and Part 2
                                    -> discard; they are execution scaffolding
            Graduate a decision only when something in this repo cites it. Do not bulk-copy
            the spec.

            Graduation is selective, so a decision you graduate can reference one you left
            behind. Resolve or delete every such reference as you write the entry: state what
            the other decision settled, or drop the clause. A reference to something the
            reader cannot open is worse than no reference.

            These docs are derived from the spec. A run owns exactly one decisions file —
            docs/decisions/YYYY-MM-DD-<slug>.md — holding every decision that run
            graduates. On a re-run, edit that same file; never create a second one. The spec
            may have changed since an earlier iteration wrote it and may now conflict with
            what is there — amend or rewrite as you judge fit. ARCHITECTURE.md is an existing
            living document; merge into it rather than adding a parallel file.

            The filename carries no sequence number, even where older entries' names do:
            runs land in parallel, a number each branch takes as the next free one collides
            with another open branch's, and git merges the two files without a word.

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

            The spec already decided what to defer, and the human approved it with the spec.
            After every repo's implementation is done, write ONE proposal for the whole run
            containing every Caveat tagged Future work, placed as its "Proposed as" line
            says, and nothing else except the additions below. If this run's change completed
            a Future-work caveat after all, leave it out and say so in the PR body.

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
               - PR body, in this exact order:

                 **a. Summary** — per-repo implementation summary scoped to this
                 repo, describing what you just did. For a PUBLIC repo, describe
                 only this repo's change and justify it on grounds that hold
                 within this repo alone; do not explain it by reference to
                 another repo.

                 **b. ⚠️ Manual Operations Required** — from the specification's
                 Manual Operations section (the Local/Production table AND the
                 "Notes for production rollout" block), under a top-level
                 `## ⚠️ Manual Operations Required` heading.
                   - For a NON-PUBLIC repo: copy that section verbatim.
                   - For a PUBLIC repo: include ONLY the operations that apply
                     to this repo, rewritten to remove other-repo names,
                     internal hosts, cluster/namespace detail, and internal
                     paths. Drop rows that describe work in a non-public repo
                     entirely — do not replace them with a placeholder row that
                     implies one exists.
                   - If that section is empty, absent, or nothing survives the
                     filter, write `_No manual operations required for this change._`
                     instead.

                 **c. Caveats & Known Limitations** — under a top-level
                 `## Caveats & Known Limitations` heading, list each Caveat from
                 the specification's Caveats section: title and Disposition tag,
                 "What's not handled" line, "Reasoning" line, each app link
                 written as its plain title. Caveats are
                 cross-repo content like Manual Operations, so the same
                 visibility rule applies: NON-PUBLIC repo
                 gets every Caveat; PUBLIC repo gets only the Caveats that
                 concern it, generalized to remove other-repo names and
                 infrastructure detail, with any Caveat that exists only
                 because a non-public repo is involved dropped entirely rather
                 than reworded into a hint that one exists. If the Caveats
                 section is empty, or
                 nothing survives the filter, omit this section entirely. Mark
                 each Future-work Caveat you proposed to the roadmap this run
                 as `proposed to the roadmap (pending Final Approval)`, and keep
                 the `(found during implementation)` marker on any you added.

                 **d. ❓ Open Decisions for Reviewer** — ONLY include this
                 section if at least one Caveat is still tagged "Needs human
                 decision". When included, render it under a top-level
                 `## ❓ Open Decisions for Reviewer` heading: restate the open
                 question in one sentence, list the options the spec laid out
                 (if any), and make clear that merging implies accepting the
                 default behavior described in the Caveat's Reasoning. Inherits
                 the Caveats visibility filter. Omit entirely if none remain.

                 **e. Companion PRs placeholder** — a single line:
                 `_Companion PRs: (linked after all PRs are created)_`. OMIT
                 this placeholder entirely if this repo will have no linkable
                 companions under step 3 below (e.g. a PUBLIC repo in a run
                 whose other repos are all non-public).

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
                 shipped. Include one-line notes if Manual Operations are
                 required and/or if any Open Decisions for Reviewer remain.

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

    private static final String CODE_REVIEW_PROMPT = """
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

            Note: by the time code reaches you, the spec is already approved.
            Architectural alternatives are NOT in scope here — if you find the
            implementation is structurally irrecoverable within the current spec,
            escalate (`uncertainty`). Do NOT propose to discard the spec.

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
            `git push origin HEAD`. The branch state is the source of truth —
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
                  in this exact order: **a. Summary** of what you fixed in
                  this repo (generalized, no cross-repo references, if
                  PUBLIC); **b. ⚠️ Manual Operations Required** from the Manual
                  Operations section of the specification (verbatim for
                  NON-PUBLIC, filtered/generalized for PUBLIC, or
                  `_No manual operations required for this change._` if none
                  survive); **c. Caveats & Known Limitations** from the Caveats
                  section of the specification, each app link written as its plain
                  title (same visibility filtering — NON-PUBLIC gets every
                  Caveat, PUBLIC gets only the ones that concern it with any
                  non-public-only Caveat dropped entirely, omit the section
                  if empty); **d. ❓ Open Decisions for Reviewer**, only if a
                  Caveat is still tagged "Needs human decision" after
                  filtering, else omit; **e. `## Companion PRs`** populated
                  (not a placeholder) from the sibling URLs in the known-PRs
                  set, applying the same visibility rule (NON-PUBLIC repo's
                  PR lists every sibling; PUBLIC repo's PR lists only PUBLIC
                  siblings; omit the section if none survive — never a
                  "none" note).
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
              `git commit` + `git push origin HEAD` on the working branch.
              Write `/workspace/out/review.md` documenting both what you
              found and a "Reasoning for fixes" section explaining WHY each
              fix was chosen and which commit(s) implement it. The
              orchestrator will route this back to Code Review for a
              fresh-session re-review on the next iteration.

            ## When to escalate

            Your system prompt describes the escalation mechanism. Escalate from this node when:

            - your fix would reverse a prior iteration's decision (`review_conflict`) — cite the
              prior commit SHA(s) in `escalation.md`;
            - the flaw is real but no candidate fix is clearly correct, or the implementation is
              structurally irrecoverable within the approved spec (`uncertainty`);
            - the tests are valid but the environment produces inconsistent failures with no
              clean in-repo fix (`environment`). The Supervisor can route past the Test gate;
              you cannot, so do not work around it in the repo.

            Do not push speculative commits when you escalate. The spec is already approved by
            this point — do not propose discarding it.

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
            - Standard git tooling (`git add`, `git commit`, `git push origin
              HEAD`) — credentials are configured by the entrypoint.
            - `artifact get` / `artifact put` — Pull/push files from/to object storage
              (used for review.md, not for code).

            ## Final reminder

            Do NOT call `report-result` until `/workspace/out/review.md` is
            written and any fix commits are pushed. The decision is final once
            submitted. Your task is not complete until you call `report-result`.""";

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

        NodeDefinition implement = createNodeDef("Implement", ExecutorType.ai, IMPLEMENT_PROMPT, 10800);
        implement.setModel(ModelIds.MODEL_SONNET);
        implement.setOutputSpec(
                "{\"files\":[{\"name\":\"summary.md\",\"required\":true,\"description\":\"Implementation summary describing changes made\"},"
                        + "{\"name\":\"roadmap_candidates.json\",\"required\":false,\"description\":\"Proposed roadmap extension for Final Approval (only when deferred work is proposed)\"}]}");
        nodeDefRepo.save(implement);
        defs.put("Implement", implement);

        defs.put("Test", createNodeDef("Test", ExecutorType.script, null, 7200));

        NodeDefinition codeReview = createNodeDef("Code Review", ExecutorType.ai, CODE_REVIEW_PROMPT, 10800);
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
                "{\"loop_group\": \"spec-review\"}",
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
                "{\"loop_group\": \"impl-review\", \"terminal_decisions\": [\"approved\"], \"materialize\": \"roadmap_extension\", \"merge_pull_requests\": \"squash\"}",
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
