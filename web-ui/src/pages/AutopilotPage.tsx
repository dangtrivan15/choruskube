import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import { formatDistanceToNow } from "date-fns";
import { Toggle } from "@base-ui/react/toggle";
import { Bot, AlertTriangle } from "lucide-react";
import PageHeader from "@/components/layout/PageHeader";
import PageShell from "@/components/layout/PageShell";
import { Card, CardHeader, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import ErrorAlert from "@/components/ui/ErrorAlert";
import {
  useAutopilot,
  useUpdateAutopilot,
  useEngageAutopilot,
  useDisengageAutopilot,
  useTickAutopilot,
  useAutopilotSubscription,
} from "@/hooks/useAutopilot";
import type { AutopilotTaskRef } from "@/lib/types";
import { cn } from "@/lib/utils";

interface TaskRefListProps {
  testId: string;
  refs: AutopilotTaskRef[];
  emptyLabel: string;
}

/** Shared renderer for the three `AutopilotTaskRef` lists (nextUp / awaitingYou / needsAttention).
 *  Links to the run when one exists (a task already started), else to the Task itself. */
function TaskRefList({ testId, refs, emptyLabel }: TaskRefListProps) {
  if (refs.length === 0) {
    return (
      <p data-testid={testId} className="text-sm text-muted-foreground">
        {emptyLabel}
      </p>
    );
  }
  return (
    <ul data-testid={testId} className="flex flex-col gap-1.5">
      {refs.map((ref) => (
        <li
          key={ref.taskId}
          className="flex items-center justify-between gap-2 rounded-md border border-border px-2.5 py-1.5 text-sm"
        >
          <Link
            to={ref.runId ? `/runs/${ref.runId}` : `/tasks/${ref.taskId}`}
            className="truncate font-medium hover:underline"
          >
            {ref.title}
          </Link>
          <Badge variant="outline" className="shrink-0">
            {ref.status}
          </Badge>
        </li>
      ))}
    </ul>
  );
}

/**
 * The Autopilot control surface (spec §10): an engage/disengage toggle, the `maxParallel`
 * knob, a manual tick, and the live status panel. "Why idle" is the trust-critical field —
 * an unattended dispatcher that has stopped for a structural reason (no ready Tasks, at
 * capacity, disengaged) must read differently from one that has silently died.
 */
export default function AutopilotPage() {
  const { data: status, isLoading, isError } = useAutopilot();
  const updateMut = useUpdateAutopilot();
  const engageMut = useEngageAutopilot();
  const disengageMut = useDisengageAutopilot();
  const tickMut = useTickAutopilot();
  useAutopilotSubscription();

  // Local echo of maxParallel so the field is editable without re-rendering on every
  // keystroke against the query cache. Only re-synced from the server value while the
  // input isn't focused, so a live STOMP update (or the 15s poll) can't clobber an
  // in-progress edit.
  const [maxParallelInput, setMaxParallelInput] = useState("1");
  const inputFocused = useRef(false);

  useEffect(() => {
    if (status && !inputFocused.current) {
      setMaxParallelInput(String(status.maxParallel));
    }
  }, [status]);

  function commitMaxParallel() {
    const parsed = Number.parseInt(maxParallelInput, 10);
    if (!status || !Number.isInteger(parsed) || parsed < 1) {
      setMaxParallelInput(String(status?.maxParallel ?? 1));
      return;
    }
    if (parsed !== status.maxParallel) {
      updateMut.mutate({ maxParallel: parsed });
    }
  }

  if (isLoading) {
    return (
      <PageShell>
        <PageHeader title="Autopilot" />
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-64 w-full" />
      </PageShell>
    );
  }

  if (isError || !status) {
    return (
      <PageShell>
        <PageHeader title="Autopilot" />
        <ErrorAlert message="Failed to load Autopilot status." />
      </PageShell>
    );
  }

  return (
    <PageShell>
      <PageHeader title="Autopilot">
        <Toggle
          data-testid="autopilot-toggle"
          aria-label={status.engaged ? "Disengage Autopilot" : "Engage Autopilot"}
          pressed={status.engaged}
          onPressedChange={(pressed) => (pressed ? engageMut.mutate() : disengageMut.mutate())}
          disabled={engageMut.isPending || disengageMut.isPending}
          className={cn(
            "inline-flex h-8 shrink-0 items-center gap-1.5 rounded-lg border border-border bg-background px-2.5 text-sm font-medium text-muted-foreground transition-all outline-none select-none hover:bg-muted hover:text-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50",
            "data-[pressed]:border-transparent data-[pressed]:bg-primary data-[pressed]:text-primary-foreground"
          )}
        >
          <Bot className="size-4" />
          {status.engaged ? "Engaged" : "Disengaged"}
        </Toggle>
      </PageHeader>

      {status.disengagedReason && (
        <div
          data-testid="autopilot-disengaged-banner"
          role="alert"
          className="flex items-start gap-2 rounded-lg border border-status-warning/50 bg-status-warning/10 p-4 text-sm text-status-warning"
        >
          <AlertTriangle className="size-4 shrink-0 translate-y-0.5" />
          <span>Autopilot disengaged itself: {status.disengagedReason}</span>
        </div>
      )}

      <Card>
        <CardHeader className="text-sm font-medium">Configuration</CardHeader>
        <CardContent className="flex flex-wrap items-end gap-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="autopilot-max-parallel" className="text-xs text-muted-foreground">
              Max parallel
            </label>
            <Input
              id="autopilot-max-parallel"
              data-testid="autopilot-max-parallel"
              type="number"
              min={1}
              className="w-24"
              value={maxParallelInput}
              onChange={(e) => setMaxParallelInput(e.target.value)}
              onFocus={() => {
                inputFocused.current = true;
              }}
              onBlur={() => {
                inputFocused.current = false;
                commitMaxParallel();
              }}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.currentTarget.blur();
                }
              }}
            />
          </div>
          <Button
            data-testid="autopilot-tick"
            variant="outline"
            onClick={() => tickMut.mutate()}
            disabled={tickMut.isPending}
          >
            {tickMut.isPending ? "Ticking..." : "Run tick now"}
          </Button>
          {status.lastTickAt && (
            <p className="text-xs text-muted-foreground">
              Last tick {formatDistanceToNow(new Date(status.lastTickAt), { addSuffix: true })}
            </p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="text-sm font-medium">Status</CardHeader>
        <CardContent className="flex flex-col gap-5">
          <div data-testid="autopilot-in-flight" className="flex items-center gap-2 text-sm">
            <span className="font-medium">{status.inFlight}</span>
            <span className="text-muted-foreground">
              of {status.maxParallel} slot(s) in use — {status.slots} free
            </span>
            {status.consecutiveFailures > 0 && (
              <Badge variant="destructive">{status.consecutiveFailures} consecutive failure(s)</Badge>
            )}
          </div>

          <section className="flex flex-col gap-1.5">
            <h2 className="text-xs font-semibold uppercase text-muted-foreground">Next up</h2>
            <TaskRefList
              testId="autopilot-next-up"
              refs={status.nextUp}
              emptyLabel="Nothing queued to start next."
            />
          </section>

          <section className="flex flex-col gap-1.5">
            <h2 className="text-xs font-semibold uppercase text-muted-foreground">Why idle</h2>
            {status.whyIdle.length === 0 ? (
              <p data-testid="autopilot-why-idle" className="text-sm text-muted-foreground">
                Not idle — actively working.
              </p>
            ) : (
              <ul data-testid="autopilot-why-idle" className="flex flex-col gap-1 text-sm text-muted-foreground">
                {status.whyIdle.map((reason, i) => (
                  <li key={i}>{reason}</li>
                ))}
              </ul>
            )}
          </section>

          <section className="flex flex-col gap-1.5">
            <h2 className="text-xs font-semibold uppercase text-muted-foreground">Awaiting you</h2>
            <TaskRefList
              testId="autopilot-awaiting-you"
              refs={status.awaitingYou}
              emptyLabel="Nothing parked on a human right now."
            />
          </section>

          <section className="flex flex-col gap-1.5">
            <h2 className="text-xs font-semibold uppercase text-muted-foreground">Needs attention</h2>
            <TaskRefList
              testId="autopilot-needs-attention"
              refs={status.needsAttention}
              emptyLabel="No failed runs awaiting retry."
            />
          </section>
        </CardContent>
      </Card>
    </PageShell>
  );
}
