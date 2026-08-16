package com.choruskube.core.service;

import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.Priority;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

/**
 * Orders the Autopilot's READY frontier (Decision 6): Epic priority, then Story priority, then
 * epic affinity, then target dates, then creation order.
 *
 * <p>Epic affinity — preferring another Task from an Epic already in flight — is deliberately a
 * tiebreak rather than a primary key: it reduces context-switching without letting a low-priority
 * Epic monopolise the frontier because it happened to start first.
 *
 * <p>{@link Priority} is declared {@code low, medium, high}, so its natural order is ascending and
 * every priority comparison here is explicitly reversed. A null target date sorts LAST: undated
 * work is not urgent work.
 *
 * <p>Deliberately a static factory rather than an injectable strategy bean — there is exactly one
 * ordering today. The seam Decision 6 leaves open for critical-path ordering is this class's
 * signature, which a future implementation can vary without touching its callers.
 */
public final class TaskOrderingStrategy {

    private TaskOrderingStrategy() {}

    public static Comparator<Task> comparator(Map<UUID, Epic> epicsById, Map<UUID, Story> storiesById) {
        return Comparator.<Task, Integer>comparing(t -> priorityRank(epicOf(t, storiesById, epicsById)))
                .thenComparing(t -> priorityRank(storiesById.get(t.getStoryId())))
                .thenComparing(t -> targetDateKey(epicOf(t, storiesById, epicsById)))
                .thenComparing(t -> targetDateKey(storiesById.get(t.getStoryId())))
                .thenComparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /** Higher priority sorts first, so the ascending enum ordinal is negated. */
    private static int priorityRank(Object item) {
        Priority p = priorityOf(item);
        return p == null ? -Priority.medium.ordinal() : -p.ordinal();
    }

    private static Priority priorityOf(Object item) {
        if (item instanceof Epic e) return e.getPriority();
        if (item instanceof Story s) return s.getPriority();
        return null;
    }

    /** Undated work sorts last, so a null date becomes the maximum. */
    private static LocalDate targetDateKey(Object item) {
        LocalDate d = null;
        if (item instanceof Epic e) d = e.getTargetDate();
        if (item instanceof Story s) d = s.getTargetDate();
        return d == null ? LocalDate.MAX : d;
    }

    private static Epic epicOf(Task t, Map<UUID, Story> storiesById, Map<UUID, Epic> epicsById) {
        Story s = storiesById.get(t.getStoryId());
        return s == null ? null : epicsById.get(s.getEpicId());
    }
}
