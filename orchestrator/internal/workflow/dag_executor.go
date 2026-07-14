package workflow

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/workflow"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/prompt"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/state"
)

const TaskQueue = "choruskube"

// DAGExecutorParams contains everything needed to execute a workflow run.
// Phase 2: SingleNode is kept for backwards compatibility but ignored when nil.
type DAGExecutorParams struct {
	RunID                uuid.UUID
	GraphVersion         int
	OrgSlug              string            // Org slug for object storage path isolation; empty = legacy paths
	RunInputArtifactRefs string            // NEW: JSON string from workflow_run.input_artifact_refs; "" or "{}" = no run-level inputs
	SingleNode           *SingleNodeParams // Deprecated: Phase 1 only
}

type SingleNodeParams struct {
	TemplateNodeID uuid.UUID
	PromptTemplate string
	Image          string
	InputArtifacts map[string]string
	Variables      map[string]string
}

// nodeCompletion is sent on the completion channel when a node finishes
type nodeCompletion struct {
	nodeID       uuid.UUID
	result       string
	err          error
	artifactRefs string // AI nodes: artifact refs from callback
	errorMessage string // from CallbackResult.ErrorMessage
}

// nodeTracker tracks in-workflow state for each activated node
type nodeTracker struct {
	status                 string // pending, running, awaiting_human, completed, failed
	result                 *string
	execID                 uuid.UUID
	iteration              int
	iterationCapEpochStart int     // epoch start for cap enforcement; default 1
	errorMessage           *string // from completion
	artifactRefs           string  // from completion
	preDecision            string  // pre-supplied decision from retry-with-approval (skip human wait)
	preFeedback            string  // pre-supplied feedback from retry-with-approval
}

