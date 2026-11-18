package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.CommittedFixtureCleaner;
import com.choruskube.core.SharedContextTest;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.model.enums.WorkflowRunStatusGroups;
import com.choruskube.core.repository.ArtifactUploadBookingRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.ArtifactUploadBudget.Admission;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Not {@code @Transactional}: the budget's row lock is only visible across separate, committed
 * connections — a single test transaction can't show two threads racing for it. {@link
 * CommittedFixtureCleaner} undoes the commits by hand, since the suite shares one database.
 */
@TestPropertySource(properties = "objectstore.run-upload-budget=1KB")
class ArtifactUploadBudgetIntegrationTest extends SharedContextTest {

    private static final long BUDGET_BYTES = 1024;

    @Autowired
    private ArtifactUploadBudget budget;

    @Autowired
    private ArtifactUploadBookingRepository bookingRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @Autowired
    private JdbcTemplate jdbc;

    private CommittedFixtureCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new CommittedFixtureCleaner(jdbc);
    }

    @AfterEach
    void removeEverythingThisTestCommitted() {
        cleaner.deleteAll();
    }

    @Test
    void withinBudget_isAdmittedAndBooked() {
        UUID runId = makeRun(WorkflowRunStatus.pending);

        Admission admission = budget.admit(runId, "key1", 500);

        assertThat(admission).isInstanceOf(Admission.Admitted.class);
        assertThat(bookingRepo.findBookedBytes(runId, "key1")).contains(500L);
    }

    @Test
    void rewriteAtSmallerSize_keepsTheLargerBookingAndAddsNothing() {
        UUID runId = makeRun(WorkflowRunStatus.pending);

        Admission first = budget.admit(runId, "key1", 500);
        Admission second = budget.admit(runId, "key1", 100);

        assertThat(first).isInstanceOf(Admission.Admitted.class);
        assertThat(second).isInstanceOf(Admission.Admitted.class);
        assertThat(bookingRepo.findBookedBytes(runId, "key1")).contains(500L);
        assertThat(bookingRepo.sumBookedBytes(runId)).isEqualTo(500L);
    }

    @Test
    void rewriteAtLargerSize_booksOnlyTheDifference() {
        UUID runId = makeRun(WorkflowRunStatus.pending);

        Admission first = budget.admit(runId, "key1", 500);
        Admission second = budget.admit(runId, "key1", 700);

        assertThat(first).isInstanceOf(Admission.Admitted.class);
        assertThat(second).isInstanceOf(Admission.Admitted.class);
        assertThat(bookingRepo.findBookedBytes(runId, "key1")).contains(700L);
        assertThat(bookingRepo.sumBookedBytes(runId)).isEqualTo(700L);
    }

    @Test
    void overBudget_namesTheBudgetTheBookedTotalAndTheDeclaredSize_andBooksNothing() {
        UUID runId = makeRun(WorkflowRunStatus.pending);

        Admission first = budget.admit(runId, "key1", 500);
        Admission second = budget.admit(runId, "key2", 600);

        assertThat(first).isInstanceOf(Admission.Admitted.class);
        assertThat(second).isInstanceOf(Admission.OverBudget.class);
        Admission.OverBudget overBudget = (Admission.OverBudget) second;
        assertThat(overBudget.budgetBytes()).isEqualTo(BUDGET_BYTES);
        assertThat(overBudget.bookedBytes()).isEqualTo(500L);
        assertThat(overBudget.declaredBytes()).isEqualTo(600L);
        assertThat(bookingRepo.sumBookedBytes(runId)).isEqualTo(500L);
    }

    @Test
    void declarationThatWouldOverflowTheRunTotal_isOverBudgetAndBooksNothing() {
        UUID runId = makeRun(WorkflowRunStatus.pending);
        budget.admit(runId, "key1", 1);

        Admission admission = budget.admit(runId, "key2", Long.MAX_VALUE);

        assertThat(admission).isInstanceOf(Admission.OverBudget.class);
        assertThat(((Admission.OverBudget) admission).bookedBytes()).isEqualTo(1L);
        assertThat(bookingRepo.findBookedBytes(runId, "key2")).isEmpty();
        assertThat(bookingRepo.sumBookedBytes(runId)).isEqualTo(1L);
    }

    @Test
    void declarationFillingTheBudgetExactly_isAdmitted() {
        UUID runId = makeRun(WorkflowRunStatus.pending);
        budget.admit(runId, "key1", 24);

        assertThat(budget.admit(runId, "key2", BUDGET_BYTES - 24)).isInstanceOf(Admission.Admitted.class);
        assertThat(budget.admit(runId, "key3", 1)).isInstanceOf(Admission.OverBudget.class);
        assertThat(bookingRepo.sumBookedBytes(runId)).isEqualTo(BUDGET_BYTES);
    }

    @Test
    void everyTerminalStatus_returnsRunClosedAndBooksNothing() {
        for (WorkflowRunStatus status : WorkflowRunStatusGroups.TERMINAL) {
            UUID runId = makeRun(status);

            Admission admission = budget.admit(runId, "key1", 500);

            assertThat(admission).isInstanceOf(Admission.RunClosed.class);
            assertThat(((Admission.RunClosed) admission).status()).isEqualTo(status);
            assertThat(bookingRepo.sumBookedBytes(runId)).isEqualTo(0L);
        }
    }

    @Test
    void concurrentAdmissionsThatFitAloneButNotTogether_admitExactlyOne() throws Exception {
        UUID runId = makeRun(WorkflowRunStatus.pending);
        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Admission>> futures = pool.invokeAll(
                    List.of(admitAt(startLine, runId, "keyA", 600), admitAt(startLine, runId, "keyB", 600)));
            Admission resultA = futures.get(0).get(30, TimeUnit.SECONDS);
            Admission resultB = futures.get(1).get(30, TimeUnit.SECONDS);

            List<Admission> results = List.of(resultA, resultB);
            assertThat(results)
                    .as("exactly one of the two racing admissions fits the shared budget")
                    .filteredOn(Admission.Admitted.class::isInstance)
                    .hasSize(1);
            assertThat(results)
                    .filteredOn(Admission.OverBudget.class::isInstance)
                    .hasSize(1);
            assertThat(bookingRepo.sumBookedBytes(runId)).isEqualTo(600L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deletingTheRun_cascadesAwayItsBookings() {
        UUID runId = makeRun(WorkflowRunStatus.pending);
        budget.admit(runId, "key1", 500);
        assertThat(bookingRepo.findBookedBytes(runId, "key1")).isPresent();

        runRepo.deleteById(runId);

        assertThat(bookingRepo.findBookedBytes(runId, "key1")).isEmpty();
    }

    private Callable<Admission> admitAt(CyclicBarrier startLine, UUID runId, String key, long declaredBytes) {
        return () -> {
            startLine.await(30, TimeUnit.SECONDS);
            return budget.admit(runId, key, declaredBytes);
        };
    }

    private UUID makeRun(WorkflowRunStatus status) {
        GraphTemplate template = new GraphTemplate();
        String unique = UUID.randomUUID().toString();
        template.setName("Upload Budget Test Template " + unique);
        template.setGraphId("upload-budget-test-" + unique);
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setStatus(status);
        run = runRepo.save(run);
        cleaner.trackWorkflowRun(run.getId());
        return run.getId();
    }
}
