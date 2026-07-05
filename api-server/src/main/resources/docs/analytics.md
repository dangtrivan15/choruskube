<!-- DO NOT add a # H1 heading here — the title comes from index.json and is rendered by the web UI -->

## Overview of the Analytics Dashboard

The **Analytics** page provides observability into your ChorusKube workflows — how many runs are
happening, how fast they complete, and where time is being spent. Use it to identify bottlenecks,
track improvement over time, and make informed decisions about workflow design.

Navigate to **Analytics** in the left sidebar to open the dashboard.

## Period Selector

The **Period Selector** at the top of the dashboard controls the time range for all charts and
tables. Available options:

| Period | Description |
|---|---|
| Last 7 days | Rolling 7-day window ending now |
| Last 30 days | Rolling 30-day window |
| Last 90 days | Quarterly view |
| Custom range | Pick specific start and end dates |

Changing the period updates all charts and tables instantly without reloading the page.

## Run Volume Trends

The **Run Volume** chart shows the number of workflow runs started and completed per day (or per
week for longer periods). Use this chart to:

- Spot days with unusually high or low activity
- Correlate run spikes with sprints, releases, or on-call incidents
- Track adoption growth over time

The chart distinguishes between **started** and **completed** runs — a large gap may indicate
runs are stalling or being cancelled.

## Throughput Metrics

The **Throughput** section shows:

- **Average run duration**: mean wall-clock time from run start to completion
- **Median run duration**: p50 latency, less affected by outlier long runs
- **P95 run duration**: the 95th-percentile latency — useful for SLA tracking
- **Completion rate**: percentage of started runs that reach a terminal success state

A rising P95 while median stays flat often indicates a specific workflow path is degrading.

## Bottleneck Detection

### How Bottlenecks Are Identified

The **Bottleneck Detection** table ranks node types by their median execution time across all
runs in the selected period. Node types that consume the most wall-clock time appear at the top.

### Interpreting the Bottleneck Table

| Column | Meaning |
|---|---|
| Node type | The category of node (AI Agent, Human Gate, Script, etc.) |
| Median duration | p50 execution time across all matching node executions |
| P95 duration | 95th-percentile execution time |
| Run count | Number of node executions included in the calculation |

**Human Gate** nodes will naturally appear high in the table because they wait for human input.
This is expected; focus optimization effort on **AI Agent** and **Script** nodes with high P95 values.

## Per-Node Performance

Click any row in the Bottleneck table to drill down into the **Per-Node Performance** view, which
shows:

- A histogram of execution times for that node type
- The 10 slowest individual executions (with links to their run monitors)
- Trend over time — is the node getting faster or slower?

## Using Analytics to Improve Workflows

| Observation | Suggested action |
|---|---|
| AI Agent node P95 > 30 minutes | Review agent prompts — overly broad scope causes long runs |
| High human gate wait time | Notify reviewers more aggressively; simplify gate prompts |
| Completion rate < 80% | Investigate failure patterns — check error logs on failed nodes |
| Run volume dropping | Check for provisioning failures or authentication issues |
| Median duration increasing week-over-week | Review recent template changes; consider adding a checkpoint gate |
