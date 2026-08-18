package com.choruskube.core.service;

import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.WorkItemStatus;
import java.util.List;

/**
 * Computes the descendant-Task rollup for an Epic or Story (Decision 2): the counts an Epic or
 * Story reports as its progress, and — separately — the satisfaction status the dependency
 * machinery reasons with.
 *
 * <p>The counts are facts and are safe to publish. A synthesized <em>status</em> is not: an Epic
 * or Story has three board lanes ({@code backlog}/{@code in_progress}/{@code rolled_out}) and no
 * {@code done} lane, so a rollup that answers "done" names a state the item cannot actually be in
 * and contradicts the {@code stage} it is served alongside. {@code EpicResponse}/{@code
 * StoryResponse} therefore carry {@code stage} and {@code progress} only; callers that want to
 * show completion show the counts.
 *
 * <p>{@link #effectiveStatus} is the one legitimate use of a status word here, and it is not a
 * display value: readiness and blocking chains need a single yes/no answer to "is this blocker
 * cleared?", for which {@code rolled_out} and "every Task done" both count.
 */
final class RollupCalculator {

    private RollupCalculator() {}

    /**
     * @param startedTasks descendant Tasks that have left {@code backlog}. Kept distinct from
     *     {@code doneTasks} because "nothing has started yet" and "nothing has finished yet" are
     *     different questions, and the delete guard asks the first one.
     */
    record Rollup(long totalTasks, long doneTasks, long startedTasks) {}

    static Rollup compute(List<Task> tasks) {
        long total = tasks.size();
        long done =
                tasks.stream().filter(t -> t.getStatus() == WorkItemStatus.done).count();
        long started = tasks.stream()
                .filter(t -> t.getStatus() != WorkItemStatus.backlog)
                .count();
        return new Rollup(total, done, started);
    }

    /**
     * Whether an Epic or Story counts as satisfied for dependency purposes, as a {@link
     * WorkItemStatus} name. A human move to {@code rolled_out} is checked first and wins outright
     * — it is the only signal in the model that says "shipped", which a Task rollup cannot express,
     * and it outranks emptiness: a container with no Tasks and no {@code rolled_out} stage is never
     * satisfied.
     */
    static String effectiveStatus(WorkItemStatus stage, List<Task> tasks) {
        return effectiveStatus(stage.name(), compute(tasks));
    }

    /**
     * Same rule, for callers that hold a response DTO rather than the entity and its Tasks —
     * {@code stage} plus {@code progress} carry everything the rule needs, so a DTO-holding caller
     * never has to re-read Tasks or restate the rule. {@link DefaultBlockingChainService} resolves
     * items through the org-scoped services and so only ever sees this form.
     */
    static String effectiveStatus(String stage, EpicResponse.Progress progress) {
        return effectiveStatus(stage, new Rollup(progress.totalTasks(), progress.doneTasks(), progress.startedTasks()));
    }

    private static String effectiveStatus(String stage, Rollup rollup) {
        if (WorkItemStatus.rolled_out.name().equals(stage)) {
            return WorkItemStatus.done.name();
        }
        if (rollup.totalTasks() == 0) {
            return WorkItemStatus.backlog.name();
        }
        if (rollup.doneTasks() == rollup.totalTasks()) {
            return WorkItemStatus.done.name();
        }
        return rollup.startedTasks() > 0 ? WorkItemStatus.in_progress.name() : WorkItemStatus.backlog.name();
    }
}
