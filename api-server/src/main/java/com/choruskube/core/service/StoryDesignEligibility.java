package com.choruskube.core.service;

import com.choruskube.core.model.Story;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.WorkItemStatus;
import java.util.Map;
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
     * @param versions approved spec version per Story; must cover every Story that directly blocks
     *     {@code s} or its Epic, wherever that Story lives
     * @param openDesign Stories with an open Story Design run
     */
    static boolean isDesignable(
            Story s,
            EpicReadinessAssembler.EpicCandidates c,
            EpicReadinessAssembler.Assembly a,
            Map<UUID, Integer> versions,
            Set<UUID> openDesign) {
        if (versions.containsKey(s.getId()) || openDesign.contains(s.getId())) {
            return false;
        }
        if (s.getStage() == WorkItemStatus.rolled_out || isDone(c.statusById().get(s.getId()))) {
            return false;
        }
        for (WorkItemDependency edge : a.edges()) {
            if (blocksStoryOrItsEpic(edge, s) && !satisfied(edge, a, versions)) {
                return false;
            }
        }
        return true;
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
