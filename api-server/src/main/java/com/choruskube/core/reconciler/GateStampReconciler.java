package com.choruskube.core.reconciler;

import com.choruskube.core.model.GateStamp;
import com.choruskube.core.service.GateRecheckService;
import com.choruskube.core.service.RunScopeBinder;
import com.choruskube.core.service.RunService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-applies held gate approvals whose gate awaits a decision again, and voids those whose run
 * ended. Polling rather than hooking the gate's status write also covers a crash between that write
 * and the re-apply; the node-execution claim keeps a concurrent pass, or replica, from deciding a
 * gate twice.
 */
@Component
@ConditionalOnProperty(name = "choruskube.gate-stamp.enabled", havingValue = "true", matchIfMissing = true)
public class GateStampReconciler {

    private static final Logger log = LoggerFactory.getLogger(GateStampReconciler.class);

    private final GateRecheckService gateRecheck;
    private final RunService runService;
    private final RunScopeBinder runScopeBinder;

    public GateStampReconciler(GateRecheckService gateRecheck, RunService runService, RunScopeBinder runScopeBinder) {
        this.gateRecheck = gateRecheck;
        this.runService = runService;
        this.runScopeBinder = runScopeBinder;
    }

    @Scheduled(fixedDelayString = "${choruskube.gate-stamp.interval:PT10S}")
    public void reconcile() {
        try {
            int ended = gateRecheck.voidPendingOnEndedRuns();
            if (ended > 0) {
                log.info("GateStampReconciler voided {} held approval(s) on ended runs", ended);
            }
        } catch (Exception e) {
            log.error("GateStampReconciler could not void held approvals on ended runs: {}", e.getMessage(), e);
        }
        List<GateStamp> ready;
        try {
            ready = gateRecheck.findReadyToReapply();
        } catch (Exception e) {
            log.error("GateStampReconciler could not list held approvals: {}", e.getMessage(), e);
            return;
        }
        for (GateStamp stamp : ready) {
            try {
                runScopeBinder.runInScopeOf(
                        stamp.getWorkflowRunId(), () -> runService.applyHeldApproval(stamp.getId()));
            } catch (Exception e) {
                log.error(
                        "Re-applying held approval {} for run {} failed: {}",
                        stamp.getId(),
                        stamp.getWorkflowRunId(),
                        e.getMessage(),
                        e);
            }
        }
    }
}
