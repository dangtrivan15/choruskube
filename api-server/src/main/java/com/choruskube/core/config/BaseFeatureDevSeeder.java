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
    static final int CURRENT_VERSION = 26;

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
            conventions before writing. When repos are independent, you may explore them
            in parallel by dispatching Task subagents — one per repo — and consolidating
            their findings.

            Your output is a SINGLE document with two clearly separated parts:

            - Part 1: Specification — for human reviewers. A coherent cross-repo
              narrative they use to evaluate architecture, decisions, repo
              collaboration, real-world risks, and operational handoff.
            - Part 2: Implementation Plan — for AI implementers. Per-repo, file-level,
              ordered tasks the downstream Implement node will execute.

            The two parts have different audiences, structures, and rules. Do not mix
            them. Implementation details belong in Part 2; design rationale belongs in
            Part 1.

            ═══════════════════════════════════════════════════════════════════════
            Part 1: Specification (for human reviewers)
            ═══════════════════════════════════════════════════════════════════════

            Write Part 1 as a single cross-repo narrative. Per-repo subsections do NOT
            appear in sections 2, 3, 4, 6, 7, or 8 — those describe the system as a
            whole. Per-repo grouping appears ONLY in section 5 (Expected Changed
            Files), where reviewers need to scan their own repo's surface.

            Use the following EIGHT sections, in this order. Section numbers and
            titles must match exactly.

            ## 1. Summary

            One short paragraph in plain language: what the feature does for the user,
            the user-visible outcome, and a one-line list of repos touched. No
            decisions or rationale yet — orientation only.

            ## 2. Decisions

            The substantive design choices a reviewer must understand to evaluate the
            design. Cross-repo by default. Document a decision when ANY of these is
            true:

            - Multiple viable approaches existed and you picked one.
            - The choice introduces a new component, abstraction, term, or interface
              the reviewer must understand.
            - The choice meaningfully constrains future work or other repos.

            For EACH decision, use this exact block:

              ### Decision N: <short title>
              - **Context:** what forced a choice
              - **Choice:** what we picked (one line)
              - **Alternatives considered:**
                - Option A — one-line description
                - Option B — one-line description
                (or "None viable" — then explain in Why this choice)
              - **Tradeoff:** what we give up OR take on by choosing this. For
                alternative-driven choices: the cost of not picking the runner-up.
                For novel-component choices: the new maintenance / complexity /
                surface area we now own.
              - **Why this choice:** the deciding factor

            Repo-local style choices (e.g., "use record vs. class") do NOT belong
            here unless they shape an interface another repo depends on.

            ## 3. Architecture

            A single-voice description of the system, organized BY SEAM /
            RESPONSIBILITY, NOT by repo. Each seam is a `### 3.x <Seam Name>`
            subsection containing:

            - A 2–4 sentence prose description of the seam's purpose and runtime
              contract.
            - A bulleted "who does what" listing each affected repo's component and
              its role IN THIS SEAM. The same repo may appear under multiple seams.
            - A reference back to Decisions by number when the seam embodies a chosen
              tradeoff (e.g., "Reflects Decision 2").
            - OPTIONAL: a small mermaid `flowchart` showing static topology, ONLY
              when the seam involves ≥3 components OR crosses a trust boundary. Skip
              it if prose suffices.

            NO code snippets, NO file paths in this section. Those belong in §5 or
            in Part 2.

            ## 4. Flow Diagrams

            End-to-end runtime behavior across all affected repos, expressed as one
            or more mermaid `sequenceDiagram` blocks. REQUIRED, not optional.

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

            ## 5. Expected Changed Files

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

            ## 6. Testing Strategy

            Describe WHAT FLOWS AND BEHAVIORS will be tested, not which files or
            test methods will be added. Audience is the human reviewer assessing
            coverage, not the agent writing the tests.

            - E2E: user-facing flows that must work after the change.
            - Integration: cross-component contracts (API shape, headers, status
              codes, error semantics).
            - Behavioral: failure modes, fallbacks, edge conditions.
            - Negative / security: explicitly state what should be rejected.

            Files and test method names go in Part 2, not here.

            ## 7. Caveats

            Things the spec does NOT handle. If a risk has a mitigation IN the spec,
            it is part of the design, not a caveat — drop it.

            For EACH caveat, use this exact block:

              ### Caveat N: <short title>
              - **What's not handled:** one line
              - **Disposition:** Accepted | Future work | Needs human decision
              - **Reasoning:** why this disposition; what would trigger revisiting

            "Needs human decision" is an alarm bell — use it sparingly, only when
            the choice genuinely cannot be defaulted. Most caveats are Accepted or
            Future work.

            The "Out of scope" content traditionally listed separately belongs here
            with disposition "Accepted".

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
              in §6.
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
            - §3 contains no per-repo subsections. §5 contains nothing but per-repo
              one-line file lists. They have inverted structures by design.
            - All diagrams are inside ```mermaid fences. No raw graph/edge JSON.
            - Part 1 contains no code bodies. Part 2 contains no design rationale.
            - A typical feature spec runs 200–500 lines. If yours is much longer,
              Part 2 has likely leaked into Part 1, or §7 contains "risks" that are
              actually handled in the design.

            If the feature request is vague or underspecified, make reasonable
            assumptions based on the codebases and document them as Caveats with
            disposition "Needs human decision". Do not ask for clarification.

            {review_history}

            Save the document as /workspace/out/spec_and_plan.md.""";

    private static final String SPEC_REVIEW_PROMPT = """
            You are a self-iterating spec reviewer. You FIND flaws AND FIX them in
            the same session, then submit the appropriate decision. The bouncing
            review pattern (reject → re-author → re-review) is gone — when you find
            something fixable, you fix it here, in this invocation.

            ## Iteration awareness

            Read `iteration` from /workspace/config.json. The iteration cap for
            Spec Review is **3**. The cap exists because spec iterations are
            expensive (cross-repo reasoning) and converging quickly matters. If
            you reach iteration 3 with flaws still unresolved, escalate to a human
            via `need_human_decision:iteration_cap` rather than guessing.

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
              downstream human gate sent this back via `rereview` or `redraft`.
              When present, this is direction from the human reviewer; honor it.
            - Repositories are cloned under `/workspace/repo/<name>/`. You may
              dispatch Task subagents to examine multiple repos in parallel.

            **Iteration-1 special case:** if `/workspace/in/spec_review/` is empty
            or absent, this is iteration 1. There is no prior iteration to read;
            you are reviewing the original draft.

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

              Part 1: §1 Summary, §2 Decisions, §3 Architecture, §4 Flow Diagrams,
              §5 Expected Changed Files, §6 Testing Strategy, §7 Caveats,
              §8 Manual Operations
              Part 2: Implementation Plan (task ordering, cross-repo sync points,
              file-level test cases, migrations, verification commands)

            ## What to check

            1. **Format conformance**: §1–§8 + Part 2 in order with exact titles;
               §3 organized by seam/responsibility (NOT per-repo); §5 is per-repo,
               one line per file, no method bodies; mermaid diagrams in ```mermaid
               fences; Decision blocks have all five fields; Caveat blocks have a
               Disposition tag; §8 has the Local/Production table.
            2. **Internal consistency**: Part 2 file changes match §5; migrations
               match §2/§3; §6 testing strategy matches Part 2 file-level tests.
            3. **Cross-repo contract coherence**: shared surfaces defined
               identically on both sides (method/path/body/response).
            4. **Decision soundness**: Tradeoffs honest (not all-upsides);
               Why-this-choice not just a restatement.
            5. **Missing details**: failure modes without coverage in §3/§6 or §7
               (with disposition) are gaps.
            6. **Caveat hygiene**: a "Caveat" pointing to a mitigation already in
               the spec is design, not a caveat.
            7. **Maintainability risk**: couplings, god classes, brittle
               abstractions — per repo and at the seams.
            8. **Scope creep**: Part 2 work not justified by Part 1; Part 1
               promises Part 2 doesn't deliver.
            9. **Convention violations per repo**: package structure, naming,
               test style.
            10. **Deployment gaps**: env vars, secrets, migrations, config absent
                from §8 or Part 2 migrations.

            ## Decision tree

            Pick exactly one of:

            - **`approved`** — No flaws found. Copy the input spec verbatim to
              `/workspace/out/spec_and_plan.md`. Write a short
              `/workspace/out/spec_review.md` confirming approval.

            - **`revised`** — Flaws found AND fixable within the current
              decomposition/architecture AND iteration < 3. Apply the fixes to
              `/workspace/out/spec_and_plan.md`. In `/workspace/out/spec_review.md`
              include a "Reasoning for fixes" section explaining WHY each fix was
              chosen. The orchestrator will route this back to Spec Review for a
              fresh-session re-review on the next iteration.

            - **`need_human_decision:iteration_cap`** — Flaws found AND iteration
              >= 3. Do not invent fixes you are not confident about. In
              `/workspace/out/spec_review.md` write a structured "Remaining
              unresolved flaws" section listing each flaw with what you tried and
              why it didn't resolve. Still write `spec_and_plan.md` with whatever
              partial fixes you applied (or copy the input verbatim if you
              applied none).

            - **`need_human_decision:uncertainty`** — A flaw is found but the
              correct fix is unclear. Do not guess. In
              `/workspace/out/spec_review.md` write a structured "Uncertain flaw"
              section: describe the flaw, the candidate fixes you considered, and
              why none of them is clearly correct. Still write the input spec
              verbatim (no partial fixes when uncertain).

            - **`need_human_decision:alternative_proposal`** — A fundamentally
              different architectural approach would be better. Do NOT apply the
              alternative — submit it as a proposal so the human can decide. In
              `/workspace/out/spec_review.md` write a structured proposal: (a)
              current approach's limitations, (b) the alternative, (c) tradeoffs
              between current and alternative. Still write the input spec
              verbatim — the alternative is a proposal, not the new spec.

            ## Boundary heuristic for `alternative_proposal`

            Apply fixes (and emit `revised`) when they preserve:
            - **Decomposition**: which components, which repos, which boundaries
            - **Architecture**: sync vs async, data flow shape, ownership model

            If a fix would change either, do NOT apply it. Emit
            `need_human_decision:alternative_proposal` instead. The asymmetry is
            deliberate: revising within the current architecture is normal review
            work; proposing a new architecture is a design decision that needs a
            human.

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

            {review_history}

            ## Final reminder

            Do NOT call `report-result` until both `/workspace/out/spec_and_plan.md`
            and `/workspace/out/spec_review.md` are written. The decision is final
            once submitted. Your task is not complete until you call
            `report-result`.""";

    private static final String IMPLEMENT_PROMPT = """
            You are implementing a feature based on an approved spec and plan. The
            feature may span multiple repositories.

            The drafting node produced a single document with two parts:

            - Part 1 (Specification, sections §1–§8) — context, decisions,
              architecture, caveats, manual operations. READ FIRST to understand
              intent and constraints.
            - Part 2 (Implementation Plan) — your actionable task list with file
              changes, ordering, cross-repo sync points, file-level test cases,
              migrations, and verification commands. EXECUTE Part 2 step by step;
              refer back to Part 1 whenever Part 2 is ambiguous about intent.

            If Part 2 conflicts with Part 1 (e.g., a file path in Part 2 doesn't
            match §5 Expected Changed Files, or a migration contradicts §2
            Decisions), follow Part 1's intent and document the discrepancy in
            your summary.

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
            - **Do NOT run `gh pr create` or open a pull request.** A separate
              "Push & Create PR" node owns PR creation. Your responsibility ends
              at `git push`.

            For repos with cross-repo dependencies (the plan will call these out),
            implement them in the ordered sequence specified in the plan; do not
            dispatch those in parallel.

            Note: this node may be a retry of a previous attempt. Before you start,
            take a quick look at the current state of each repo's working branch —
            prior commits, partial changes, or anything left over. Use that context
            to decide how to proceed.

            If you encounter build errors or ambiguities in the plan, fix them
            yourself. Try multiple approaches if the first one fails. A quick
            compile or type-check is fine to catch obvious breakage, but the
            authoritative test gate is the Test node downstream — not your local
            invocation.

            Before finishing, verify the implementation in each affected repo:
            - All plan steps for that repo are addressed
            - Tests are written (the Test node will execute them)
            - Code quality is clean (no dead code, no debug artifacts, no unused imports)
            - Changes match the spec intent, not just the plan mechanics
            - All changes committed and pushed to the working branch
            - No pull request was opened from this node

            Save a summary of what you changed (per repo) as /workspace/out/summary.md.

            {review_history}""";

    private static final String CODE_REVIEW_PROMPT = """
            You are a self-iterating code reviewer. You FIND flaws AND FIX them in
            the same session — by editing the code, committing, and pushing — then
            submit the appropriate decision. The bouncing review pattern (reject
            → re-implement → re-review) is gone: when you find something fixable,
            you fix it here, in this invocation, on the working git branch.

            ## Iteration awareness

            Read `iteration` from /workspace/config.json. The iteration cap for
            Code Review is **5**. Code-level flaws tend to be incremental (typo,
            missing test, wrong import) so the cap is more generous than Spec
            Review's. If you reach iteration 5 with flaws still unresolved,
            escalate to a human via `need_human_decision:iteration_cap` rather
            than guessing.

            Note: by the time code reaches you, the spec is already approved.
            Architectural alternatives are NOT in scope here — if you find the
            implementation is structurally irrecoverable within the current spec,
            escalate via `need_human_decision:iteration_cap` (cannot converge) or
            `need_human_decision:uncertainty` (don't know how to proceed). Do NOT
            propose to discard the spec.

            ## Inputs

            - `/workspace/repo/` (or `/workspace/repo/<name>/` for multi-repo) —
              the working git branch checkout. The IMPLEMENTATION lives on this
              branch, not in object storage. This is your primary review target.
            - `/workspace/in/code_review/review.md` — the prior iteration's
              review notes (including "Reasoning for fixes"). Present only if
              iteration > 1. Read this carefully so you build on prior decisions
              instead of reverting them.
            - `/workspace/in/run_log.md` — accumulated history including the
              Implement node summary and any test reports.
            - `/workspace/in/<gate_label>/human_guidance.md` — present only if
              Final Approval sent this back via `rereview`. When present, this is
              direction from the human reviewer; honor it.

            **Iteration-1 special case:** if `/workspace/in/code_review/` is
            empty or absent, this is iteration 1. There is no prior review to
            read; you are reviewing the implementation as it stands.

            ## Outputs

            - `/workspace/out/review.md` — REQUIRED. Your review notes. If you
              applied fixes, include a "Reasoning for fixes" section that
              explains WHY each fix was chosen (not just what changed) and which
              files/commits implement it. Subsequent iterations read this to
              build on or challenge prior decisions without reverting them.

            **Code fixes go to git, not to object storage.** When you apply a fix, edit
            the code in `/workspace/repo/...`, run the relevant tests locally,
            then `git add` → `git commit -m "review: <what you fixed>"` →
            `git push origin HEAD`. The branch state is the source of truth;
            Push & Create PR later reads from this branch. Do NOT write code to
            `/workspace/out/`.

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

            - **`revised`** — Flaws found AND fixable AND iteration < 5. Apply
              the fixes via `git commit` + `git push origin HEAD` on the working
              branch. Write `/workspace/out/review.md` documenting both what you
              found and a "Reasoning for fixes" section explaining WHY each fix
              was chosen and which commit(s) implement it. The orchestrator will
              route this back to Code Review for a fresh-session re-review on
              the next iteration.

            - **`need_human_decision:iteration_cap`** — Flaws found AND
              iteration >= 5. Do not invent fixes you are not confident about.
              In `/workspace/out/review.md` write a structured "Remaining
              unresolved flaws" section listing each flaw with what you tried
              and why it didn't resolve. You may have applied partial fixes
              (already pushed to the branch) — note them.

            - **`need_human_decision:uncertainty`** — A flaw is found but the
              correct fix is unclear, OR the implementation appears structurally
              irrecoverable within the current spec. Do not guess and do not
              push speculative commits. In `/workspace/out/review.md` write a
              structured "Uncertain flaw" section: describe the flaw, the
              candidate fixes you considered, and why none of them is clearly
              correct. One more valid case of this decision is when the test is valid
              but the testing environment isn't leading to consistent test failure,
              and there is no clean way to fix the test without environmental workaround.
              This decision will be escalated straight to Human without passing the Test Node,
              so be extra careful when making this decision.

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
            - Standard git tooling (`git add`, `git commit`, `git push origin
              HEAD`) — credentials are configured by the entrypoint.
            - `artifact get` / `artifact put` — Pull/push files from/to object storage
              (used for review.md, not for code).

            {review_history}

            ## Final reminder

            Do NOT call `report-result` until `/workspace/out/review.md` is
            written and any fix commits are pushed. The decision is final once
            submitted. Your task is not complete until you call `report-result`.""";

    private static final String PUSH_PR_PROMPT = """
            You are creating pull requests for a completed multi-repository feature
            implementation. Create ONE pull request per affected repository, with
            companion-PR cross-links (subject to the repository-visibility rules
            below) and explicit surfacing of (a) manual operations the reviewer
            must perform and (b) caveats the spec intentionally did not handle.

            Specification:
            {input.draft_spec_and_plan.result}

            Spec review notes (use to augment Caveats with resolution context):
            {input.spec_review.result}

            Implementation summary:
            {input.implement.result}

            Code review:
            {input.code_review.result}

            Repositories are cloned under /workspace/repo/<name>/ on the working branch.
            Per-repo metadata (id, url, name) is in /workspace/config.json under the
            "repos" array.

            ## Steps

            0. **Resolve each repo's visibility FIRST**, before writing any PR text.
               For each repo in config.json, run
               `gh repo view <owner/repo> --json visibility`. Treat a repo as PUBLIC
               if the command fails or the answer is unclear (fail-safe). Carry this
               classification through every step below — it governs what may appear
               in each PR body. See "Repository Isolation" in your system prompt for
               the full rule; it overrides any instruction here that conflicts with it.

               A PUBLIC repo's PR must not name, link to, or otherwise reveal the
               existence of any non-public repo in this run, and must not carry that
               repo's infrastructure detail. Scope and generalize its content instead.

            1. **Per repo, push and open a PR**. For each repo with a committed working
               branch, in /workspace/repo/<name>/:
               - Ensure all changes are committed and the branch is pushed to origin.
               - Create a pull request against the default branch via `gh pr create`.
               - PR title: a concise summary of this repo's portion of the feature.
               - PR body, in this exact order:

                 **a. Summary** — per-repo implementation summary scoped to this
                 repo (from {input.implement.result}). For a PUBLIC repo, describe
                 only this repo's change and justify it on grounds that hold within
                 this repo alone; do not explain it by reference to another repo.

                 **b. Code Review Findings** — relevant excerpt from
                 {input.code_review.result}, scoped to this repo. Never paste the
                 review verbatim into a PUBLIC repo's PR: review notes routinely
                 discuss every repo in the run. Summarize only the findings that
                 concern this repo, and generalize any cross-repo reasoning.

                 **c. ⚠️ Manual Operations Required** — from section §8 of the
                 specification (the Local/Production table AND the "Notes for
                 production rollout" block), under a top-level
                 `## ⚠️ Manual Operations Required` heading.
                   - For a NON-PUBLIC repo: copy §8 verbatim. It is cross-repo
                     content — every non-public repo's PR gets the same full block.
                   - For a PUBLIC repo: include ONLY the operations that apply to
                     this repo, rewritten to remove other-repo names, internal
                     hosts, cluster/namespace detail, and internal paths. Drop rows
                     that describe work in a non-public repo entirely — do not
                     replace them with a placeholder row that implies one exists.
                   - If §8 is empty, absent, or nothing survives the filter, write
                     `_No manual operations required for this change._` instead.

                 **d. Caveats & Known Limitations** — under a top-level
                 `## Caveats & Known Limitations` heading, list each Caveat from
                 §7 of the specification:
                   - Title and Disposition tag (Accepted / Future work /
                     Needs human decision).
                   - "What's not handled" line.
                   - "Reasoning" line.
                   - If the spec review notes contain a resolution or comment
                     that materially clarifies how this Caveat was addressed
                     during review, append a `> Resolution from review:`
                     blockquote with a one-line summary. Do not invent
                     resolutions; only include this when the review notes
                     clearly speak to the caveat.
                 §7 is cross-repo content, exactly like §8, so the same
                 visibility rule applies. For a NON-PUBLIC repo: list every
                 Caveat. For a PUBLIC repo: list only the Caveats that concern
                 this repo, generalized to remove other-repo names and
                 infrastructure detail; DROP any Caveat that exists only because
                 a non-public repo is involved, rather than rewording it into a
                 hint that one exists.
                 If §7 is empty, or nothing survives the filter, omit this
                 section entirely.

                 **e. ❓ Open Decisions for Reviewer** — ONLY include this section
                 IF, after applying any review-note resolutions, there is at
                 least one Caveat still tagged "Needs human decision". When
                 included, render it under a top-level
                 `## ❓ Open Decisions for Reviewer` heading. For each
                 unresolved decision:
                   - Restate the open question in one sentence.
                   - List the options the spec laid out (if any).
                   - Make clear that merging the PR implies accepting the
                     default behavior described in the Caveat's Reasoning.
                 This section is derived from §7, so it inherits §7's visibility
                 filter: a decision that was dropped or generalized for a PUBLIC
                 repo above must be dropped or generalized here too. Never
                 restate an open question that only makes sense if the reader
                 knows a non-public repo exists.
                 If all "Needs human decision" caveats were resolved during
                 review, omit this section entirely. Its presence is a signal
                 that the merging reviewer must make a call before merging.

                 **f. Companion PRs placeholder** — a single line:
                 `_Companion PRs: (linked after all PRs are created)_`.
                 OMIT this placeholder entirely if this repo will have no linkable
                 companions under the step-3 rules (e.g. a PUBLIC repo in a run
                 whose other repos are all non-public).

               - Record the PR URL and PR number.

               You may dispatch Task subagents to create PRs in parallel — one per repo.

            2. **Register each PR with ChorusKube** by running, for each PR:

                   register-pr --repo-id <gitRepoId> --pr-url <url> \\
                       --pr-number <number> --title <title> --repo-name <name>

               Use the `id` from config.json's repos[] as `<gitRepoId>`.

            3. **Cross-link PRs, honoring visibility**. After all PRs are created,
               for each PR edit its body (via `gh pr edit <url> --body <new_body>`)
               to replace the Companion PRs placeholder with a `## Companion PRs`
               section. Which PRs may be listed depends on the visibility of the
               repo whose body you are editing:
                 - NON-PUBLIC repo's PR: list every OTHER PR in the set.
                 - PUBLIC repo's PR: list ONLY the other PRs whose repos are also
                   PUBLIC. Never link or name a non-public repo's PR from a public
                   repo — the URL alone discloses that repo's existence.
               If no companions remain listable for a public repo, omit the
               `## Companion PRs` section entirely. Do NOT write "none", "not
               applicable", or any note explaining why it is empty — such a note
               discloses exactly what the rule exists to withhold.
               PRESERVE the ⚠️ Manual Operations Required, Caveats & Known
               Limitations, and ❓ Open Decisions for Reviewer sections during
               this edit.

            4. **Save artifacts**:
               - /workspace/out/pr_urls.txt — one URL per line, in repo order.
               - /workspace/out/pr_summary.md — brief summary of what was shipped.
                 Include one-line notes if Manual Operations are required and/or
                 if any Open Decisions for Reviewer remain, so the run log makes
                 the operational handoff visible.

            Do not merge the PRs. A human will review and merge.""";

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
                createNodeDef("Draft Spec & Plan", ExecutorType.ai, SPEC_AND_PLAN_PROMPT, 1800);
        draftSpecAndPlan.setOutputSpec(
                "{\"files\":[{\"name\":\"spec_and_plan.md\",\"required\":true,\"description\":\"Technical specification and implementation plan\"}]}");
        nodeDefRepo.save(draftSpecAndPlan);
        defs.put("Draft Spec & Plan", draftSpecAndPlan);

        NodeDefinition specReview = createNodeDef("Spec Review", ExecutorType.ai, SPEC_REVIEW_PROMPT, 1800);
        specReview.setOutputSpec(
                "{\"files\":[{\"name\":\"spec_review.md\",\"required\":true,\"description\":\"AI reviewer assessment and recommendations\"}]}");
        // At iteration 3, submitDecision() silently overrides any non-approved decision to
        // need_human_decision:iteration_cap, routing to the approve_spec_and_plan gate.
        // list-decisions always returns the full set; the cap fires transparently at submit time.
        // Without this the revised self-loop has no termination condition other than the AI
        // volunteering to stop.
        specReview.setIterationCap(3);
        nodeDefRepo.save(specReview);
        defs.put("Spec Review", specReview);

        defs.put("Approve Spec & Plan", createNodeDef("Approve Spec & Plan", ExecutorType.human, null, 86400));

        NodeDefinition implement = createNodeDef("Implement", ExecutorType.ai, IMPLEMENT_PROMPT, 10800);
        implement.setOutputSpec(
                "{\"files\":[{\"name\":\"summary.md\",\"required\":true,\"description\":\"Implementation summary describing changes made\"}]}");
        nodeDefRepo.save(implement);
        defs.put("Implement", implement);

        defs.put("Test", createNodeDef("Test", ExecutorType.script, null, 7200));

        NodeDefinition codeReview = createNodeDef("Code Review", ExecutorType.ai, CODE_REVIEW_PROMPT, 1800);
        codeReview.setOutputSpec(
                "{\"files\":[{\"name\":\"review.md\",\"required\":true,\"description\":\"Code review findings and approve/reject recommendation\"}]}");
        // Code review can sustain more iterations than spec review — each pass reviews concrete
        // diffs, so progress is easier to confirm; 5 attempts before forcing escalation.
        codeReview.setIterationCap(5);
        nodeDefRepo.save(codeReview);
        defs.put("Code Review", codeReview);

        defs.put("Final Approval", createNodeDef("Final Approval", ExecutorType.human, null, 86400));

        NodeDefinition pushCreatePr = createNodeDef("Push & Create PR", ExecutorType.ai, PUSH_PR_PROMPT, 1800);
        pushCreatePr.setOutputSpec(
                "{\"files\":[{\"name\":\"pr_summary.md\",\"required\":false,\"description\":\"PR creation confirmation and link\"}]}");
        // Runs on the default model, NOT a cheaper tier. This node was tiered down to
        // Haiku through v25 on the premise that it is mechanical (stitch caveats, push
        // commits, open PR). That premise no longer holds: from v26 it must classify
        // each repo's visibility and decide what to drop or generalize before writing a
        // PR body, and a misjudgment publishes non-public detail irreversibly.
        nodeDefRepo.save(pushCreatePr);
        defs.put("Push & Create PR", pushCreatePr);

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

        // v24 single-test-gate layout. Multi-repo coarse-rejection: a single
        // Implement node operates across all repos (internally parallelized via
        // Task subagents). Code Review can self-loop to apply fixes; the Test
        // gate sits AFTER Code Review so commits Code Review pushes during its
        // self-loop are validated before reaching Final Approval. Test failure
        // routes back to Implement (single failure path; loop bound by external
        // human intervention via Final Approval rereview).
        //
        //   Spec subgraph (unchanged from v23):
        //     [Draft S&P] → [Spec Review] ⇄ (revised) → [Approve S&P]
        //
        //   Impl subgraph (v24):
        //     [Implement] → [Code Review] ⇄ revised (self-loop)
        //                       ↓ approved | need_human_decision:*
        //                    [Test]
        //                       ├── passed ──→ [Final Approval]
        //                       │                  ├── approved ──→ [Push & Create PR]
        //                       │                  └── rereview ──→ [Code Review]
        //                       └── failed ──→ [Implement]

        TemplateNode tnDraftSpecAndPlan = createNode(
                template,
                nodeDefs.get("Draft Spec & Plan"),
                "draft_spec_and_plan",
                true,
                "{\"loop_group\": \"spec-review\"}");
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
                "{\"loop_group\": \"spec-review\"}",
                "[{\"template_node_label\":\"draft_spec_and_plan\",\"artifacts\":[{\"name\":\"spec_and_plan.md\",\"description\":\"Original draft spec from the first author\"}]},{\"template_node_label\":\"spec_review\",\"artifacts\":[{\"name\":\"spec_and_plan.md\",\"description\":\"Prior iteration's revised spec (only present if iteration > 1)\"},{\"name\":\"spec_review.md\",\"description\":\"Prior iteration's review notes including Reasoning for fixes (only present if iteration > 1)\"}]}]");
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
                "[{\"template_node_label\":\"spec_review\",\"artifacts\":[{\"name\":\"spec_and_plan.md\",\"description\":\"The reviewed (and possibly revised) spec to approve\"},{\"name\":\"spec_review.md\",\"description\":\"Reviewer notes — needed when escalating via need_human_decision:*\"}]}]");

        // Implement reads spec_and_plan.md from Spec Review (ownership transfer),
        // not from Draft Spec.
        TemplateNode tnImplement = createNode(
                template,
                nodeDefs.get("Implement"),
                "implement",
                false,
                "{\"loop_group\": \"impl-review\", \"needs_branch\": \"true\"}",
                "[{\"template_node_label\":\"spec_review\",\"artifacts\":[{\"name\":\"spec_and_plan.md\",\"description\":\"The approved spec to implement\"}]}]");
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
        TemplateNode tnCodeReview = createNode(
                template,
                nodeDefs.get("Code Review"),
                "code_review",
                false,
                "{\"loop_group\": \"impl-review\", \"needs_branch\": \"true\"}",
                "[{\"template_node_label\":\"code_review\",\"artifacts\":[{\"name\":\"review.md\",\"description\":\"Prior iteration's code review notes including Reasoning for fixes (only present if iteration > 1)\"}]}]");
        TemplateNode tnFinalApproval = createNode(
                template,
                nodeDefs.get("Final Approval"),
                "final_approval",
                false,
                "{\"loop_group\": \"impl-review\"}",
                "[{\"template_node_label\":\"implement\",\"artifacts\":[{\"name\":\"summary.md\",\"description\":\"Implementation summary describing changes made\"}]},{\"template_node_label\":\"code_review\",\"artifacts\":[{\"name\":\"review.md\",\"description\":\"Code review findings and approve/reject recommendation\"}]}]");
        TemplateNode tnPushCreatePr = createNode(
                template, nodeDefs.get("Push & Create PR"), "push_create_pr", false, "{\"needs_branch\": \"true\"}");

        // Create edges. v23 introduces self-iterating review nodes: review nodes
        // self-loop on `revised` (find AND fix in one session) and escalate to
        // human gates via suffix-variant `need_human_decision:*` decisions.
        // Approve Spec & Plan splits its rejection action into `rereview` (re-run
        // Spec Review with human guidance) and `redraft` (full re-author). Final
        // Approval gets only `rereview` — once a spec is approved, discarding the
        // implementation entirely is rare enough not to be a routable action.
        // Spec-review loop
        createEdge(template, tnDraftSpecAndPlan, tnSpecReview, null);
        createEdge(template, tnSpecReview, tnApproveSpecAndPlan, "approved");
        createEdge(template, tnSpecReview, tnApproveSpecAndPlan, "need_human_decision:alternative_proposal");
        createEdge(template, tnSpecReview, tnApproveSpecAndPlan, "need_human_decision:iteration_cap");
        createEdge(template, tnSpecReview, tnApproveSpecAndPlan, "need_human_decision:uncertainty");
        createEdge(template, tnSpecReview, tnSpecReview, "revised");
        createEdge(template, tnApproveSpecAndPlan, tnImplement, "approved");
        createEdge(template, tnApproveSpecAndPlan, tnSpecReview, "rereview");
        createEdge(template, tnApproveSpecAndPlan, tnDraftSpecAndPlan, "redraft");
        // Impl-review loop (v24: single Test gate placed after Code Review)
        createEdge(template, tnImplement, tnCodeReview, null);
        createEdge(template, tnCodeReview, tnCodeReview, "revised");
        createEdge(template, tnCodeReview, tnTest, "approved");
        createEdge(template, tnCodeReview, tnTest, "need_human_decision:iteration_cap");
        createEdge(template, tnCodeReview, tnTest, "need_human_decision:uncertainty");
        createEdge(template, tnTest, tnFinalApproval, "passed");
        createEdge(template, tnTest, tnImplement, "failed");
        createEdge(template, tnFinalApproval, tnPushCreatePr, "approved");
        createEdge(template, tnFinalApproval, tnCodeReview, "rereview");

        log.info(
                "BaseFeatureDevSeeder: seeded template graphId='{}' v{}: 8 template nodes, 18 edges (node defs shared)",
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
