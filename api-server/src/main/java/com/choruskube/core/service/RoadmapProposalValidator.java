package com.choruskube.core.service;

import com.choruskube.core.dto.CandidateDependency;
import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.CandidateMilestone;
import com.choruskube.core.dto.CandidateStoryProposal;
import com.choruskube.core.dto.CandidateTaskProposal;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.RoadmapMaterializeMode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Decides whether a roadmap proposal document is acceptable — the single validator behind
 * {@code propose-roadmap} (agent strictness), the Final Approval / Roadmap Provisioner approve
 * path (gate strictness), and a person's JSON import ({@link #validateImport}). Never throws for
 * content problems: every violation is collected and returned path-prefixed, so a caller sees the
 * whole list at once rather than fixing one error at a time.
 *
 * <p>It never reveals whether an out-of-project id exists — an anchor that is missing and one that
 * belongs to another project produce the identical "not found in ..." message, via {@link
 * RoadmapAnchorLookup}.
 */
@Service
public class RoadmapProposalValidator {

    public enum Strictness {
        AGENT,
        GATE
    }

    public record Summary(int newEpics, int newStories, int newTasks, int existingItems, int dependencies) {}

    /** Whether a candidate-local key resolves to an item inside the run's own Epic scope. */
    private record KeyScope(boolean inScope) {}

    private final RoadmapAnchorLookup anchorLookup;
    private final InternalRunService internalRunService;
    private final Validator beanValidator;

    public RoadmapProposalValidator(
            RoadmapAnchorLookup anchorLookup, InternalRunService internalRunService, Validator beanValidator) {
        this.anchorLookup = anchorLookup;
        this.internalRunService = internalRunService;
        this.beanValidator = beanValidator;
    }

    /**
     * What a document is checked against. Every rule that differs between a run's gate and a
     * person's import is a field here, so both callers share one rule walk.
     *
     * @param projectError reported once when anchors need a project the run cannot resolve
     * @param triggeringEpicId non-null only for a task-triggered extension; enables its scope rules
     * @param projectRepos non-null only when new Tasks must name a repo of the project
     * @param anchorsForbidden the error to report for any {@code existingId}, or null when allowed
     * @param milestonesForbidden the error to report for any milestone, or null when allowed
     * @param projectLabel how anchor errors name the project
     */
    private record Scope(
            UUID projectId,
            String projectError,
            UUID triggeringEpicId,
            List<GitRepo> projectRepos,
            String anchorsForbidden,
            String milestonesForbidden,
            String projectLabel) {}

    public List<String> validate(
            UUID runId, RoadmapCandidatesDocument doc, RoadmapMaterializeMode mode, Strictness strictness) {
        if (doc == null) {
            return new ArrayList<>();
        }
        return validate(doc, strictness, runScope(runId, doc, mode));
    }

    /**
     * A person importing a document into {@code softwareProjectId}, which the caller has already
     * authorized. Anchors may point anywhere in that project and milestones are allowed. Always
     * agent strictness: nothing reviews an import afterwards, so every edge must resolve up front.
     */
    public List<String> validateImport(UUID softwareProjectId, RoadmapCandidatesDocument doc) {
        if (doc == null) {
            return new ArrayList<>(List.of("document: nothing to import"));
        }
        List<String> errors = validate(
                doc,
                Strictness.AGENT,
                new Scope(softwareProjectId, null, null, null, null, null, "this software project"));
        if (orEmpty(doc.epics()).isEmpty() && orEmpty(doc.milestones()).isEmpty()) {
            errors.add("document: nothing to import — add at least one epic or milestone");
        }
        return errors;
    }

    /**
     * Anchors resolve against the run's software project — only bother resolving it when an anchor
     * might need it, so a Provisioner document with no anchors validates even for a run with no
     * project at all (its mode never anchors).
     */
    private Scope runScope(UUID runId, RoadmapCandidatesDocument doc, RoadmapMaterializeMode mode) {
        boolean extension = mode == RoadmapMaterializeMode.roadmap_extension;
        UUID projectId = null;
        String projectError = null;
        if (hasAnyAnchor(orEmpty(doc.epics())) || extension) {
            try {
                projectId = internalRunService.resolveSoftwareProjectId(runId);
            } catch (NotFoundException e) {
                projectError = "run has no software project; a roadmap proposal cannot be applied";
            }
        }

        UUID triggeringEpicId =
                extension ? internalRunService.resolveTriggeringEpicId(runId).orElse(null) : null;

        // (g) needs the project's repos; null when unresolvable, which skips the rule — the
        // project-resolution failure above already reports why nothing can be applied.
        List<GitRepo> projectRepos = null;
        if (extension && projectId != null) {
            try {
                projectRepos = internalRunService.resolveRepos(projectId);
            } catch (NotFoundException e) {
                projectRepos = null;
            }
        }

        // roadmap_candidates creates wholly new Epic trees only — an existingId anchor is the one
        // mechanism for extending what already exists, and that mechanism belongs to
        // roadmap_extension alone. Without this check a candidates document could silently attach
        // new children under any Epic in the project, the exact write roadmap_extension's own
        // triggering-Epic scope check exists to prevent.
        String anchorsForbidden = mode == RoadmapMaterializeMode.roadmap_candidates
                ? "existingId anchors are not allowed in roadmap_candidates mode"
                : null;
        // (e), first sub-bullet: milestones are forbidden in extension mode, unconditionally.
        String milestonesForbidden = extension ? "milestones are not allowed in a roadmap extension" : null;

        return new Scope(
                projectId,
                projectError,
                triggeringEpicId,
                projectRepos,
                anchorsForbidden,
                milestonesForbidden,
                "this run's software project");
    }

