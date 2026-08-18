package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.WorkItemStatus;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The counts an Epic/Story publishes, and the satisfaction status only the dependency machinery
 * consumes. The distinction is the point of this class: no synthesized status reaches a response,
 * because a container has no {@code done} lane to be in.
 */
class RollupCalculatorTest {

    private static Task task(WorkItemStatus status) {
        Task t = new Task();
        t.setStatus(status);
        return t;
    }

    @Nested
    class Counts {

        @Test
        void emptyContainerCountsNothing() {
            RollupCalculator.Rollup rollup = RollupCalculator.compute(List.of());
            assertThat(rollup.totalTasks()).isZero();
            assertThat(rollup.doneTasks()).isZero();
            assertThat(rollup.startedTasks()).isZero();
        }

        @Test
        void startedCountsEveryTaskOutOfBacklog_notJustFinishedOnes() {
            RollupCalculator.Rollup rollup = RollupCalculator.compute(
                    List.of(task(WorkItemStatus.backlog), task(WorkItemStatus.in_progress), task(WorkItemStatus.done)));
            assertThat(rollup.totalTasks()).isEqualTo(3);
            assertThat(rollup.doneTasks()).isEqualTo(1);
            // The delete guard asks "has anything begun?", which doneTasks alone cannot answer.
            assertThat(rollup.startedTasks()).isEqualTo(2);
        }
    }

    @Nested
    class EffectiveStatus {

        @Test
        void rolledOutWinsOutrightEvenWithUnfinishedTasks() {
            assertThat(RollupCalculator.effectiveStatus(
                            WorkItemStatus.rolled_out, List.of(task(WorkItemStatus.backlog))))
                    .isEqualTo("done");
        }

        @Test
        void rolledOutWinsOutrightEvenWithNoTasksAtAll() {
            assertThat(RollupCalculator.effectiveStatus(WorkItemStatus.rolled_out, List.of()))
                    .isEqualTo("done");
        }

        @Test
        void emptyContainerIsNeverSatisfied() {
            // Emptiness is not completion — otherwise an Epic with no work would silently unblock
            // everything depending on it.
            assertThat(RollupCalculator.effectiveStatus(WorkItemStatus.backlog, List.of()))
                    .isEqualTo("backlog");
        }

        @Test
        void everyTaskDoneSatisfiesWithoutTheStageMoving() {
            assertThat(RollupCalculator.effectiveStatus(
                            WorkItemStatus.backlog, List.of(task(WorkItemStatus.done), task(WorkItemStatus.done))))
                    .isEqualTo("done");
        }

        @Test
        void anyStartedTaskReadsAsInProgress() {
            assertThat(RollupCalculator.effectiveStatus(
                            WorkItemStatus.backlog,
                            List.of(task(WorkItemStatus.in_progress), task(WorkItemStatus.backlog))))
                    .isEqualTo("in_progress");
        }

        @Test
        void nothingStartedReadsAsBacklog() {
            assertThat(RollupCalculator.effectiveStatus(
                            WorkItemStatus.in_progress, List.of(task(WorkItemStatus.backlog))))
                    .isEqualTo("backlog");
        }

        @Test
        void dtoOverloadAgreesWithTheEntityOverload() {
            // DefaultBlockingChainService resolves items through the org-scoped services and only
            // ever holds a response, so both forms must answer identically.
            List<Task> tasks = List.of(task(WorkItemStatus.done), task(WorkItemStatus.backlog));
            RollupCalculator.Rollup rollup = RollupCalculator.compute(tasks);
            EpicResponse.Progress progress =
                    new EpicResponse.Progress(rollup.totalTasks(), rollup.doneTasks(), rollup.startedTasks());

            assertThat(RollupCalculator.effectiveStatus("backlog", progress))
                    .isEqualTo(RollupCalculator.effectiveStatus(WorkItemStatus.backlog, tasks))
                    .isEqualTo("in_progress");
        }

        @Test
        void dtoOverloadShortCircuitsOnRolledOutToo() {
            assertThat(RollupCalculator.effectiveStatus("rolled_out", new EpicResponse.Progress(2, 0, 0)))
                    .isEqualTo("done");
        }
    }
}
