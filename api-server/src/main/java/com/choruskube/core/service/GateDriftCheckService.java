package com.choruskube.core.service;

import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.exception.GateRecheckUnavailableException;
import com.choruskube.core.exception.GitHubApiException;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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

        /** Plain text, for the gate's result note and the approve response. */
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

    private final NodeExecutionRepository execRepo;
    private final NodeExecutionRepoBaseRepository baseRepo;
    private final GitRepoRepository gitRepoRepo;
    private final RunPullRequestRepository prRepo;
    private final GitHubCredentialResolver credentialResolver;
    private final GitHubAppService gitHubAppService;
    private final ObjectMapper objectMapper;

    public GateDriftCheckService(
            NodeExecutionRepository execRepo,
            NodeExecutionRepoBaseRepository baseRepo,
            GitRepoRepository gitRepoRepo,
            RunPullRequestRepository prRepo,
            GitHubCredentialResolver credentialResolver,
            GitHubAppService gitHubAppService,
            ObjectMapper objectMapper) {
        this.execRepo = execRepo;
        this.baseRepo = baseRepo;
        this.gitRepoRepo = gitRepoRepo;
        this.prRepo = prRepo;
        this.credentialResolver = credentialResolver;
        this.gitHubAppService = gitHubAppService;
        this.objectMapper = objectMapper;
    }

    public DriftCheck check(UUID runId, JsonNode snapshot) {
        List<UUID> repoIds = repoIdsOf(snapshot);
        NodeExecution predecessor = latestCompletedNonHuman(runId, snapshot);
        if (repoIds.isEmpty() || predecessor == null) {
            // Nothing was built against any repo yet, so there is no tested state to protect.
            return new DriftCheck(predecessor, List.of());
        }
        Map<UUID, NodeExecutionRepoBase> bases = baseRepo.findByNodeExecutionId(predecessor.getId()).stream()
                .collect(Collectors.toMap(NodeExecutionRepoBase::getGitRepoId, Function.identity(), (a, b) -> a));
        String token = null;
        List<RepoDrift> results = new ArrayList<>();
        for (UUID repoId : repoIds) {
            GitRepo repo = gitRepoRepo.findById(repoId).orElse(null);
            if (repo == null) {
                // Removed from the project: there is no default branch to compare, and
                // merge-on-approval refuses its pull requests on its own.
                log.warn("Run {} cloned repo {}, which is no longer registered; skipping its re-check", runId, repoId);
                continue;
            }
            String ownerRepo = RepoNameUtil.deriveOwnerRepoName(repo.getUrl());
            String branch = repo.getDefaultBranch();
            NodeExecutionRepoBase base = bases.get(repoId);
            if (base == null) {
                results.add(new RepoDrift(repoId, ownerRepo, branch, Reason.NO_RECORD, null, 0, List.of()));
            } else if ("conflict".equals(base.getOutcome())) {
                results.add(new RepoDrift(repoId, ownerRepo, branch, Reason.CONFLICT, null, 0, files(base)));
            } else if ("unavailable".equals(base.getOutcome()) || base.getBaseSha() == null) {
                results.add(new RepoDrift(repoId, ownerRepo, branch, Reason.UNAVAILABLE, null, 0, List.of()));
            } else {
                if (token == null) {
                    token = token(runId, ownerRepo);
                }
                results.add(compare(token, repoId, ownerRepo, branch, base.getBaseSha()));
            }
        }
        return new DriftCheck(predecessor, results);
    }

    /** The {@code human_guidance.md} the routed-to node reads when an approval is held. */
    public String guidance(UUID runId, DriftCheck check, int round) {
        StringBuilder sb = new StringBuilder("# Re-check against current main\n\n")
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

    private RepoDrift compare(String token, UUID repoId, String ownerRepo, String branch, String baseSha) {
        try {
            int ahead = gitHubAppService.compareCommits(token, ownerRepo, baseSha, branch);
            Reason reason = ahead > 0 ? Reason.MAIN_MOVED : Reason.CURRENT;
            return new RepoDrift(repoId, ownerRepo, branch, reason, baseSha, ahead, List.of());
        } catch (GitHubApiException e) {
            if (e.getStatus() == 404) {
                return new RepoDrift(repoId, ownerRepo, branch, Reason.BASE_GONE, baseSha, 0, List.of());
            }
            throw new GateRecheckUnavailableException(ownerRepo, e);
        } catch (RuntimeException e) {
            throw new GateRecheckUnavailableException(ownerRepo, e);
        }
    }

    private String token(UUID runId, String ownerRepo) {
        try {
            return credentialResolver.getTokenForRun(runId);
        } catch (RuntimeException e) {
            throw new GateRecheckUnavailableException(ownerRepo, e);
        }
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

    private NodeExecution latestCompletedNonHuman(UUID runId, JsonNode snapshot) {
        Set<UUID> humanNodes = new HashSet<>();
        for (JsonNode node : snapshot.path("nodes")) {
            if ("human".equalsIgnoreCase(node.path("executor_type").asText())) {
                humanNodes.add(UUID.fromString(node.path("template_node_id").asText()));
            }
        }
        return execRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed).stream()
                .filter(e -> e.getCompletedAt() != null && !humanNodes.contains(e.getTemplateNodeId()))
                .max(Comparator.comparing(NodeExecution::getCompletedAt))
                .orElse(null);
    }

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
