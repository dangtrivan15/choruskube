package com.choruskube.core.service;

import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.NodeExecutionRepoBase;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.NodeExecutionRepoBaseRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Whether main moved on any of a run's repos since the node before its gate ran. That node
 * recorded, per repo, the default-branch commit its clone sat on; GitHub's compare says whether the
 * default branch has moved past it. A repo with no usable record counts as stale, so a missing
 * record costs one re-check, never a merge nobody tested.
 */
@Service
public class GateDriftCheckService {

    public static final int MAX_RECHECK_ROUNDS = 2;

    public static final String GUIDANCE_HEADING = "# Re-check against current main";

    private static final Logger log = LoggerFactory.getLogger(GateDriftCheckService.class);

    public enum Reason {
        CURRENT,
        MAIN_MOVED,
        CONFLICT,
        UNAVAILABLE,
        NO_RECORD,
        BASE_GONE
    }

    public record RepoDrift(
            UUID gitRepoId,
            String ownerRepo,
            String defaultBranch,
            Reason reason,
            String baseSha,
            int aheadBy,
            List<String> conflictedFiles) {

        public boolean stale() {
            return reason != Reason.CURRENT;
        }

        /** Plain text, for the gate's result note and the Worker-driven plan's refusal reason. */
        public String summary() {
            return switch (reason) {
                case CURRENT -> "current with " + defaultBranch;
                case MAIN_MOVED -> aheadBy + (aheadBy == 1 ? " commit" : " commits") + " behind " + defaultBranch;
                case CONFLICT ->
                    "rebasing onto " + defaultBranch + " conflicted"
                            + (conflictedFiles.isEmpty() ? "" : " in " + String.join(", ", conflictedFiles));
                case UNAVAILABLE -> "the last node could not fetch " + defaultBranch;
                case NO_RECORD -> "no record of which " + defaultBranch + " commit the last node ran on";
                case BASE_GONE -> "its base is no longer in " + defaultBranch + "'s history";
            };
        }
    }

    public record DriftCheck(NodeExecution predecessor, List<RepoDrift> repos) {

        public boolean stale() {
            return repos.stream().anyMatch(RepoDrift::stale);
        }

        public List<RepoDrift> staleRepos() {
            return repos.stream().filter(RepoDrift::stale).toList();
        }

        public String summary() {
            return staleRepos().stream()
                    .map(r -> r.ownerRepo() + " (" + r.summary() + ")")
                    .collect(Collectors.joining("; "));
        }
    }

    public record CompareInput(UUID gitRepoId, String ownerRepo, String branch, String baseSha) {}

    public record DriftInputs(NodeExecution predecessor, List<RepoDrift> known, List<CompareInput> toCompare) {}

    public record CompareResult(UUID gitRepoId, int aheadBy, boolean baseGone) {}

    private final NodeExecutionRepository execRepo;
    private final NodeExecutionRepoBaseRepository baseRepo;
    private final GitRepoRepository gitRepoRepo;
    private final RunPullRequestRepository prRepo;
    private final ObjectMapper objectMapper;

    public GateDriftCheckService(
            NodeExecutionRepository execRepo,
            NodeExecutionRepoBaseRepository baseRepo,
            GitRepoRepository gitRepoRepo,
            RunPullRequestRepository prRepo,
            ObjectMapper objectMapper) {
        this.execRepo = execRepo;
        this.baseRepo = baseRepo;
        this.gitRepoRepo = gitRepoRepo;
        this.prRepo = prRepo;
        this.objectMapper = objectMapper;
    }

