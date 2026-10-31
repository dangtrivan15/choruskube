package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.Priority;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TaskOrderingStrategyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void higherEpicPriorityComesFirst() {
        Epic lowEpic = epic(Priority.low, null, T0);
        Epic highEpic = epic(Priority.high, null, T0.plusSeconds(1));
        Story lowStory = story(lowEpic, Priority.medium, null, T0);
        Story highStory = story(highEpic, Priority.medium, null, T0);
        Task fromLow = task(lowStory, Priority.medium, T0);
        Task fromHigh = task(highStory, Priority.medium, T0);

        List<Task> sorted = sort(List.of(fromLow, fromHigh), List.of(lowEpic, highEpic), List.of(lowStory, highStory));

        assertThat(sorted).containsExactly(fromHigh, fromLow);
    }

    @Test
    void withinSameEpic_higherStoryPriorityComesFirst() {
        Epic e = epic(Priority.medium, null, T0);
        Story lowStory = story(e, Priority.low, null, T0);
        Story highStory = story(e, Priority.high, null, T0.plusSeconds(1));
        Task fromLow = task(lowStory, Priority.medium, T0);
        Task fromHigh = task(highStory, Priority.medium, T0.plusSeconds(1));

        List<Task> sorted = sort(List.of(fromLow, fromHigh), List.of(e), List.of(lowStory, highStory));

        assertThat(sorted).containsExactly(fromHigh, fromLow);
    }

    @Test
    void withinSameStory_higherTaskPriorityComesFirst() {
        Epic e = epic(Priority.medium, null, T0);
        Story s = story(e, Priority.medium, null, T0);
        Task olderLow = task(s, Priority.low, T0);
        Task newerHigh = task(s, Priority.high, T0.plusSeconds(1));

        List<Task> sorted = sort(List.of(olderLow, newerHigh), List.of(e), List.of(s));

        assertThat(sorted).containsExactly(newerHigh, olderLow);
    }

    @Test
    void equalPriorityEpics_doNotInterleave_theOlderEpicIsFinishedFirst() {
        // Tasks created alternately across two equal Epics. Ranking by Task age alone would
        // alternate between them; the Epic has to be chosen before any Task key is consulted.
        Epic older = epic(Priority.high, null, T0);
        Epic newer = epic(Priority.high, null, T0.plusSeconds(10));
        Story olderStory = story(older, Priority.high, null, T0);
        Story newerStory = story(newer, Priority.high, null, T0.plusSeconds(10));
        Task newer1 = task(newerStory, Priority.medium, T0.plusSeconds(11));
        Task older1 = task(olderStory, Priority.medium, T0.plusSeconds(12));
        Task newer2 = task(newerStory, Priority.medium, T0.plusSeconds(13));
        Task older2 = task(olderStory, Priority.medium, T0.plusSeconds(14));

        List<Task> sorted =
                sort(List.of(newer1, older1, newer2, older2), List.of(older, newer), List.of(olderStory, newerStory));

        assertThat(sorted).containsExactly(older1, older2, newer1, newer2);
    }

    @Test
    void aHigherPriorityStoryDoesNotPullItsTaskAheadOfAnEarlierEpic() {
        Epic first = epic(Priority.high, null, T0);
        Epic second = epic(Priority.high, null, T0.plusSeconds(1));
        Story firstEpicsMediumStory = story(first, Priority.medium, null, T0);
        Story secondEpicsHighStory = story(second, Priority.high, LocalDate.of(2026, 1, 2), T0);
        Task fromFirst = task(firstEpicsMediumStory, Priority.low, T0.plusSeconds(5));
        Task fromSecond = task(secondEpicsHighStory, Priority.high, T0);

        List<Task> sorted = sort(
                List.of(fromSecond, fromFirst),
                List.of(first, second),
                List.of(firstEpicsMediumStory, secondEpicsHighStory));

        assertThat(sorted).containsExactly(fromFirst, fromSecond);
    }

    @Test
    void amongEqualPriorityEpics_aStartedEpicComesBeforeAnOlderUnstartedOne() {
        Epic olderUntouched = epic(Priority.high, null, T0);
        Epic newerStarted = epic(Priority.high, null, T0.plusSeconds(10));
        Story untouchedStory = story(olderUntouched, Priority.medium, null, T0);
        Story startedStory = story(newerStarted, Priority.medium, null, T0.plusSeconds(10));
        Task fromUntouched = task(untouchedStory, Priority.medium, T0);
        Task fromStarted = task(startedStory, Priority.medium, T0.plusSeconds(10));

        List<Task> sorted = sort(
                List.of(fromUntouched, fromStarted),
                List.of(olderUntouched, newerStarted),
                List.of(untouchedStory, startedStory),
                Set.of(newerStarted.getId(), startedStory.getId()));

        assertThat(sorted).containsExactly(fromStarted, fromUntouched);
    }

    @Test
    void withinAnEpic_aStartedStoryComesBeforeAnOlderUnstartedOne() {
        Epic e = epic(Priority.medium, null, T0);
        Story olderUntouched = story(e, Priority.medium, null, T0);
        Story newerStarted = story(e, Priority.medium, null, T0.plusSeconds(10));
        Task fromUntouched = task(olderUntouched, Priority.medium, T0);
        Task fromStarted = task(newerStarted, Priority.medium, T0.plusSeconds(10));

        List<Task> sorted = sort(
                List.of(fromUntouched, fromStarted),
                List.of(e),
                List.of(olderUntouched, newerStarted),
                Set.of(e.getId(), newerStarted.getId()));

        assertThat(sorted).containsExactly(fromStarted, fromUntouched);
    }

    @Test
    void anEarlierTargetDateOutranksBeingStarted() {
        // A date is the user's explicit signal; being started is only inferred from progress.
        Epic startedUndated = epic(Priority.high, null, T0);
        Epic untouchedDated = epic(Priority.high, LocalDate.of(2026, 3, 1), T0.plusSeconds(10));
        Story s1 = story(startedUndated, Priority.medium, null, T0);
        Story s2 = story(untouchedDated, Priority.medium, null, T0.plusSeconds(10));
        Task fromStarted = task(s1, Priority.medium, T0);
        Task fromDated = task(s2, Priority.medium, T0.plusSeconds(10));

        List<Task> sorted = sort(
                List.of(fromStarted, fromDated),
                List.of(startedUndated, untouchedDated),
                List.of(s1, s2),
                Set.of(startedUndated.getId(), s1.getId()));

        assertThat(sorted).containsExactly(fromDated, fromStarted);
    }

    @Test
    void earlierTargetDateWins_andNullSortsLast() {
        Epic soon = epic(Priority.medium, LocalDate.of(2026, 1, 1), T0.plusSeconds(2));
        Epic later = epic(Priority.medium, LocalDate.of(2027, 1, 1), T0.plusSeconds(1));
        Epic undated = epic(Priority.medium, null, T0);
        Story s1 = story(soon, Priority.medium, null, T0);
        Story s2 = story(later, Priority.medium, null, T0);
        Story s3 = story(undated, Priority.medium, null, T0);
        Task t1 = task(s1, Priority.medium, T0);
        Task t2 = task(s2, Priority.medium, T0);
        Task t3 = task(s3, Priority.medium, T0);

        List<Task> sorted = sort(List.of(t3, t2, t1), List.of(soon, later, undated), List.of(s1, s2, s3));

        assertThat(sorted).containsExactly(t1, t2, t3);
    }

    @Test
    void tiedOnEveryPriorKey_taskIdBreaksTheTieDeterministically() {
        Epic e = epic(Priority.medium, null, T0);
        Story s = story(e, Priority.medium, null, T0);
        Task smallerId = task(s, Priority.medium, T0);
        smallerId.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        Task largerId = task(s, Priority.medium, T0);
        largerId.setId(UUID.fromString("00000000-0000-0000-0000-000000000002"));

        List<Task> sorted = sort(List.of(largerId, smallerId), List.of(e), List.of(s));

        assertThat(sorted).containsExactly(smallerId, largerId);
    }

    @Test
    void tiedEpicsWithNoCreationTime_epicIdKeepsTheirTasksGrouped() {
        Epic a = epic(Priority.medium, null, null);
        a.setId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
        Epic b = epic(Priority.medium, null, null);
        b.setId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
        Story sa = story(a, Priority.medium, null, null);
        Story sb = story(b, Priority.medium, null, null);
        Task b1 = task(sb, Priority.medium, T0);
        Task a1 = task(sa, Priority.medium, T0.plusSeconds(1));
        Task b2 = task(sb, Priority.medium, T0.plusSeconds(2));

        List<Task> sorted = sort(List.of(b1, a1, b2), List.of(a, b), List.of(sa, sb));

        assertThat(sorted).containsExactly(a1, b1, b2);
    }

    private static List<Task> sort(List<Task> tasks, List<Epic> epics, List<Story> stories) {
        return sort(tasks, epics, stories, Set.of());
    }

    private static List<Task> sort(List<Task> tasks, List<Epic> epics, List<Story> stories, Set<UUID> startedIds) {
        Map<UUID, Epic> epicsById = epics.stream().collect(Collectors.toMap(Epic::getId, e -> e));
        Map<UUID, Story> storiesById = stories.stream().collect(Collectors.toMap(Story::getId, s -> s));
        Comparator<Task> c = TaskOrderingStrategy.comparator(epicsById, storiesById, startedIds);
        return tasks.stream().sorted(c).toList();
    }

    // Fixture builders — set ids explicitly since nothing is persisted here.
    private static Epic epic(Priority p, LocalDate target, Instant createdAt) {
        Epic e = new Epic();
        e.setId(UUID.randomUUID());
        e.setPriority(p);
        e.setTargetDate(target);
        ReflectionTestUtils.setField(e, "createdAt", createdAt);
        return e;
    }

    private static Story story(Epic parent, Priority p, LocalDate target, Instant createdAt) {
        Story s = new Story();
        s.setId(UUID.randomUUID());
        s.setEpicId(parent.getId());
        s.setPriority(p);
        s.setTargetDate(target);
        ReflectionTestUtils.setField(s, "createdAt", createdAt);
        return s;
    }

    private static Task task(Story parent, Priority p, Instant createdAt) {
        Task t = new Task();
        t.setId(UUID.randomUUID());
        t.setStoryId(parent.getId());
        t.setPriority(p);
        ReflectionTestUtils.setField(t, "createdAt", createdAt);
        return t;
    }
}