    private List<String> validate(RoadmapCandidatesDocument doc, Strictness strictness, Scope scope) {
        List<String> errors = new ArrayList<>();

        // (a) Bean violations, plus null list-element checks bean validation does not perform on
        // its own (cascading @Valid skips a null element rather than reporting it).
        for (ConstraintViolation<RoadmapCandidatesDocument> v : beanValidator.validate(doc)) {
            errors.add(v.getPropertyPath().toString() + ": " + v.getMessage());
        }

        List<CandidateMilestone> milestones = orEmpty(doc.milestones());
        List<CandidateEpicProposal> epics = orEmpty(doc.epics());
        List<CandidateDependency> dependencies = orEmpty(doc.dependencies());

        // (b) Keys unique across milestones, epics, stories and tasks — anchors included.
        Set<String> seenKeys = new HashSet<>();
        Map<String, Set<UUID>> seenExistingIds = new HashMap<>();
        Set<String> milestoneKeys = new HashSet<>();

        for (int m = 0; m < milestones.size(); m++) {
            CandidateMilestone milestone = milestones.get(m);
            String path = "milestones[" + m + "]";
            if (milestone == null) {
                errors.add(path + ": entry is null");
                continue;
            }
            registerKey(milestone.key(), path, seenKeys, errors);
            if (milestone.key() != null) {
                milestoneKeys.add(milestone.key());
            }
        }

        // (c) Anchors resolve against the scope's project.
        if (scope.projectError() != null) {
            errors.add(scope.projectError());
        }
        UUID projectId = scope.projectId();
        UUID triggeringEpicId = scope.triggeringEpicId();
        List<GitRepo> projectRepos = scope.projectRepos();

        if (scope.milestonesForbidden() != null) {
            boolean anyEpicMilestone = epics.stream().anyMatch(e -> e != null && e.milestone() != null);
            if (!milestones.isEmpty() || anyEpicMilestone) {
                errors.add(scope.milestonesForbidden());
            }
        }

        if (scope.anchorsForbidden() != null && hasAnyAnchor(epics)) {
            errors.add(scope.anchorsForbidden());
        }

        Map<String, KeyScope> keyScopes = new HashMap<>();

        for (int i = 0; i < epics.size(); i++) {
            CandidateEpicProposal epic = epics.get(i);
            String epicPath = "epics[" + i + "]";
            if (epic == null) {
                errors.add(epicPath + ": entry is null");
                continue;
            }
            registerKey(epic.key(), epicPath, seenKeys, errors);

            boolean isAnchor = epic.existingId() != null;
            boolean isTriggeringEpic =
                    isAnchor && triggeringEpicId != null && triggeringEpicId.equals(epic.existingId());
            boolean epicInScope;

            if (isAnchor) {
                checkDuplicateExisting("epic", epic.existingId(), epicPath, seenExistingIds, errors);
                if (projectId != null) {
                    Optional<Epic> found = anchorLookup.epic(epic.existingId(), projectId);
                    if (found.isEmpty()) {
                        errors.add(epicPath + ": existing epic " + epic.existingId() + " not found in "
                                + scope.projectLabel());
                    }
                }
                epicInScope = isTriggeringEpic;
            } else {
                epicInScope = true;
            }
            if (epic.key() != null) {
                keyScopes.put(epic.key(), new KeyScope(epicInScope));
            }

            List<CandidateStoryProposal> stories = orEmpty(epic.stories());
            if (!isAnchor && stories.isEmpty()) {
                errors.add(epicPath + ": a new epic needs at least one story");
            }

            boolean hasNewDescendant = false;

            for (int j = 0; j < stories.size(); j++) {
                CandidateStoryProposal story = stories.get(j);
                String storyPath = epicPath + ".stories[" + j + "]";
                if (story == null) {
                    errors.add(storyPath + ": entry is null");
                    continue;
                }
                registerKey(story.key(), storyPath, seenKeys, errors);

                boolean storyIsAnchor = story.existingId() != null;
                boolean storyInScope;

                if (storyIsAnchor) {
                    if (!isAnchor) {
                        errors.add(storyPath + ": an existing story can only be listed under its existing epic");
                    } else {
                        checkDuplicateExisting("story", story.existingId(), storyPath, seenExistingIds, errors);
                        if (projectId != null) {
                            Optional<Story> found = anchorLookup.story(story.existingId(), projectId);
                            if (found.isEmpty()) {
                                errors.add(storyPath + ": existing story " + story.existingId() + " not found in "
                                        + scope.projectLabel());
                            } else if (!found.get().getEpicId().equals(epic.existingId())) {
                                errors.add(storyPath + ": story " + story.existingId() + " does not belong to epic "
                                        + epic.existingId());
                            }
                        }
                    }
                    storyInScope = epicInScope;
                } else {
                    hasNewDescendant = true;
                    storyInScope = true;
                }
                if (story.key() != null) {
                    keyScopes.put(story.key(), new KeyScope(storyInScope));
                }

                List<CandidateTaskProposal> tasks = orEmpty(story.tasks());
                if (!storyIsAnchor && tasks.isEmpty()) {
                    errors.add(storyPath + ": a new story needs at least one task");
                }

                for (int k = 0; k < tasks.size(); k++) {
                    CandidateTaskProposal task = tasks.get(k);
                    String taskPath = storyPath + ".tasks[" + k + "]";
                    if (task == null) {
                        errors.add(taskPath + ": entry is null");
                        continue;
                    }
                    registerKey(task.key(), taskPath, seenKeys, errors);

                    boolean taskIsAnchor = task.existingId() != null;
                    boolean taskInScope;

                    if (taskIsAnchor) {
                        if (!storyIsAnchor) {
                            errors.add(taskPath + ": an existing task can only be listed under its existing story");
                        } else {
                            checkDuplicateExisting("task", task.existingId(), taskPath, seenExistingIds, errors);
                            if (projectId != null) {
                                Optional<Task> found = anchorLookup.task(task.existingId(), projectId);
                                if (found.isEmpty()) {
                                    errors.add(taskPath + ": existing task " + task.existingId() + " not found in "
                                            + scope.projectLabel());
                                } else if (!found.get().getStoryId().equals(story.existingId())) {
                                    errors.add(taskPath + ": task " + task.existingId() + " does not belong to story "
                                            + story.existingId());
                                }
                            }
                        }
                        taskInScope = storyInScope;
                    } else {
                        hasNewDescendant = true;
                        taskInScope = true;
                        if (projectRepos != null) {
                            validateRepoId(task, taskPath, projectRepos, errors);
                        }
                    }
                    if (task.key() != null) {
                        keyScopes.put(task.key(), new KeyScope(taskInScope));
                    }
                }
            }

            // (e): a task-triggered extension may only add new work under its own Epic — an anchor
            // to any other existing Epic may still appear (to expose an ancestor chain's key for a
            // dependency endpoint) but must carry no new descendant.
            if (triggeringEpicId != null && isAnchor && !isTriggeringEpic && hasNewDescendant) {
                errors.add(epicPath + ": new items may only be added under this run's epic " + triggeringEpicId);
            }
        }

        // (f) AGENT-only dependency checks, and (e)'s scope check for both strictness levels.
        Map<String, Set<String>> acceptedEdges = new HashMap<>();
        Set<String> seenPairs = new HashSet<>();
        for (int n = 0; n < dependencies.size(); n++) {
            CandidateDependency dep = dependencies.get(n);
            String depPath = "dependencies[" + n + "]";
            if (dep == null) {
                errors.add(depPath + ": entry is null");
                continue;
            }
            String blocking = dep.blocking();
            String blocked = dep.blocked();
            boolean blockingIsMilestone = blocking != null && milestoneKeys.contains(blocking);
            boolean blockedIsMilestone = blocked != null && milestoneKeys.contains(blocked);
            boolean blockingDeclared = blockingIsMilestone || (blocking != null && keyScopes.containsKey(blocking));
            boolean blockedDeclared = blockedIsMilestone || (blocked != null && keyScopes.containsKey(blocked));

            if (strictness == Strictness.AGENT) {
                boolean bad = false;
                if (blocking == null || blocking.isBlank() || !blockingDeclared) {
                    errors.add(depPath + ": unknown key '" + blocking + "'");
                    bad = true;
                }
                if (blocked == null || blocked.isBlank() || !blockedDeclared) {
                    errors.add(depPath + ": unknown key '" + blocked + "'");
                    bad = true;
                }
                if (!bad && (blockingIsMilestone || blockedIsMilestone)) {
                    errors.add(depPath + ": milestones cannot be dependency endpoints");
                    bad = true;
                }
                if (!bad && blocking.equals(blocked)) {
                    errors.add(depPath + ": an item cannot block itself");
                    bad = true;
                }
                if (!bad && !seenPairs.add(blocking + "\u0000" + blocked)) {
                    errors.add(depPath + ": duplicate dependency");
                    bad = true;
                }
                if (!bad && CandidateDependencyCycles.wouldCreateCycle(acceptedEdges, blocking, blocked)) {
                    errors.add(depPath + ": would create a cycle");
                    bad = true;
                }
                if (!bad) {
                    acceptedEdges
                            .computeIfAbsent(blocking, x -> new HashSet<>())
                            .add(blocked);
                }
            }

            // An edge naming a key the document doesn't declare is left for the materializer to
            // skip and record at gate strictness, and is reported as an unknown key above at agent
            // strictness — either way it is not scope-checked here.
            if (triggeringEpicId != null
                    && blocking != null
                    && blocked != null
                    && keyScopes.containsKey(blocking)
                    && keyScopes.containsKey(blocked)) {
                boolean blockingInScope = keyScopes.get(blocking).inScope();
                boolean blockedInScope = keyScopes.get(blocked).inScope();
                if (!blockingInScope && !blockedInScope) {
                    errors.add(depPath + ": at least one side must be an item in this run's epic");
                }
            }
        }

        return errors;
    }

