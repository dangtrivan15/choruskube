package com.choruskube.core.service;

import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.WorkItemStatus;
import java.util.List;

/**
 * Computes the derived status/progress rollup for an Epic or Story from its descendant Tasks
 * (Decision 2). Status is never stored on Epic/Story — it is always recomputed at read time
 * from the live Task rows: all done -&gt; done; any started -&gt; in_progress; otherwise (including
 * an empty container) -&gt; backlog.
 */
final class RollupCalculator {

    private RollupCalculator() {}

    record Rollup(long totalTasks, long doneTasks, String status) {}

    static Rollup compute(List<Task> tasks) {
        long total = tasks.size();
        long done =
                tasks.stream().filter(t -> t.getStatus() == WorkItemStatus.done).count();
        boolean anyStarted = tasks.stream().anyMatch(t -> t.getStatus() != WorkItemStatus.backlog);

        String status;
        if (total == 0) {
            status = WorkItemStatus.backlog.name();
        } else if (done == total) {
            status = WorkItemStatus.done.name();
        } else if (anyStarted) {
            status = WorkItemStatus.in_progress.name();
        } else {
            status = WorkItemStatus.backlog.name();
        }
        return new Rollup(total, done, status);
    }
}
