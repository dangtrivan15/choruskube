package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.WorkItemStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoryDesignEligibilityTest {

    private final Epic epic = epic();
    private final Story story = story(epic);

    /** What {@code loadEpicCandidates} reads: this Epic's own items. */
    private final Map<UUID, String> candidateStatuses = new HashMap<>();

    /** What only {@code assemble} resolves: a direct blocker that lives in another Epic. */
    private final Map<UUID, String> externalStatuses = new HashMap<>();

    private final List<WorkItemDependency> edges = new ArrayList<>();
    private final Map<UUID, Integer> versions = new HashMap<>();
    private final Set<UUID> openDesign = new HashSet<>();

    @Test
    void undesignedStoryWithNoTasks_isDesignable() {
        assertThat(designable()).isTrue();
    }

    @Test
    void approvedVersion_isNotDesignable() {
        versions.put(story.getId(), 1);

        assertThat(designable()).isFalse();
    }

    @Test
    void openDesignRun_isNotDesignable() {
        openDesign.add(story.getId());

        assertThat(designable()).isFalse();
    }

    @Test
    void doneStory_isNotDesignable() {
        candidateStatuses.put(story.getId(), WorkItemStatus.done.name());

        assertThat(designable()).isFalse();
    }

    @Test
    void rolledOutStory_isNotDesignable() {
        story.setStage(WorkItemStatus.rolled_out);

        assertThat(designable()).isFalse();
    }

    @Test
    void blockingStoryWithApprovedSpec_isSatisfiedBeforeItsCodeLands() {
        UUID blocker = inEpicStory(WorkItemStatus.in_progress);
        blocks(blocker, BlockableItemType.story, story.getId(), BlockableItemType.story);
        versions.put(blocker, 2);

        assertThat(designable()).isTrue();
    }

    @Test
    void blockingStoryWithoutSpec_blocksUntilDone() {
        UUID blocker = inEpicStory(WorkItemStatus.in_progress);
        blocks(blocker, BlockableItemType.story, story.getId(), BlockableItemType.story);

        assertThat(designable()).isFalse();

        candidateStatuses.put(blocker, WorkItemStatus.done.name());
        assertThat(designable()).isTrue();
    }

    @Test
    void blockingTask_isSatisfiedOnlyWhenDone_evenWithASpecOnItsId() {
        UUID blocker = UUID.randomUUID();
        candidateStatuses.put(blocker, WorkItemStatus.in_progress.name());
        blocks(blocker, BlockableItemType.task, story.getId(), BlockableItemType.story);
        versions.put(blocker, 1);

        assertThat(designable()).isFalse();

        candidateStatuses.put(blocker, WorkItemStatus.done.name());
        assertThat(designable()).isTrue();
    }

    @Test
    void blockerOfTheStorysEpic_holdsTheStoryBack() {
        UUID blockingEpic = UUID.randomUUID();
        externalStatuses.put(blockingEpic, WorkItemStatus.in_progress.name());
        blocks(blockingEpic, BlockableItemType.epic, epic.getId(), BlockableItemType.epic);

        assertThat(designable()).isFalse();

        externalStatuses.put(blockingEpic, WorkItemStatus.done.name());
        assertThat(designable()).isTrue();
    }

    @Test
    void crossEpicBlockingStory_isJudgedByTheStatusTheReadinessWalkRead() {
        UUID external = UUID.randomUUID();
        externalStatuses.put(external, WorkItemStatus.done.name());
        blocks(external, BlockableItemType.story, story.getId(), BlockableItemType.story);

        assertThat(designable()).isTrue();
    }

    @Test
    void blockerOfOneOfItsTasks_doesNotHoldTheDesignBack() {
        UUID ownTask = UUID.randomUUID();
        UUID blocker = UUID.randomUUID();
        candidateStatuses.put(ownTask, WorkItemStatus.backlog.name());
        candidateStatuses.put(blocker, WorkItemStatus.backlog.name());
        blocks(blocker, BlockableItemType.task, ownTask, BlockableItemType.task);

        assertThat(designable()).isTrue();
    }

    @Test
    void anEdgeTheStoryBlocks_isNotABlockerOfIt() {
        UUID downstream = inEpicStory(WorkItemStatus.backlog);
        blocks(story.getId(), BlockableItemType.story, downstream, BlockableItemType.story);

        assertThat(designable()).isTrue();
    }

    private boolean designable() {
        candidateStatuses.putIfAbsent(story.getId(), WorkItemStatus.backlog.name());
        Map<UUID, String> effective = new HashMap<>(candidateStatuses);
        effective.putAll(externalStatuses);
        EpicReadinessAssembler.EpicCandidates candidates = new EpicReadinessAssembler.EpicCandidates(
                List.of(story),
                Map.of(story.getId(), List.of()),
                Set.copyOf(candidateStatuses.keySet()),
                Map.copyOf(candidateStatuses),
                Map.of(story.getId(), epic.getId()));
        EpicReadinessAssembler.Assembly assembly =
                new EpicReadinessAssembler.Assembly(Map.of(), List.of(), List.of(), edges, Map.of(), effective);
        return StoryDesignEligibility.isDesignable(story, candidates, assembly, versions, openDesign);
    }

    private UUID inEpicStory(WorkItemStatus status) {
        UUID id = UUID.randomUUID();
        candidateStatuses.put(id, status.name());
        return id;
    }

    private void blocks(
            UUID blockingId, BlockableItemType blockingType, UUID blockedId, BlockableItemType blockedType) {
        WorkItemDependency edge = new WorkItemDependency();
        edge.setId(UUID.randomUUID());
        edge.setBlockingItemId(blockingId);
        edge.setBlockingItemType(blockingType);
        edge.setBlockedItemId(blockedId);
        edge.setBlockedItemType(blockedType);
        edges.add(edge);
    }

    private static Epic epic() {
        Epic e = new Epic();
        e.setId(UUID.randomUUID());
        e.setStage(WorkItemStatus.backlog);
        return e;
    }

    private static Story story(Epic parent) {
        Story s = new Story();
        s.setId(UUID.randomUUID());
        s.setEpicId(parent.getId());
        s.setTitle("Story");
        s.setStage(WorkItemStatus.backlog);
        return s;
    }
}
