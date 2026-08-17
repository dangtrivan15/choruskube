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
 * Orders the Autopilot's READY frontier (Decision 6), in this exact order:
 *
 * <ol>
 *   <li>Epic priority, descending
 *   <li>Story priority, descending
 *   <li>epic affinity — deliberately NOT here; applied later as a stable partition of this
 *       comparator's output, since it depends on which Epics currently have runs in flight
 *   <li>Story target date, ascending, null last
 *   <li>Epic target date, ascending, null last
 *   <li>{@code created_at}
 *   <li>Task id — a final total-order tiebreak
 * </ol>
 *
 * <p>Story target date is checked before Epic target date: a Story deadline inside an undated (or
 * later-dated) Epic is the more specific signal, so the narrower scope wins.
 *
 * <p>{@link Priority} is declared {@code low, medium, high}, so its natural order is ascending and
 * every priority comparison here is explicitly reversed. A null target date sorts LAST: undated
 * work is not urgent work.
 *
 * <p>The trailing {@code Task::getId} comparison exists because {@code maxParallel = 1} is the
 * default: with every prior key tied, which Task the Autopilot picks is the entire user-visible
 * behaviour of a tick, so the pick must be deterministic across ticks and across replicas rather
 * than left to sort stability.
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
                .thenComparing(t -> targetDateKey(storiesById.get(t.getStoryId())))
                .thenComparing(t -> targetDateKey(epicOf(t, storiesById, epicsById)))
                .thenComparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Task::getId);
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
