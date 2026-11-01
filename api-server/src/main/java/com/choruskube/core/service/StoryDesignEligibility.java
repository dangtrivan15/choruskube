package com.choruskube.core.service;

import com.choruskube.core.model.Story;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Whether the Autopilot may start a Story's first design now.
 *
 * <p>A blocking Story is satisfied once its spec is approved, not only once its code lands: the
 * dependent design builds on the blocker's spec, and a story conflict catches any drift from it.
 * Waiting for the blocker to be done would serialise dependent Stories end to end.
 */
final class StoryDesignEligibility {

    private StoryDesignEligibility() {}

    /**
     * A failed or cancelled first design leaves the Story without an outcome, like a Task left
     * {@code in_progress} by a finished run. Restarting it would undo a human's cancel on every
     * tick, and retry a broken design until the failure breaker trips.
     */
    private static final Set<WorkflowRunStatus> HOLDING =
            EnumSet.of(WorkflowRunStatus.failed, WorkflowRunStatus.cancelled);

    /**
     * @param versions approved spec version per Story; must cover every Story that directly blocks
     *     {@code s} or its Epic, wherever that Story lives
     * @param openDesign Stories with an open Story Design run
     * @param latestDesignRuns each Story's newest non-deleted design run
     */
    static boolean isDesignable(
            Story s,
            EpicReadinessAssembler.EpicCandidates c,
            EpicReadinessAssembler.Assembly a,
            Map<UUID, Integer> versions,
            Set<UUID> openDesign,
            Map<UUID, WorkflowRun> latestDesignRuns) {
        if (!awaitsFirstDesign(s, c, versions, openDesign)
                || holdingRun(s, c, versions, openDesign, latestDesignRuns).isPresent()) {
            return false;
        }
        for (WorkItemDependency edge : a.edges()) {
            if (blocksStoryOrItsEpic(edge, s) && !satisfied(edge, a, versions)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The finished run that keeps the Autopilot from designing {@code s}: its newest design run,
     * when that run failed or was cancelled. A human releases it by designing the Story by hand or
     * by deleting the run.
     */
    static Optional<WorkflowRun> holdingRun(
            Story s,
            EpicReadinessAssembler.EpicCandidates c,
            Map<UUID, Integer> versions,
            Set<UUID> openDesign,
            Map<UUID, WorkflowRun> latestDesignRuns) {
        if (!awaitsFirstDesign(s, c, versions, openDesign)) {
            return Optional.empty();
        }
        return Optional.ofNullable(latestDesignRuns.get(s.getId())).filter(run -> HOLDING.contains(run.getStatus()));
    }

    /** An open run, completed-but-unrecorded included, is design in progress and never a hold. */
    private static boolean awaitsFirstDesign(
            Story s, EpicReadinessAssembler.EpicCandidates c, Map<UUID, Integer> versions, Set<UUID> openDesign) {
        return !versions.containsKey(s.getId())
                && !openDesign.contains(s.getId())
                && s.getStage() != WorkItemStatus.rolled_out
                && !isDone(c.statusById().get(s.getId()));
    }

    /** Direct edges only: a Story's own Tasks, and its blockers' blockers, gate its delivery, not its design. */
    private static boolean blocksStoryOrItsEpic(WorkItemDependency edge, Story s) {
        return (edge.getBlockedItemType() == BlockableItemType.story
                        && s.getId().equals(edge.getBlockedItemId()))
                || (edge.getBlockedItemType() == BlockableItemType.epic
                        && s.getEpicId().equals(edge.getBlockedItemId()));
    }

    /**
     * Read from the assembly, not the candidates: a blocker in another Epic has a status only there,
     * and missing it would hold the Story back forever.
     */
    private static boolean satisfied(
            WorkItemDependency edge, EpicReadinessAssembler.Assembly a, Map<UUID, Integer> versions) {
        if (isDone(a.effectiveStatusById().get(edge.getBlockingItemId()))) {
            return true;
        }
        return edge.getBlockingItemType() == BlockableItemType.story && versions.containsKey(edge.getBlockingItemId());
    }

    private static boolean isDone(String status) {
        return WorkItemStatus.done.name().equals(status)
                || WorkItemStatus.rolled_out.name().equals(status);
    }
}
