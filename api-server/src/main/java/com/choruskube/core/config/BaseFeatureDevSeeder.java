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
    static final int CURRENT_VERSION = 39;

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
              narrative they use to evaluate architecture, decisions, repo
              collaboration, real-world risks, and operational handoff.
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
              - layer — which repo each statement belongs to. Keep §3 Architecture
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
              - §2 Decisions      -> docs/decisions/  (accumulates; entries are immutable)
              - §3 Architecture,
                §4 Flow Diagrams  -> merge into ARCHITECTURE.md (rewritten in place, so it
                                     does NOT accumulate); this is present-tense state, not
                                     a record of this change
              - §7 Caveats tagged "Future work" -> open a GitHub issue or a roadmap item;
                                     do not create a new docs/ surface for them
              - §1, §5, §6, §8 and Part 2 -> discard; they are execution scaffolding
            Graduate a decision only when something in this repo cites it. Do not bulk-copy
            the spec.

            Graduation is selective, so a decision you graduate can reference one you left
            behind. Resolve or delete every such reference as you write the entry: state what
            the other decision settled, or drop the clause. A reference to something the
            reader cannot open is worse than no reference.

            These docs are derived from the spec. A run owns exactly one decisions file —
            docs/decisions/YYYY-MM-DD---NN-<slug>.md — holding every decision that run
            graduates. On a re-run, edit that same file; never create a second one. The spec
            may have changed since an earlier iteration wrote it and may now conflict with
            what is there — amend or rewrite as you judge fit. ARCHITECTURE.md is an existing
            living document; merge into it rather than adding a parallel file.

            Every entry you graduate gets its own row in docs/decisions/README.md's index,
            newest last: the filename linked, its date, one line on the question it settles,
            and status `current`. An entry missing from that index is invisible to the next
            run's supersession check below, so it cannot be marked stale later — the
            directory then accumulates decisions no reader can tell are still true.

            Before graduating a decision, check that same index for an entry this run's
            decision reverses or replaces. If one exists, do not edit it. Add your entry as
            normal, name in it which entry it supersedes, and change the old entry's status
            to `superseded by <your entry>`.

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

                 **b. ⚠️ Manual Operations Required** — from section §8 of the
                 specification (the Local/Production table AND the "Notes for
                 production rollout" block), under a top-level
                 `## ⚠️ Manual Operations Required` heading.
                   - For a NON-PUBLIC repo: copy §8 verbatim.
                   - For a PUBLIC repo: include ONLY the operations that apply
                     to this repo, rewritten to remove other-repo names,
                     internal hosts, cluster/namespace detail, and internal
                     paths. Drop rows that describe work in a non-public repo
                     entirely — do not replace them with a placeholder row that
                     implies one exists.
                   - If §8 is empty, absent, or nothing survives the filter,
                     write `_No manual operations required for this change._`
                     instead.

                 **c. Caveats & Known Limitations** — under a top-level
                 `## Caveats & Known Limitations` heading, list each Caveat from
                 §7 of the specification: title and Disposition tag, "What's
                 not handled" line, "Reasoning" line. §7 is cross-repo content
                 like §8, so the same visibility rule applies: NON-PUBLIC repo
                 gets every Caveat; PUBLIC repo gets only the Caveats that
                 concern it, generalized to remove other-repo names and
                 infrastructure detail, with any Caveat that exists only
                 because a non-public repo is involved dropped entirely rather
                 than reworded into a hint that one exists. If §7 is empty, or
                 nothing survives the filter, omit this section entirely.

                 **d. ❓ Open Decisions for Reviewer** — ONLY include this
                 section if at least one Caveat is still tagged "Needs human
                 decision". When included, render it under a top-level
                 `## ❓ Open Decisions for Reviewer` heading: restate the open
                 question in one sentence, list the options the spec laid out
                 (if any), and make clear that merging implies accepting the
                 default behavior described in the Caveat's Reasoning. Inherits
                 §7's visibility filter. Omit entirely if none remain.

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
            not merge PRs — a human will review and merge after Final Approval.

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
                  PUBLIC); **b. ⚠️ Manual Operations Required** from spec §8
                  (verbatim for NON-PUBLIC, filtered/generalized for PUBLIC,
                  or `_No manual operations required for this change._` if
                  none survive); **c. Caveats & Known Limitations** from spec
                  §7 (same visibility filtering — NON-PUBLIC gets every
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
                createNodeDef("Draft Spec & Plan", ExecutorType.ai, SPEC_AND_PLAN_PROMPT, 1800);
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
                "{\"files\":[{\"name\":\"summary.md\",\"required\":true,\"description\":\"Implementation summary describing changes made\"}]}");
        nodeDefRepo.save(implement);
        defs.put("Implement", implement);

        defs.put("Test", createNodeDef("Test", ExecutorType.script, null, 7200));

        NodeDefinition codeReview = createNodeDef("Code Review", ExecutorType.ai, CODE_REVIEW_PROMPT, 10800);
        codeReview.setOutputSpec(
                "{\"files\":[{\"name\":\"review.md\",\"required\":true,\"description\":\"Code review findings and approve/reject recommendation\"}]}");
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
                "[{\"template_node_label\":\"spec_review\",\"artifacts\":[{\"name\":\"spec_and_plan.md\",\"description\":\"The approved spec to implement\",\"required\":true}]}]");
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
                "{\"loop_group\": \"impl-review\", \"needs_branch\": \"true\", \"needs_pr\": \"true\", "
                        + "\"model_first_iteration\": \"" + ModelIds.MODEL_OPUS + "\", "
                        + "\"effort_first_iteration\": \"xhigh\", "
                        + "\"model_subsequent_iteration\": \"" + ModelIds.MODEL_SONNET + "\", "
                        + "\"effort_subsequent_iteration\": \"high\"}",
                "[{\"template_node_label\":\"code_review\",\"artifacts\":[{\"name\":\"review.md\",\"description\":\"Prior iteration's code review notes including Reasoning for fixes (only present if iteration > 1)\",\"required\":false}]}]");
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
                "{\"loop_group\": \"impl-review\", \"terminal_decisions\": [\"approved\"]}",
                "[{\"template_node_label\":\"implement\",\"artifacts\":[{\"name\":\"summary.md\",\"description\":\"Implementation summary describing changes made\",\"required\":true}]},{\"template_node_label\":\"code_review\",\"artifacts\":[{\"name\":\"review.md\",\"description\":\"Code review findings and approve/reject recommendation\",\"required\":true}]}]");

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