func DAGExecutorWorkflow(ctx workflow.Context, params DAGExecutorParams) error {
	logger := workflow.GetLogger(ctx)
	logger.Info("Starting DAG executor", "runID", params.RunID)

	// Activity options
	dbOpts := workflow.ActivityOptions{
		StartToCloseTimeout: 30 * time.Second,
		RetryPolicy:         &temporal.RetryPolicy{MaximumAttempts: 3},
	}
	dbCtx := workflow.WithActivityOptions(ctx, dbOpts)

	var activities *activity.Activities

	// Step 1: Mark run as running
	if err := workflow.ExecuteActivity(dbCtx, activities.UpdateWorkflowRunStatus,
		activity.UpdateRunStatusParams{RunID: params.RunID, Status: "running"},
	).Get(ctx, nil); err != nil {
		return fmt.Errorf("update run status to running: %w", err)
	}

	// Step 1b: Initialize run log in object storage
	if err := workflow.ExecuteActivity(dbCtx, activities.InitRunLog,
		activity.InitRunLogParams{RunID: params.RunID, OrgSlug: params.OrgSlug},
	).Get(ctx, nil); err != nil {
		return fmt.Errorf("init run log: %w", err)
	}

	// Step 2: Load graph snapshot
	var snapshotJSON string
	if err := workflow.ExecuteActivity(dbCtx, activities.GetGraphRuntime,
		params.RunID,
	).Get(ctx, &snapshotJSON); err != nil {
		return fmt.Errorf("load graph snapshot: %w", err)
	}

	snap, err := ParseSnapshot(snapshotJSON)
	if err != nil {
		return err
	}

	// Parse run-level input artifact refs for injection into every AI node's config.json
	runInputArtifacts := map[string]string{}
	if params.RunInputArtifactRefs != "" && params.RunInputArtifactRefs != "{}" {
		var rawRefs map[string]string
		if err := json.Unmarshal([]byte(params.RunInputArtifactRefs), &rawRefs); err != nil {
			logger.Warn("Failed to unmarshal RunInputArtifactRefs; run-level inputs will be skipped",
				"runID", params.RunID, "error", err)
		} else {
			for filename, objectPath := range rawRefs {
				runInputArtifacts["run_input/"+filename] = objectPath
			}
		}
	}

	// Initialize node tracking
	nodes := make(map[uuid.UUID]*nodeTracker)

	// Step 3: Create node_execution rows for entry nodes
	entryNodes := FindEntryNodes(snap)
	if len(entryNodes) == 0 {
		return fmt.Errorf("no entry nodes found in graph")
	}
	for _, node := range entryNodes {
		var execID uuid.UUID
		if err := workflow.ExecuteActivity(dbCtx, activities.CreateNodeExecution,
			activity.CreateNodeExecParams{
				WorkflowRunID:  params.RunID,
				TemplateNodeID: node.TemplateNodeID,
				GraphVersion:   params.GraphVersion,
				Iteration:      1,
				Label:          node.Label,
			},
		).Get(ctx, &execID); err != nil {
			return fmt.Errorf("create entry node execution for %s: %w", node.Label, err)
		}
		nodes[node.TemplateNodeID] = &nodeTracker{
			status: "pending", execID: execID, iteration: 1, iterationCapEpochStart: 1,
		}
	}

	// Pause/resume/cancel handling
	paused := false
	cancelled := false
	pauseInterrupted := make(map[uuid.UUID]struct{})
	pauseCh := workflow.GetSignalChannel(ctx, SignalPause)
	resumeCh := workflow.GetSignalChannel(ctx, SignalResume)
	cancelCh := workflow.GetSignalChannel(ctx, SignalCancel)

	// Completion channel for tracking node completions
	completionCh := workflow.NewChannel(ctx)

	// Step 4: Main execution loop
	for {
		// 4a: Check for cancel signal (non-blocking drain)
		for {
			var sig interface{}
			if !cancelCh.ReceiveAsync(&sig) {
				break
			}
			cancelled = true
		}
		if cancelled {
			logger.Info("Workflow cancelled")
			break
		}

		// Check for pause signal (non-blocking drain)
		for {
			var sig interface{}
			ok := pauseCh.ReceiveAsync(&sig)
			if !ok {
				break
			}
			paused = true
			workflow.ExecuteActivity(dbCtx, activities.UpdateWorkflowRunStatus,
				activity.UpdateRunStatusParams{RunID: params.RunID, Status: "paused"},
			).Get(ctx, nil)
		}

		// When the run is paused, stamp running non-human/non-live_chat nodes as "paused"
		// and delete their K8s jobs. This complements the api-server's pauseRun() cleanup;
		// both sides are idempotent (api-server returns 204 even on 404).
		if paused {
			for nodeID, tracker := range nodes {
				if tracker.status == "running" {
					snapshotNode, ok := GetNodeByID(snap, nodeID)
					if !ok || snapshotNode.ExecutorType == "human" || snapshotNode.ExecutorType == "live_chat" {
						continue
					}
					// Stamp node as paused before deleting the job so any in-flight callback
					// hits the 409 guard rather than advancing the node out of paused state.
					if stampErr := workflow.ExecuteActivity(dbCtx, activities.UpdateNodeExecutionStatus,
						activity.UpdateNodeExecStatusParams{
							RunID:           params.RunID,
							NodeExecutionID: tracker.execID,
							Status:          "paused",
						},
					).Get(ctx, nil); stampErr != nil {
						logger.Warn("UpdateNodeExecutionStatus(paused) failed (non-fatal)",
							"nodeID", nodeID, "execID", tracker.execID, "err", stampErr)
					} else {
						pauseInterrupted[tracker.execID] = struct{}{}
					}
					// Delete the K8s job (idempotent; job may already be gone if api-server beat us).
					if cleanErr := workflow.ExecuteActivity(dbCtx, activities.DeleteAgentJob,
						activity.DeleteAgentJobParams{NodeExecutionID: tracker.execID},
					).Get(ctx, nil); cleanErr != nil {
						logger.Warn("DeleteAgentJob on pause failed (non-fatal)",
							"nodeID", nodeID,
							"execID", tracker.execID,
							"err", cleanErr)
					}
				}
			}
		}

		// Block if paused — listen for both resume and cancel
		if paused {
			logger.Info("Workflow paused, waiting for resume or cancel")
			pauseSelector := workflow.NewSelector(ctx)
			pauseSelector.AddReceive(resumeCh, func(ch workflow.ReceiveChannel, more bool) {
				ch.Receive(ctx, nil)
				paused = false
			})
			pauseSelector.AddReceive(cancelCh, func(ch workflow.ReceiveChannel, more bool) {
				ch.Receive(ctx, nil)
				cancelled = true
				paused = false
			})
			pauseSelector.Select(ctx)

			if cancelled {
				logger.Info("Workflow cancelled while paused")
			} else {
				workflow.ExecuteActivity(dbCtx, activities.UpdateWorkflowRunStatus,
					activity.UpdateRunStatusParams{RunID: params.RunID, Status: "running"},
				).Get(ctx, nil)
				logger.Info("Workflow resumed")
			}
		}

		// 4b: Find ready nodes
		nodeStates := make(map[uuid.UUID]string)
		for id, tracker := range nodes {
			nodeStates[id] = tracker.status
		}
		readyNodes := FindReadyNodes(snap, nodeStates)

		// 4c: Check termination
		runningCount := 0
		for _, tracker := range nodes {
			if tracker.status == "running" || tracker.status == "awaiting_human" {
				runningCount++
			}
		}
		if len(readyNodes) == 0 && runningCount == 0 {
			// Check if any nodes failed — offer retry opportunity
			var failedNodeIDs []uuid.UUID
			for nodeID, tracker := range nodes {
				if tracker.status == "failed" {
					failedNodeIDs = append(failedNodeIDs, nodeID)
				}
			}

			if len(failedNodeIDs) == 0 || cancelled {
				break // all completed or cancelled
			}

			// Enter awaiting_retry mode
			retryDeadline := workflow.Now(ctx).Add(7 * 24 * time.Hour)
			workflow.ExecuteActivity(dbCtx, activities.UpdateWorkflowRunStatus,
				activity.UpdateRunStatusParams{RunID: params.RunID, Status: "awaiting_retry"},
			).Get(ctx, nil)

			// Log retry availability for each failed node
			for _, failedID := range failedNodeIDs {
				failedTracker := nodes[failedID]
				failedNode, _ := GetNodeByID(snap, failedID)
				workflow.ExecuteActivity(dbCtx, activities.WriteExecutionLog,
					activity.WriteExecutionLogParams{
						RunID: params.RunID, NodeExecutionID: failedTracker.execID, Level: "info",
						Message: fmt.Sprintf("Retry available until %s (7 days). Use the Retry button to re-run this node.",
							retryDeadline.UTC().Format("2006-01-02 15:04 UTC")),
					},
				).Get(ctx, nil)
				logger.Info("Node awaiting retry", "node", failedNode.Label, "deadline", retryDeadline)
			}

			// Wait for retry signal, cancel, or timeout
			retryTimerCtx, retryTimerCancel := workflow.WithCancel(ctx)
			retryTimeout := workflow.NewTimer(retryTimerCtx, 7*24*time.Hour)
			retryCh := workflow.GetSignalChannel(ctx, SignalRetryNode)

			timedOut := false
			retrySelector := workflow.NewSelector(ctx)

			retrySelector.AddReceive(retryCh, func(ch workflow.ReceiveChannel, more bool) {
				var signal RetryNodeSignal
				ch.Receive(ctx, &signal)

				templateNodeID, err := uuid.Parse(signal.TemplateNodeID)
				if err != nil {
					logger.Error("Invalid retry signal", "templateNodeId", signal.TemplateNodeID)
					return
				}

				tracker, exists := nodes[templateNodeID]
				if !exists || tracker.status != "failed" {
					logger.Warn("Retry signal for non-failed node", "templateNodeId", signal.TemplateNodeID)
					return
				}

				retryTimerCancel()

				// Re-fetch the snapshot so retries pick up GitRepo changes
				// (e.g. updated test_command). This is a new activity invocation,
				// so Temporal executes it rather than replaying the original.
				var refreshedJSON string
				if err := workflow.ExecuteActivity(dbCtx, activities.GetGraphRuntime,
					params.RunID,
				).Get(ctx, &refreshedJSON); err != nil {
					logger.Error("Failed to refresh snapshot for retry", "error", err)
				} else if refreshed, err := ParseSnapshot(refreshedJSON); err != nil {
					logger.Error("Failed to parse refreshed snapshot", "error", err)
				} else {
					snap = refreshed
				}

				targetNode, _ := GetNodeByID(snap, templateNodeID)
				newIteration := tracker.iteration + 1

				// Create new node execution
				var execID uuid.UUID
				if err := workflow.ExecuteActivity(dbCtx, activities.CreateNodeExecution,
					activity.CreateNodeExecParams{
						WorkflowRunID:          params.RunID,
						TemplateNodeID:         templateNodeID,
						GraphVersion:           params.GraphVersion,
						Iteration:              newIteration,
						Label:                  targetNode.Label,
						IterationCapEpochStart: tracker.iterationCapEpochStart,
					},
				).Get(ctx, &execID); err != nil {
					logger.Error("Failed to create retry node execution", "error", err)
					return
				}

				// Reset tracker
				nodes[templateNodeID] = &nodeTracker{
					status:                 "pending",
					execID:                 execID,
					iteration:              newIteration,
					iterationCapEpochStart: tracker.iterationCapEpochStart,
				}

				// Restore run status
				workflow.ExecuteActivity(dbCtx, activities.UpdateWorkflowRunStatus,
					activity.UpdateRunStatusParams{RunID: params.RunID, Status: "running"},
				).Get(ctx, nil)

				workflow.ExecuteActivity(dbCtx, activities.WriteExecutionLog,
					activity.WriteExecutionLogParams{
						RunID: params.RunID, NodeExecutionID: execID, Level: "info",
						Message: fmt.Sprintf("Node retried (iteration %d)", newIteration),
					},
				).Get(ctx, nil)

			})

			// Also listen for human-decision signals for failed human nodes.
			// If a human gate timed out but the user later clicks approve,
			// the signal arrives on the original exec's channel. We treat it
			// as "retry this node with the supplied decision".
			for _, fid := range failedNodeIDs {
				failedID := fid // capture loop variable
				failedTracker := nodes[failedID]
				failedNode, _ := GetNodeByID(snap, failedID)
				if failedNode.ExecutorType != "human" {
					continue
				}

				humanCh := workflow.GetSignalChannel(ctx, SignalHumanDecisionPrefix+failedTracker.execID.String())
				retrySelector.AddReceive(humanCh, func(ch workflow.ReceiveChannel, more bool) {
					var signal HumanDecisionSignal
					ch.Receive(ctx, &signal)

					retryTimerCancel()

					newIteration := failedTracker.iteration + 1

					// Create new node execution for the retry
					var execID uuid.UUID
					if err := workflow.ExecuteActivity(dbCtx, activities.CreateNodeExecution,
						activity.CreateNodeExecParams{
							WorkflowRunID:          params.RunID,
							TemplateNodeID:         failedID,
							GraphVersion:           params.GraphVersion,
							Iteration:              newIteration,
							Label:                  failedNode.Label,
							IterationCapEpochStart: failedTracker.iterationCapEpochStart,
						},
					).Get(ctx, &execID); err != nil {
						logger.Error("Failed to create retry node execution", "error", err)
						return
					}

					// Persist the decision on the new execution
					if err := workflow.ExecuteActivity(dbCtx, activities.SetNodeDecision,
						activity.SetNodeDecisionParams{
							RunID:           params.RunID,
							NodeExecutionID: execID,
							Decision:        signal.Decision,
						},
					).Get(ctx, nil); err != nil {
						logger.Error("Failed to set decision on retry execution", "error", err)
						return
					}

					// Reset tracker with pre-decision
					nodes[failedID] = &nodeTracker{
						status:                 "pending",
						execID:                 execID,
						iteration:              newIteration,
						iterationCapEpochStart: failedTracker.iterationCapEpochStart,
						preDecision:            signal.Decision,
						preFeedback:            signal.Feedback,
					}

					// Restore run status
					workflow.ExecuteActivity(dbCtx, activities.UpdateWorkflowRunStatus,
						activity.UpdateRunStatusParams{RunID: params.RunID, Status: "running"},
					).Get(ctx, nil)

					workflow.ExecuteActivity(dbCtx, activities.WriteExecutionLog,
						activity.WriteExecutionLogParams{
							RunID: params.RunID, NodeExecutionID: execID, Level: "info",
							Message: fmt.Sprintf("Node retried via late human approval (iteration %d)", newIteration),
						},
					).Get(ctx, nil)
				})
			}

			retrySelector.AddReceive(cancelCh, func(ch workflow.ReceiveChannel, more bool) {
				ch.Receive(ctx, nil)
				retryTimerCancel()
				cancelled = true
			})

			retrySelector.AddFuture(retryTimeout, func(f workflow.Future) {
				if err := f.Get(ctx, nil); err == nil {
					timedOut = true
					logger.Info("Retry window expired", "runID", params.RunID)
				}
			})

			retrySelector.Select(ctx)

			if cancelled || timedOut {
				if timedOut {
					for _, failedID := range failedNodeIDs {
						failedTracker := nodes[failedID]
						workflow.ExecuteActivity(dbCtx, activities.WriteExecutionLog,
							activity.WriteExecutionLogParams{
								RunID: params.RunID, NodeExecutionID: failedTracker.execID, Level: "warn",
								Message: "Retry window expired after 7 days — finalizing run as failed",
							},
						).Get(ctx, nil)
					}
				}
				break
			}
			// Either retried successfully (node is now pending) or received an invalid
			// signal (bad UUID, non-failed node, DB error). In both cases, re-enter the
			// main loop: retried nodes will be picked up by FindReadyNodes, and invalid
			// signals let the user try again within the same 7-day window.
			continue
		}

		// 4d: Fan-out ready nodes
		for _, node := range readyNodes {
			tracker := nodes[node.TemplateNodeID]
			snapshotNode, _ := GetNodeByID(snap, node.TemplateNodeID)

			if snapshotNode.ExecutorType == "human" {
				if tracker.preDecision != "" {
					// Pre-decided via retry — follow normal lifecycle but complete immediately.
					// The decision is already saved to DB by the retry handler.
					tracker.status = "awaiting_human"

					workflow.ExecuteActivity(dbCtx, activities.UpdateNodeExecutionStatus,
						activity.UpdateNodeExecStatusParams{
							RunID: params.RunID, NodeExecutionID: tracker.execID, Status: "awaiting_human",
						},
					).Get(ctx, nil)
					workflow.ExecuteActivity(dbCtx, activities.UpdateWorkflowRunStatus,
						activity.UpdateRunStatusParams{RunID: params.RunID, Status: "awaiting_human"},
					).Get(ctx, nil)

					workflow.ExecuteActivity(dbCtx, activities.WriteExecutionLog,
						activity.WriteExecutionLogParams{
							RunID: params.RunID, NodeExecutionID: tracker.execID, Level: "info",
							Message: fmt.Sprintf("Human decision pre-applied from previous approval: %s", tracker.preDecision),
						},
					).Get(ctx, nil)

					preResult := tracker.preFeedback
					preNodeID := node.TemplateNodeID
					workflow.Go(ctx, func(gCtx workflow.Context) {
						completionCh.Send(gCtx, nodeCompletion{
							nodeID:       preNodeID,
							result:       preResult,
							artifactRefs: "{}",
						})
					})

				} else {
					// Human node — handle via workflow-level signal
					tracker.status = "awaiting_human"

					// Update DB status
					workflow.ExecuteActivity(dbCtx, activities.UpdateNodeExecutionStatus,
						activity.UpdateNodeExecStatusParams{
							RunID: params.RunID, NodeExecutionID: tracker.execID, Status: "awaiting_human",
						},
					).Get(ctx, nil)
					workflow.ExecuteActivity(dbCtx, activities.UpdateWorkflowRunStatus,
						activity.UpdateRunStatusParams{RunID: params.RunID, Status: "awaiting_human"},
					).Get(ctx, nil)

					// Log: human gate waiting
					workflow.ExecuteActivity(dbCtx, activities.WriteExecutionLog,
						activity.WriteExecutionLogParams{
							RunID: params.RunID, NodeExecutionID: tracker.execID, Level: "info",
							Message: "Waiting for human decision",
						},
					).Get(ctx, nil)

					// Spawn goroutine to wait for human decision signal on per-node channel
					execID := tracker.execID
					nodeID := node.TemplateNodeID
					timeoutSecs := snapshotNode.TimeoutSeconds
					if timeoutSecs == 0 {
						timeoutSecs = 604800 // default 7 days
					}
					humanCh := workflow.GetSignalChannel(ctx, SignalHumanDecisionPrefix+execID.String())
					workflow.Go(ctx, func(gCtx workflow.Context) {
						waitForHumanDecision(gCtx, dbCtx, humanCh, completionCh, activities, params.RunID, execID, nodeID, timeoutSecs)
					})
				}

			} else {
				// AI or script node — execute via K8s Job
				tracker.status = "running"

				vars := buildPromptVariables(snap, nodes, params.RunID, node.TemplateNodeID, tracker.iteration)

				// Load predecessor inputs — API returns transitive predecessors with labels
				var inputVars map[string]string
				workflow.ExecuteActivity(dbCtx, activities.LoadPredecessorInputs,
					activity.LoadPredecessorInputsParams{
						RunID:           params.RunID,
						NodeExecutionID: tracker.execID,
					},
				).Get(ctx, &inputVars)
				for k, v := range inputVars {
					vars[k] = v
				}

				promptTemplate := ""
				loopGroup := ""
				executorType := snapshotNode.ExecutorType

				if executorType != "script" {
					// Only AI nodes get prompts and review history
					loopGroup = extractConfigField(snapshotNode.ConfigOverrides, "loop_group")
					if loopGroup != "" {
						var reviewHistoryJSON string
						workflow.ExecuteActivity(dbCtx, activities.LoadReviewHistoryJSON,
							activity.LoadReviewHistoryJSONParams{
								RunID:     params.RunID,
								LoopGroup: loopGroup,
							},
						).Get(ctx, &reviewHistoryJSON)
						vars["review_history"] = reviewHistoryJSON
					}
					if snapshotNode.PromptTemplate != nil {
						promptTemplate = *snapshotNode.PromptTemplate
					}
				}

				// Resolve config override fields with run variables
				command := extractConfigField(snapshotNode.ConfigOverrides, "command")
				if command != "" {
					resolvedCmd, _ := prompt.NewResolver().Resolve(command, vars)
					command = resolvedCmd
				}

				repoURL := ""
				if v, ok := vars["run.repo_url"]; ok {
					repoURL = v
				}

				// Multi-repo config building
				var repos []map[string]interface{}
				needsBranch := extractConfigField(snapshotNode.ConfigOverrides, "needs_branch")

				if len(snap.Repos) > 0 {
					for _, r := range snap.Repos {
						repo := map[string]interface{}{
							"id":         r.ID,
							"url":        r.URL,
							"name":       r.Name,
							"local_path": "/workspace/repo/" + r.Name,
						}
						// Without test_command, run-all-tests has nothing to execute and the
						// AI prompts that read repos[].test_command silently no-op.
						if r.TestCommand != "" {
							repo["test_command"] = r.TestCommand
						}
						if needsBranch == "true" {
							repo["working_branch"] = "choruskube-run-" + params.RunID.String()
						}
						repos = append(repos, repo)
					}
					// Set first repo's URL as default for backwards compat
					if repoURL == "" {
						repoURL = snap.Repos[0].URL
					}
				}

				workingBranch := ""
				if repoURL != "" && needsBranch == "true" && len(repos) == 0 {
					workingBranch = "choruskube-run-" + params.RunID.String()
				}

				// Use node-level timeout from snapshot, falling back to 30 minutes
				timeoutDuration := 30 * time.Minute
				if snapshotNode.TimeoutSeconds > 0 {
					timeoutDuration = time.Duration(snapshotNode.TimeoutSeconds) * time.Second
				}
				// Scale heartbeat: min(15min, timeoutDuration) to avoid heartbeat exceeding overall timeout.
				// Agent ticks every 60s but only POSTs when the Claude stream advances, so a
				// single long-running tool call (e.g., a multi-minute test) can legitimately
				// go several minutes without a heartbeat. 15min gives that headroom while
				// still detecting true hangs well before the activity StartToCloseTimeout fires.
				heartbeatDuration := 15 * time.Minute
				if timeoutDuration < heartbeatDuration {
					heartbeatDuration = timeoutDuration
				}

				aiOpts := workflow.ActivityOptions{
					ActivityID:          tracker.execID.String(),
					StartToCloseTimeout: timeoutDuration,
					HeartbeatTimeout:    heartbeatDuration, // Agent heartbeats every 60s; scaled to node timeout
					RetryPolicy: &temporal.RetryPolicy{
						MaximumAttempts: 1, // No retries for timeout failures — wastes resources
					},
				}
				aiCtx := workflow.WithActivityOptions(ctx, aiOpts)

				execID := tracker.execID
				nodeID := node.TemplateNodeID
				future := workflow.ExecuteActivity(aiCtx, activities.ExecuteAINodeFromSnapshot,
					activity.ExecuteAINodeFromSnapshotParams{
						NodeExecutionID: execID,
						RunID:           params.RunID,
						TemplateNodeID:  nodeID,
						Label:           snapshotNode.Label,
						ExecutorType:    executorType,
						PromptTemplate:  promptTemplate,
						Model:           snapshotNode.Model,
						InputArtifacts:  runInputArtifacts,
						Variables:       vars,
						LoopGroup:       loopGroup,
						Iteration:       tracker.iteration,
						RepoURL:         repoURL,
						WorkingBranch:   workingBranch,
						Command:         command,
						OrgSlug:         params.OrgSlug,
						NeedDecision:    HasConditionalEdges(snap, node.TemplateNodeID),
						Repos:           repos,
						OutputSpec:      snapshotNode.OutputSpec,
					},
				)

				workflow.Go(ctx, func(gCtx workflow.Context) {
					var cbResult activity.CallbackResult
					err := future.Get(gCtx, &cbResult)
					result := ""
					artifactRefs := ""
					errorMessage := ""
					if err == nil {
						result = cbResult.Result
						artifactRefs = cbResult.ArtifactRefs
						errorMessage = cbResult.ErrorMessage
					}
					completionCh.Send(gCtx, nodeCompletion{
						nodeID:       nodeID,
						result:       result,
						err:          err,
						artifactRefs: artifactRefs,
						errorMessage: errorMessage,
					})
				})
			}
		}

		// 4e: Wait for any completion (or pause/resume signals)
		if runningCount > 0 || len(readyNodes) > 0 {
			selector := workflow.NewSelector(ctx)

			// Listen for node completions
			selector.AddReceive(completionCh, func(ch workflow.ReceiveChannel, more bool) {
				var completion nodeCompletion
				ch.Receive(ctx, &completion)

				tracker := nodes[completion.nodeID]
				snapshotNode, _ := GetNodeByID(snap, completion.nodeID)

				if completion.err != nil {
					// Check if this is a heartbeat timeout arriving after a deliberate pause.
					// If so, invalidate the old execution and re-queue as a fresh pending
					// execution (iteration+1) rather than entering the normal failure path.
					if _, wasPaused := pauseInterrupted[tracker.execID]; wasPaused {
						delete(pauseInterrupted, tracker.execID)
						invErr := workflow.ExecuteActivity(dbCtx, activities.UpdateNodeExecutionStatus,
							activity.UpdateNodeExecStatusParams{
								RunID:           params.RunID,
								NodeExecutionID: tracker.execID,
								Status:          "invalidated",
							},
						).Get(ctx, nil)
						if invErr != nil {
							logger.Warn("Could not invalidate paused execution; falling through to failure",
								"execID", tracker.execID, "err", invErr)
						} else {
							newIteration := tracker.iteration + 1
							var newExecID uuid.UUID
							createErr := workflow.ExecuteActivity(dbCtx, activities.CreateNodeExecution,
								activity.CreateNodeExecParams{
									WorkflowRunID:          params.RunID,
									TemplateNodeID:         completion.nodeID,
									GraphVersion:           params.GraphVersion,
									Iteration:              newIteration,
									Label:                  snapshotNode.Label,
									IterationCapEpochStart: tracker.iterationCapEpochStart,
								},
							).Get(ctx, &newExecID)
							if createErr != nil {
								logger.Error("Could not create new execution after pause-resume; node will fail",
									"nodeID", completion.nodeID, "err", createErr)
								// fall through to normal failure path
							} else {
								nodes[completion.nodeID] = &nodeTracker{
									status:                 "pending",
									execID:                 newExecID,
									iteration:              newIteration,
									iterationCapEpochStart: tracker.iterationCapEpochStart,
								}
								return // skip normal failure path; ready-nodes evaluator will re-schedule
							}
						}
					}

					// Normal failure path
					tracker.status = "failed"
					errMsg := completion.err.Error()
					workflow.ExecuteActivity(dbCtx, activities.UpdateNodeExecutionStatus,
						activity.UpdateNodeExecStatusParams{
							RunID:           params.RunID,
							NodeExecutionID: tracker.execID,
							Status:          "failed",
							ErrorMessage:    &errMsg,
						},
					).Get(ctx, nil)
					// Fetch pod logs for AI/script nodes (best-effort)
					if snapshotNode.ExecutorType != "human" {
						var podLogs string
						if err := workflow.ExecuteActivity(dbCtx, activities.FetchPodLogs,
							activity.FetchPodLogsParams{
								NodeExecutionID: tracker.execID,
								TailLines:       50,
							},
						).Get(ctx, &podLogs); err == nil && podLogs != "" && podLogs != "(no pod found)" {
							workflow.ExecuteActivity(dbCtx, activities.WriteExecutionLog,
								activity.WriteExecutionLogParams{
									RunID: params.RunID, NodeExecutionID: tracker.execID, Level: "warn",
									Message: fmt.Sprintf("Pod logs (last 50 lines):\n%s", podLogs),
								},
							).Get(ctx, nil)
						}
					}
					// Log: node failed
					workflow.ExecuteActivity(dbCtx, activities.WriteExecutionLog,
						activity.WriteExecutionLogParams{
							RunID: params.RunID, NodeExecutionID: tracker.execID, Level: "error",
							Message: fmt.Sprintf("Node failed: %s", errMsg),
						},
					).Get(ctx, nil)
					// Append to run log (non-fatal)
					workflow.ExecuteActivity(dbCtx, activities.AppendRunLog,
						activity.AppendRunLogParams{
							RunID:        params.RunID,
							OrgSlug:      params.OrgSlug,
							NodeLabel:    snapshotNode.Label,
							Status:       "failed",
							Iteration:    tracker.iteration,
							Result:       completion.result,
							ErrorMessage: completion.err.Error(),
							ArtifactRefs: completion.artifactRefs,
						},
					).Get(ctx, nil)
					// Best-effort: delete the K8s job so the pod does not linger after a
					// timeout. cleanup() is idempotent — a 404 from the api-server (when the
					// api-server already deleted the job) is treated as success.
					if snapshotNode.ExecutorType != "human" {
						if cleanErr := workflow.ExecuteActivity(dbCtx, activities.DeleteAgentJob,
							activity.DeleteAgentJobParams{NodeExecutionID: tracker.execID},
						).Get(ctx, nil); cleanErr != nil {
							// Non-fatal: log and continue; the reconciler is the safety net.
							logger.Warn("DeleteAgentJob failed after node timeout",
								"nodeID", completion.nodeID,
								"execID", tracker.execID,
								"err", cleanErr)
						}
					}
					return
				}

				// Write review history metadata (decision is already in DB via submitDecision or auto-set)
				loopGroup := extractConfigField(snapshotNode.ConfigOverrides, "loop_group")
				if loopGroup != "" {
					reviewerType := "ai"
					if snapshotNode.ExecutorType == "human" {
						reviewerType = "human"
					}
					if err := workflow.ExecuteActivity(dbCtx, activities.WriteReviewHistory,
						activity.WriteReviewHistoryParams{
							WorkflowRunID:   params.RunID,
							LoopGroup:       loopGroup,
							Iteration:       tracker.iteration,
							ReviewerType:    reviewerType,
							ArtifactRefs:    completion.artifactRefs,
							NodeExecutionID: tracker.execID,
						},
					).Get(ctx, nil); err != nil {
						tracker.status = "failed"
						errMsg := fmt.Sprintf("failed to write review history: %v", err)
						workflow.ExecuteActivity(dbCtx, activities.UpdateNodeExecutionStatus,
							activity.UpdateNodeExecStatusParams{
								RunID:           params.RunID,
								NodeExecutionID: tracker.execID,
								Status:          "failed",
								ErrorMessage:    &errMsg,
							},
						).Get(ctx, nil)
						return
					}
				}

				tracker.status = "completed"
				tracker.result = &completion.result
				if completion.errorMessage != "" {
					tracker.errorMessage = &completion.errorMessage
				}
				tracker.artifactRefs = completion.artifactRefs

				// Update node execution status in DB (human nodes aren't updated via callback)
				refs := completion.artifactRefs
				if err := workflow.ExecuteActivity(dbCtx, activities.UpdateNodeExecutionStatus,
					activity.UpdateNodeExecStatusParams{
						RunID:           params.RunID,
						NodeExecutionID: tracker.execID,
						Status:          "completed",
						Result:          &completion.result,
						ArtifactRefs:    &refs,
					},
				).Get(ctx, nil); err != nil {
					tracker.status = "failed"
					errMsg := fmt.Sprintf("failed to update node status to completed: %v", err)
					workflow.ExecuteActivity(dbCtx, activities.WriteExecutionLog,
						activity.WriteExecutionLogParams{
							RunID: params.RunID, NodeExecutionID: tracker.execID, Level: "error",
							Message: errMsg,
						},
					).Get(ctx, nil)
					return
				}

				// Restore run status to "running" after human gate completes
				if err := workflow.ExecuteActivity(dbCtx, activities.UpdateWorkflowRunStatus,
					activity.UpdateRunStatusParams{RunID: params.RunID, Status: "running"},
				).Get(ctx, nil); err != nil {
					workflow.ExecuteActivity(dbCtx, activities.WriteExecutionLog,
						activity.WriteExecutionLogParams{
							RunID: params.RunID, NodeExecutionID: tracker.execID, Level: "error",
							Message: fmt.Sprintf("failed to restore run status to running: %v", err),
						},
					).Get(ctx, nil)
				}

				// Log: human decision received
				if snapshotNode.ExecutorType == "human" {
					workflow.ExecuteActivity(dbCtx, activities.WriteExecutionLog,
						activity.WriteExecutionLogParams{
							RunID: params.RunID, NodeExecutionID: tracker.execID, Level: "info",
							Message: fmt.Sprintf("Human decision: %s", completion.result),
						},
					).Get(ctx, nil)
				}

				// 4f: Evaluate outgoing edges — always read decision from DB
				var targets []uuid.UUID
				var firedEdgeIDs []uuid.UUID
				var evalErr error
				var decision string
				if err := workflow.ExecuteActivity(dbCtx, activities.GetNodeDecision,
					activity.GetNodeDecisionParams{
						RunID:           params.RunID,
						NodeExecutionID: tracker.execID,
					},
				).Get(ctx, &decision); err != nil || decision == "" {
					tracker.status = "failed"
					errMsg := "node completed without a decision in DB"
					if err != nil {
						errMsg = fmt.Sprintf("failed to read decision: %v", err)
					}
					workflow.ExecuteActivity(dbCtx, activities.UpdateNodeExecutionStatus,
						activity.UpdateNodeExecStatusParams{
							RunID:           params.RunID,
							NodeExecutionID: tracker.execID,
							Status:          "failed",
							ErrorMessage:    &errMsg,
						},
					).Get(ctx, nil)
					workflow.ExecuteActivity(dbCtx, activities.WriteExecutionLog,
						activity.WriteExecutionLogParams{
							RunID: params.RunID, NodeExecutionID: tracker.execID, Level: "error",
							Message: errMsg,
						},
					).Get(ctx, nil)
					workflow.ExecuteActivity(dbCtx, activities.AppendRunLog,
						activity.AppendRunLogParams{
							RunID:        params.RunID,
							OrgSlug:      params.OrgSlug,
							NodeLabel:    snapshotNode.Label,
							Status:       "failed",
							Iteration:    tracker.iteration,
							Result:       completion.result,
							ErrorMessage: errMsg,
							ArtifactRefs: completion.artifactRefs,
						},
					).Get(ctx, nil)
					return
				}
				targets, firedEdgeIDs, evalErr = EvaluateEdges(snap, completion.nodeID, decision)
				if evalErr != nil {
					tracker.status = "failed"
					errMsg := evalErr.Error()
					workflow.ExecuteActivity(dbCtx, activities.UpdateNodeExecutionStatus,
						activity.UpdateNodeExecStatusParams{
							RunID:           params.RunID,
							NodeExecutionID: tracker.execID,
							Status:          "failed",
							ErrorMessage:    &errMsg,
						},
					).Get(ctx, nil)
					return
				}

				// Persist which edges fired so the web UI doesn't re-derive the rule.
				// Best-effort: a failure here only affects edge highlighting, not routing.
				if firedEdgeIDs == nil {
					firedEdgeIDs = []uuid.UUID{}
				}
				if err := workflow.ExecuteActivity(dbCtx, activities.SetTraversedEdges,
					activity.SetTraversedEdgesParams{
						RunID:           params.RunID,
						NodeExecutionID: tracker.execID,
						EdgeIDs:         firedEdgeIDs,
					},
				).Get(ctx, nil); err != nil {
					workflow.ExecuteActivity(dbCtx, activities.WriteExecutionLog,
						activity.WriteExecutionLogParams{
							RunID: params.RunID, NodeExecutionID: tracker.execID, Level: "warn",
							Message: fmt.Sprintf("failed to persist traversed edges: %v", err),
						},
					).Get(ctx, nil)
				}

				// Build target labels — needed for run log AND execution log
				var targetLabels []string
				for _, targetID := range targets {
					if tn, ok := GetNodeByID(snap, targetID); ok {
						targetLabels = append(targetLabels, tn.Label)
					}
				}

				// Log: edge evaluation
				if len(targets) > 0 {
					workflow.ExecuteActivity(dbCtx, activities.WriteExecutionLog,
						activity.WriteExecutionLogParams{
							RunID: params.RunID, NodeExecutionID: tracker.execID, Level: "info",
							Message: fmt.Sprintf("Result '%s' → activating nodes: [%s]",
								completion.result, strings.Join(targetLabels, ", ")),
						},
					).Get(ctx, nil)
				}

				// Append to run log (non-fatal)
				workflow.ExecuteActivity(dbCtx, activities.AppendRunLog,
					activity.AppendRunLogParams{
						RunID:        params.RunID,
						OrgSlug:      params.OrgSlug,
						NodeLabel:    snapshotNode.Label,
						Status:       "completed",
						Iteration:    tracker.iteration,
						Result:       completion.result,
						ErrorMessage: completion.errorMessage,
						ArtifactRefs: completion.artifactRefs,
						RoutedTo:     strings.Join(targetLabels, ", "),
					},
				).Get(ctx, nil)

				// 4g: Activate target nodes
				for _, targetID := range targets {
					existingTracker, exists := nodes[targetID]
					iteration := 1
					iterationCapEpochStart := 1
					if exists && existingTracker.status == "completed" {
						// Back-edge / loop — increment iteration
						iteration = existingTracker.iteration + 1
						// Carry epoch forward by default; reset if source was a human node
						if snapshotNode.ExecutorType == "human" {
							iterationCapEpochStart = iteration // reset: new epoch starts at this iteration
						} else {
							iterationCapEpochStart = existingTracker.iterationCapEpochStart
						}
					}

					targetNode, _ := GetNodeByID(snap, targetID)
					var execID uuid.UUID
					workflow.ExecuteActivity(dbCtx, activities.CreateNodeExecution,
						activity.CreateNodeExecParams{
							WorkflowRunID:          params.RunID,
							TemplateNodeID:         targetID,
							GraphVersion:           params.GraphVersion,
							Iteration:              iteration,
							Label:                  targetNode.Label,
							IterationCapEpochStart: iterationCapEpochStart,
						},
					).Get(ctx, &execID)

					nodes[targetID] = &nodeTracker{
						status:                 "pending",
						execID:                 execID,
						iteration:              iteration,
						iterationCapEpochStart: iterationCapEpochStart,
					}
				}
			})

			// Also listen for pause
			selector.AddReceive(pauseCh, func(ch workflow.ReceiveChannel, more bool) {
				ch.Receive(ctx, nil)
				paused = true
				workflow.ExecuteActivity(dbCtx, activities.UpdateWorkflowRunStatus,
					activity.UpdateRunStatusParams{RunID: params.RunID, Status: "paused"},
				).Get(ctx, nil)
			})

			// Also listen for cancel
			selector.AddReceive(cancelCh, func(ch workflow.ReceiveChannel, more bool) {
				ch.Receive(ctx, nil)
				cancelled = true
			})

			selector.Select(ctx)
		}
	}

	// Step 5: Cancel cleanup — mark active nodes as skipped, delete K8s Jobs
	if cancelled {
		for nodeID, tracker := range nodes {
			if tracker.status == "pending" || tracker.status == "running" || tracker.status == "awaiting_human" {
				workflow.ExecuteActivity(dbCtx, activities.UpdateNodeExecutionStatus,
					activity.UpdateNodeExecStatusParams{
						RunID: params.RunID, NodeExecutionID: tracker.execID, Status: "skipped",
					},
				).Get(ctx, nil)

				// Delete K8s Job for running AI nodes
				if tracker.status == "running" {
					if snapshotNode, ok := GetNodeByID(snap, nodeID); ok && snapshotNode.ExecutorType != "human" {
						if cleanErr := workflow.ExecuteActivity(dbCtx, activities.DeleteAgentJob,
							activity.DeleteAgentJobParams{NodeExecutionID: tracker.execID},
						).Get(ctx, nil); cleanErr != nil {
							logger.Warn("DeleteAgentJob on cancel failed (non-fatal)",
								"nodeID", nodeID, "execID", tracker.execID, "err", cleanErr)
						}
					}
				}
			}
		}
	}

	// Step 6: Mark workflow as completed/cancelled/failed
	finalStatus := "completed"
	if cancelled {
		finalStatus = "cancelled"
	} else {
		for _, tracker := range nodes {
			if tracker.status == "failed" {
				finalStatus = "failed"
				break
			}
		}
	}

	workflow.ExecuteActivity(dbCtx, activities.UpdateWorkflowRunStatus,
		activity.UpdateRunStatusParams{RunID: params.RunID, Status: finalStatus},
	).Get(ctx, nil)

	logger.Info("DAG executor finished", "runID", params.RunID, "status", finalStatus)
	return nil
}