    public Summary summarize(RoadmapCandidatesDocument doc) {
        if (doc == null) {
            return new Summary(0, 0, 0, 0, 0);
        }
        int newEpics = 0;
        int newStories = 0;
        int newTasks = 0;
        int existingItems = 0;
        Set<String> declaredKeys = new HashSet<>();

        for (CandidateMilestone m : orEmpty(doc.milestones())) {
            if (m != null && m.key() != null) {
                declaredKeys.add(m.key());
            }
        }
        for (CandidateEpicProposal epic : orEmpty(doc.epics())) {
            if (epic == null) {
                continue;
            }
            if (epic.key() != null) {
                declaredKeys.add(epic.key());
            }
            if (epic.existingId() != null) {
                existingItems++;
            } else {
                newEpics++;
            }
            for (CandidateStoryProposal story : orEmpty(epic.stories())) {
                if (story == null) {
                    continue;
                }
                if (story.key() != null) {
                    declaredKeys.add(story.key());
                }
                if (story.existingId() != null) {
                    existingItems++;
                } else {
                    newStories++;
                }
                for (CandidateTaskProposal task : orEmpty(story.tasks())) {
                    if (task == null) {
                        continue;
                    }
                    if (task.key() != null) {
                        declaredKeys.add(task.key());
                    }
                    if (task.existingId() != null) {
                        existingItems++;
                    } else {
                        newTasks++;
                    }
                }
            }
        }

        int dependencies = 0;
        for (CandidateDependency dep : orEmpty(doc.dependencies())) {
            if (dep != null
                    && dep.blocking() != null
                    && dep.blocked() != null
                    && declaredKeys.contains(dep.blocking())
                    && declaredKeys.contains(dep.blocked())) {
                dependencies++;
            }
        }

        return new Summary(newEpics, newStories, newTasks, existingItems, dependencies);
    }