    /** Makes no GitHub call and mints no token: a Worker runs the compares this leaves in {@code toCompare}. */
    public DriftInputs gather(UUID runId, UUID gateTemplateNodeId, JsonNode snapshot) {
        List<UUID> repoIds = repoIdsOf(snapshot);
        NodeExecution predecessor = predecessorOf(runId, gateTemplateNodeId, snapshot);
        if (repoIds.isEmpty() || predecessor == null) {
            // Nothing was built against any repo yet, so there is no tested state to protect.
            return new DriftInputs(predecessor, List.of(), List.of());
        }
        Map<UUID, NodeExecutionRepoBase> bases = baseRepo.findByNodeExecutionId(predecessor.getId()).stream()
                .collect(Collectors.toMap(NodeExecutionRepoBase::getGitRepoId, Function.identity(), (a, b) -> a));
        List<RepoDrift> known = new ArrayList<>();
        List<CompareInput> toCompare = new ArrayList<>();
        for (UUID repoId : repoIds) {
            GitRepo repo = gitRepoRepo.findById(repoId).orElse(null);
            if (repo == null) {
                // Removed from the project: there is no default branch to compare, and
                // merge-on-approval refuses its pull requests on its own.
                log.warn("Run {} cloned repo {}, which is no longer registered; skipping its re-check", runId, repoId);
                continue;
            }
            String ownerRepo = RepoNameUtil.deriveOwnerRepoName(repo.getUrl());
            NodeExecutionRepoBase base = bases.get(repoId);
            // The branch the pod rebased onto: a stored setting that differs from the host's real
            // default makes every compare 404, so the gate would read BASE_GONE on every approval.
            String branch =
                    base != null && base.getDefaultBranch() != null ? base.getDefaultBranch() : repo.getDefaultBranch();
            if (base == null) {
                known.add(new RepoDrift(repoId, ownerRepo, branch, Reason.NO_RECORD, null, 0, List.of()));
            } else if ("conflict".equals(base.getOutcome())) {
                known.add(new RepoDrift(repoId, ownerRepo, branch, Reason.CONFLICT, null, 0, files(base)));
            } else if ("unavailable".equals(base.getOutcome()) || base.getBaseSha() == null) {
                known.add(new RepoDrift(repoId, ownerRepo, branch, Reason.UNAVAILABLE, null, 0, List.of()));
            } else {
                toCompare.add(new CompareInput(repoId, ownerRepo, branch, base.getBaseSha()));
            }
        }
        return new DriftInputs(predecessor, known, toCompare);
    }

    /**
     * Folds compare answers into the drift verdict. A repo with no answer reads UNAVAILABLE, never
     * CURRENT, so a lost compare costs one re-check instead of a merge nobody tested.
     */
    public DriftCheck classify(DriftInputs inputs, List<CompareResult> results) {
        Map<UUID, CompareResult> byRepo =
                results.stream().collect(Collectors.toMap(CompareResult::gitRepoId, Function.identity(), (a, b) -> a));
        List<RepoDrift> repos = new ArrayList<>(inputs.known());
        for (CompareInput input : inputs.toCompare()) {
            repos.add(driftOf(input, byRepo.get(input.gitRepoId())));
        }
        return new DriftCheck(inputs.predecessor(), repos);
    }

    /** The {@code human_guidance.md} the routed-to node reads when an approval is held. */
    public String guidance(UUID runId, DriftCheck check, int round) {
        StringBuilder sb = new StringBuilder(GUIDANCE_HEADING + "\n\n")
                .append("A reviewer approved this run, but the default branch moved since it was last checked, ")
                .append("so the approval is on hold until this re-check passes (round ")
                .append(round)
                .append(" of ")
                .append(MAX_RECHECK_ROUNDS)
                .append(").\n\n");
        for (RepoDrift repo : check.staleRepos()) {
            sb.append("- `").append(repo.ownerRepo()).append("`: ").append(repo.summary());
            if (repo.baseSha() != null) {
                sb.append(" (base `").append(shortSha(repo.baseSha())).append("`)");
            }
            sb.append(".\n");
        }
        List<RunPullRequest> merged = mergedSince(runId, check);
        if (!merged.isEmpty()) {
            sb.append("\nPull requests other runs merged on these repos since then:\n\n");
            for (RunPullRequest pr : merged) {
                sb.append("- ").append(pr.getPrUrl());
                if (pr.getTitle() != null && !pr.getTitle().isBlank()) {
                    sb.append(" — ").append(pr.getTitle());
                }
                sb.append("\n");
            }
        }
        sb.append("\nRebase onto current main, resolve any conflicts, and check that this change still holds ")
                .append("against what merged: read those pull requests and any `docs/decisions/` entries they ")
                .append("added. If reconciling would change the approved spec's decomposition or architecture, ")
                .append("or reverse a decision a merged run made, escalate (`review_conflict`) and cite the ")
                .append("merged run.\n");
        return sb.toString();
    }