// waitForHumanDecision blocks on the human-decision signal channel for a specific node
func waitForHumanDecision(
	ctx workflow.Context,
	dbCtx workflow.Context,
	humanCh workflow.ReceiveChannel,
	completionCh workflow.Channel,
	acts *activity.Activities,
	runID uuid.UUID,
	execID uuid.UUID,
	nodeID uuid.UUID,
	timeoutSecs int,
) {
	timerCtx, timerCancel := workflow.WithCancel(ctx)
	defer timerCancel()

	timeoutFuture := workflow.NewTimer(timerCtx, time.Duration(timeoutSecs)*time.Second)

	selector := workflow.NewSelector(ctx)

	// Listen for human decision on per-node channel
	selector.AddReceive(humanCh, func(ch workflow.ReceiveChannel, more bool) {
		var signal HumanDecisionSignal
		ch.Receive(ctx, &signal)

		timerCancel()

		// Persist decision to DB — must succeed before completion so
		// the edge-routing activity (GetNodeDecision) finds it.
		if err := workflow.ExecuteActivity(dbCtx, acts.SetNodeDecision,
			activity.SetNodeDecisionParams{
				RunID:           runID,
				NodeExecutionID: execID,
				Decision:        signal.Decision,
			},
		).Get(ctx, nil); err != nil {
			completionCh.Send(ctx, nodeCompletion{
				nodeID: nodeID,
				err:    fmt.Errorf("failed to persist decision: %w", err),
			})
			return
		}

		attachmentRefs := signal.AttachmentRefs
		if attachmentRefs == "" {
			attachmentRefs = "{}"
		}
		completionCh.Send(ctx, nodeCompletion{
			nodeID:       nodeID,
			result:       signal.Feedback,
			artifactRefs: attachmentRefs,
		})
	})

	// Listen for timeout
	selector.AddFuture(timeoutFuture, func(f workflow.Future) {
		err := f.Get(ctx, nil)
		if err == nil {
			completionCh.Send(ctx, nodeCompletion{
				nodeID: nodeID,
				err:    fmt.Errorf("human gate timed out after %ds", timeoutSecs),
			})
		}
	})

	selector.Select(ctx)
}

// buildPromptVariables builds the flat variable map for prompt template resolution.
// DB-backed variables (input.*, review_history) are loaded via activities in the main loop.
func buildPromptVariables(
	snap *state.GraphRuntimeSnapshot,
	nodes map[uuid.UUID]*nodeTracker,
	runID uuid.UUID,
	nodeID uuid.UUID,
	iteration int,
) map[string]string {
	vars := map[string]string{
		"run.id":    runID.String(),
		"iteration": fmt.Sprintf("%d", iteration),
	}

	node, ok := GetNodeByID(snap, nodeID)
	if ok {
		vars["node.label"] = node.Label
	}

	// Populate run.* variables from snapshot inputs
	for k, v := range snap.Inputs {
		switch val := v.(type) {
		case string:
			vars["run."+k] = val
		default:
			if b, err := json.Marshal(val); err == nil {
				vars["run."+k] = string(b)
			}
		}
	}

	return vars
}

// extractConfigField extracts a string field from config overrides map
func extractConfigField(overrides map[string]interface{}, key string) string {
	if overrides == nil {
		return ""
	}
	if v, ok := overrides[key]; ok {
		return fmt.Sprintf("%v", v)
	}
	return ""
}
