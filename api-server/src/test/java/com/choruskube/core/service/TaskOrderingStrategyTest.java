package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.Priority;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskOrderingStrategyTest {

    @Test
    void higherEpicPriorityComesFirst() {
        Epic lowEpic = epic(Priority.low, null);
        Epic highEpic = epic(Priority.high, null);
        Story lowStory = story(lowEpic, Priority.medium, null);
        Story highStory = story(highEpic, Priority.medium, null);
        Task fromLow = task(lowStory);
        Task fromHigh = task(highStory);

        List<Task> sorted = sort(
                List.of(fromLow, fromHigh),
                Map.of(lowEpic.getId(), lowEpic, highEpic.getId(), highEpic),
                Map.of(lowStory.getId(), lowStory, highStory.getId(), highStory));

        assertThat(sorted).containsExactly(fromHigh, fromLow);
    }

    @Test
    void withinSameEpic_higherStoryPriorityComesFirst() {
        Epic e = epic(Priority.medium, null);
        Story lowStory = story(e, Priority.low, null);
        Story highStory = story(e, Priority.high, null);
        Task fromLow = task(lowStory);
        Task fromHigh = task(highStory);

        List<Task> sorted = sort(
                List.of(fromLow, fromHigh),
                Map.of(e.getId(), e),
                Map.of(lowStory.getId(), lowStory, highStory.getId(), highStory));

        assertThat(sorted).containsExactly(fromHigh, fromLow);
    }

    @Test
    void earlierTargetDateWins_andNullSortsLast() {
        Epic soon = epic(Priority.medium, LocalDate.of(2026, 1, 1));
        Epic later = epic(Priority.medium, LocalDate.of(2027, 1, 1));
        Epic undated = epic(Priority.medium, null);
        Story s1 = story(soon, Priority.medium, null);
        Story s2 = story(later, Priority.medium, null);
        Story s3 = story(undated, Priority.medium, null);
        Task t1 = task(s1);
        Task t2 = task(s2);
        Task t3 = task(s3);

        List<Task> sorted = sort(
                List.of(t3, t2, t1),
                Map.of(soon.getId(), soon, later.getId(), later, undated.getId(), undated),
                Map.of(s1.getId(), s1, s2.getId(), s2, s3.getId(), s3));

        assertThat(sorted).containsExactly(t1, t2, t3);
    }

    private static List<Task> sort(List<Task> tasks, Map<UUID, Epic> epics, Map<UUID, Story> stories) {
        Comparator<Task> c = TaskOrderingStrategy.comparator(epics, stories);
        return tasks.stream().sorted(c).toList();
    }

    // Fixture builders — set ids explicitly since nothing is persisted here.
    private static Epic epic(Priority p, LocalDate target) {
        Epic e = new Epic();
        e.setId(UUID.randomUUID());
        e.setPriority(p);
        e.setTargetDate(target);
        return e;
    }

    private static Story story(Epic parent, Priority p, LocalDate target) {
        Story s = new Story();
        s.setId(UUID.randomUUID());
        s.setEpicId(parent.getId());
        s.setPriority(p);
        s.setTargetDate(target);
        return s;
    }

    private static Task task(Story parent) {
        Task t = new Task();
        t.setId(UUID.randomUUID());
        t.setStoryId(parent.getId());
        return t;
    }
}