    private void validateRepoId(CandidateTaskProposal task, String taskPath, List<GitRepo> repos, List<String> errors) {
        if (task.repoId() != null) {
            if (repos.stream().noneMatch(r -> r.getId().equals(task.repoId()))) {
                errors.add(taskPath + ": repoId " + task.repoId() + " is not part of this run's software project");
            }
            return;
        }
        if (repos.size() > 1) {
            errors.add(taskPath
                    + ": a repoId is required for a new task when the run's project spans more than one repository");
        }
    }

    private void registerKey(String key, String path, Set<String> seenKeys, List<String> errors) {
        if (key == null) {
            return;
        }
        if (!seenKeys.add(key)) {
            errors.add(path + ": duplicate key '" + key + "'");
        }
    }

    private void checkDuplicateExisting(
            String type, UUID id, String path, Map<String, Set<UUID>> seenExistingIds, List<String> errors) {
        Set<UUID> ids = seenExistingIds.computeIfAbsent(type, t -> new HashSet<>());
        if (!ids.add(id)) {
            errors.add(path + ": existing " + type + " " + id + " is listed more than once");
        }
    }

    private boolean hasAnyAnchor(List<CandidateEpicProposal> epics) {
        for (CandidateEpicProposal epic : epics) {
            if (epic == null) {
                continue;
            }
            if (epic.existingId() != null) {
                return true;
            }
            for (CandidateStoryProposal story : orEmpty(epic.stories())) {
                if (story == null) {
                    continue;
                }
                if (story.existingId() != null) {
                    return true;
                }
                for (CandidateTaskProposal task : orEmpty(story.tasks())) {
                    if (task != null && task.existingId() != null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}
