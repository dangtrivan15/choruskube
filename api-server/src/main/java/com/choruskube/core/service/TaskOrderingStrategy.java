package com.choruskube.core.service;

import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.Priority;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Orders the Autopilot's READY frontier by choosing an Epic, then a Story inside it, then a Task
 * inside that — so every Task of the chosen Epic sorts ahead of any other Epic's, and the Autopilot
 * finishes one Epic before it moves to the next.
 *
 * <p>An Epic, and a Story within its Epic, is ranked by:
 *
 * <ol>
 *   <li>priority, descending
 *   <li>target date, ascending, null last
 *   <li>started first — any of its Tasks is past {@code backlog}
 *   <li>{@code created_at}, then id
 * </ol>
 *
 * and a Task within its Story by priority descending, then {@code created_at}, then id.
 *
 * <p>Each level ends on its own id, not on a Task key. Ranking Epics by priority alone and then
 * by Task age interleaves Epics of equal priority one Task at a time — with three priority levels
 * a tie is the common case, so the tiebreak is what decides whether the Autopilot stays on an
 * Epic at all.
 *
 * <p>A target date ranks above being started: the date is the user's explicit signal, while
 * started is only inferred from progress. Started stands in for "the user is mid-way through
 * this", and survives a run parking on a human gate, which a live-pod test would not.
 *
 * <p>{@link Priority} is declared {@code low, medium, high}, so its natural order is ascending and
 * every priority comparison here is explicitly reversed; a null priority ranks as {@code medium}.
 *
 * <p>The trailing ids exist because {@code maxParallel = 1} is the default: with every prior key
 * tied, which Task the Autopilot picks is the entire user-visible behaviour of a tick, so the pick
 * must be deterministic across ticks and across replicas rather than left to sort stability.
 */
public final class TaskOrderingStrategy {

    private TaskOrderingStrategy() {}

    /**
     * @param startedIds the Epics and Stories with a Task past {@code backlog}; the two id spaces
     *     never collide, so one set serves both levels
     */
    public static Comparator<Task> comparator(
            Map<UUID, Epic> epicsById, Map<UUID, Story> storiesById, Set<UUID> startedIds) {
        Function<Task, Story> storyOf = t -> storiesById.get(t.getStoryId());
        Function<Task, Epic> epicOf = t -> {
            Story s = storyOf.apply(t);
            return s == null ? null : epicsById.get(s.getEpicId());
        };
        Comparator<Epic> epicOrder =
                level(startedIds, Epic::getPriority, Epic::getTargetDate, Epic::getCreatedAt, Epic::getId);
        Comparator<Story> storyOrder =
                level(startedIds, Story::getPriority, Story::getTargetDate, Story::getCreatedAt, Story::getId);
        return Comparator.comparing(epicOf, Comparator.nullsLast(epicOrder))
                .thenComparing(storyOf, Comparator.nullsLast(storyOrder))
                .thenComparing(t -> priorityRank(t.getPriority()))
                .thenComparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Task::getId);
    }

    private static <C> Comparator<C> level(
            Set<UUID> startedIds,
            Function<C, Priority> priority,
            Function<C, LocalDate> targetDate,
            Function<C, Instant> createdAt,
            Function<C, UUID> id) {
        return Comparator.<C, Integer>comparing(c -> priorityRank(priority.apply(c)))
                .thenComparing(c -> targetDateKey(targetDate.apply(c)))
                // false sorts before true, so a started container comes first.
                .thenComparing(c -> !startedIds.contains(id.apply(c)))
                .thenComparing(createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(id);
    }

    /** Higher priority sorts first, so the ascending enum ordinal is negated. */
    private static int priorityRank(Priority p) {
        return p == null ? -Priority.medium.ordinal() : -p.ordinal();
    }

    /** Undated work sorts last, so a null date becomes the maximum. */
    private static LocalDate targetDateKey(LocalDate d) {
        return d == null ? LocalDate.MAX : d;
    }
}