    private static RepoDrift driftOf(CompareInput in, CompareResult result) {
        if (result == null) {
            return new RepoDrift(in.gitRepoId(), in.ownerRepo(), in.branch(), Reason.UNAVAILABLE, null, 0, List.of());
        }
        if (result.baseGone()) {
            return new RepoDrift(
                    in.gitRepoId(), in.ownerRepo(), in.branch(), Reason.BASE_GONE, in.baseSha(), 0, List.of());
        }
        Reason reason = result.aheadBy() > 0 ? Reason.MAIN_MOVED : Reason.CURRENT;
        return new RepoDrift(
                in.gitRepoId(), in.ownerRepo(), in.branch(), reason, in.baseSha(), result.aheadBy(), List.of());
    }

    private List<RunPullRequest> mergedSince(UUID runId, DriftCheck check) {
        NodeExecution predecessor = check.predecessor();
        if (predecessor == null || predecessor.getStartedAt() == null) {
            return List.of();
        }
        List<UUID> repoIds =
                check.staleRepos().stream().map(RepoDrift::gitRepoId).toList();
        return repoIds.isEmpty()
                ? List.of()
                : prRepo.findMergedByOtherRunsSince(repoIds, predecessor.getStartedAt(), runId);
    }

    /**
     * The latest completed non-human execution of a node with an edge into the gate, else of any
     * node: a parallel branch finishing later would otherwise vouch for a base the gate never saw.
     */
    private NodeExecution predecessorOf(UUID runId, UUID gateTemplateNodeId, JsonNode snapshot) {
        Set<UUID> humanNodes = new HashSet<>();
        for (JsonNode node : snapshot.path("nodes")) {
            if ("human".equalsIgnoreCase(node.path("executor_type").asText())) {
                humanNodes.add(UUID.fromString(node.path("template_node_id").asText()));
            }
        }
        Set<UUID> upstream = new HashSet<>();
        for (JsonNode edge : snapshot.path("edges")) {
            if (gateTemplateNodeId.toString().equals(edge.path("target_node_id").asText())) {
                upstream.add(UUID.fromString(edge.path("source_node_id").asText()));
            }
        }
        List<NodeExecution> completed =
                execRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed).stream()
                        .filter(e -> e.getCompletedAt() != null && !humanNodes.contains(e.getTemplateNodeId()))
                        .toList();
        return latest(completed.stream().filter(e -> upstream.contains(e.getTemplateNodeId())))
                .orElseGet(() -> latest(completed.stream()).orElse(null));
    }

    private static Optional<NodeExecution> latest(Stream<NodeExecution> executions) {
        return executions.max(Comparator.comparing(NodeExecution::getCompletedAt));
    }

    // Read live from the project, while agent pods clone the list frozen at run start: a repo
    // added mid-run never gets a record, so the gate parks, and one removed mid-run goes unchecked.
    private static List<UUID> repoIdsOf(JsonNode snapshot) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode repo : snapshot.path("repos")) {
            String id = repo.path("id").asText("");
            if (!id.isBlank()) {
                ids.add(UUID.fromString(id));
            }
        }
        return ids;
    }

    private List<String> files(NodeExecutionRepoBase base) {
        try {
            return objectMapper.readValue(base.getConflictedFiles(), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }
}
