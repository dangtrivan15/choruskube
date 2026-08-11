import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ComponentType } from "react";
import { renderWithProviders } from "@/__tests__/test-utils";
import type { RoadmapTimelineResponse, TimelineEpicSummary, TimelineStorySummary } from "@/lib/types";
import { TIMELINE_AXIS_ORIGIN_X, TIMELINE_LANE_HEIGHT } from "@/lib/timelineLayout";

// setCenter is asserted against directly, so it lives outside the mock factory (vi.mock's factory
// runs before module-scope `const`s in this file are initialized, but a `vi.fn()` captured by
// reference inside the factory and reset in beforeEach works the same way RoadmapGraph.test.tsx's
// mock captures onNodeClick/onPaneClick from the real component's props).
const setCenterMock = vi.fn();

// Mock @xyflow/react for the same reason RoadmapGraph.test.tsx does (real ReactFlow's d3-zoom/
// d3-drag pointer handlers crash happy-dom) — this mock renders our own node components directly
// (unmocked) inside plain clickable divs, so RoadmapTimeline's own onNodeClick/focus-centering
// logic all still executes for real, and fires `onInit` with a stub instance so the pan-to-focus
// effect has something to call `setCenter` on.
vi.mock("@xyflow/react", async () => {
  const react = await import("react");
  return {
    ReactFlow: ({
      nodes,
      nodeTypes,
      onNodeClick,
      onInit,
    }: {
      nodes: { id: string; type: string; data: unknown }[];
      nodeTypes: Record<string, ComponentType<{ id: string; data: unknown }>>;
      onNodeClick?: (event: unknown, node: { id: string; type: string; data: unknown }) => void;
      onInit?: (instance: { setCenter: typeof setCenterMock }) => void;
    }) => {
      react.useEffect(() => {
        onInit?.({ setCenter: setCenterMock });
      }, [onInit]);
      return (
        <div data-testid="mock-react-flow-pane">
          {nodes.map((n) => {
            const Comp = nodeTypes[n.type];
            return (
              <div key={n.id} data-testid={`mock-node-${n.id}`} onClick={(e) => onNodeClick?.(e, n)}>
                <Comp id={n.id} data={n.data} />
              </div>
            );
          })}
        </div>
      );
    },
    Controls: () => null,
    Background: () => null,
  };
});

import RoadmapTimeline from "@/components/roadmap/RoadmapTimeline";

function makeStory(overrides: Partial<TimelineStorySummary> = {}): TimelineStorySummary {
  return {
    id: "story-1",
    epicId: "epic-1",
    title: "Story",
    stage: "backlog",
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    readiness: "READY",
    stalled: false,
    ...overrides,
  };
}

function makeEpic(overrides: Partial<TimelineEpicSummary> = {}): TimelineEpicSummary {
  return {
    id: "epic-1",
    title: "Epic",
    stage: "backlog",
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    stories: [],
    stalled: false,
    ...overrides,
  };
}

beforeEach(() => {
  setCenterMock.mockClear();
});

describe("RoadmapTimeline", () => {
  it("clicking an Epic lane node calls onFocusChange with just the epicId", async () => {
    const onFocusChange = vi.fn();
    const data: RoadmapTimelineResponse = { epics: [makeEpic({ id: "epic-1" })] };
    renderWithProviders(<RoadmapTimeline data={data} onFocusChange={onFocusChange} />);
    const user = userEvent.setup();

    await user.click(screen.getByTestId("mock-node-epic-1"));

    expect(onFocusChange).toHaveBeenCalledWith("epic-1");
    expect(onFocusChange).toHaveBeenCalledTimes(1);
  });

  it("clicking a Story node calls onFocusChange with the epic id and story id", async () => {
    const onFocusChange = vi.fn();
    const data: RoadmapTimelineResponse = {
      epics: [makeEpic({ id: "epic-1", stories: [makeStory({ id: "story-1", epicId: "epic-1" })] })],
    };
    renderWithProviders(<RoadmapTimeline data={data} onFocusChange={onFocusChange} />);
    const user = userEvent.setup();

    await user.click(screen.getByTestId("mock-node-story-1"));

    expect(onFocusChange).toHaveBeenCalledWith("epic-1", "story-1");
  });

  it("does not throw when a node is clicked and no onFocusChange is provided", async () => {
    const data: RoadmapTimelineResponse = { epics: [makeEpic({ id: "epic-1" })] };
    renderWithProviders(<RoadmapTimeline data={data} />);
    const user = userEvent.setup();

    await expect(user.click(screen.getByTestId("mock-node-epic-1"))).resolves.not.toThrow();
  });

  it("centers on the focused Story node's position once the instance is ready", async () => {
    const data: RoadmapTimelineResponse = {
      epics: [makeEpic({ id: "epic-1", stories: [makeStory({ id: "story-1", epicId: "epic-1" })] })],
    };
    renderWithProviders(<RoadmapTimeline data={data} focusedEpicId="epic-1" focusedStoryId="story-1" />);

    // Single Story in the whole response ⇒ buildTimeScale anchors it at the axis origin (no time
    // range to scale against); lane index 0 ⇒ y=0.
    expect(setCenterMock).toHaveBeenCalledWith(TIMELINE_AXIS_ORIGIN_X, 0, { zoom: 1, duration: 400 });
  });

  it("centers on the focused Epic's lane when only an epicId is focused (no Story)", async () => {
    const data: RoadmapTimelineResponse = {
      epics: [makeEpic({ id: "epic-1" }), makeEpic({ id: "epic-2" })],
    };
    renderWithProviders(<RoadmapTimeline data={data} focusedEpicId="epic-2" />);

    expect(setCenterMock).toHaveBeenCalledWith(0, TIMELINE_LANE_HEIGHT, { zoom: 1, duration: 400 });
  });

  it("does not call setCenter when the focused id isn't present in the current layout", async () => {
    const data: RoadmapTimelineResponse = { epics: [makeEpic({ id: "epic-1" })] };
    renderWithProviders(<RoadmapTimeline data={data} focusedEpicId="epic-does-not-exist" />);

    expect(setCenterMock).not.toHaveBeenCalled();
  });

  it("does not call setCenter when nothing is focused", async () => {
    const data: RoadmapTimelineResponse = { epics: [makeEpic({ id: "epic-1" })] };
    renderWithProviders(<RoadmapTimeline data={data} />);

    expect(setCenterMock).not.toHaveBeenCalled();
  });

  describe("blocked/stalled risk badges", () => {
    it("shows the blocked badge and data-risk=blocked for a blocked Story, no stalled badge", () => {
      const data: RoadmapTimelineResponse = {
        epics: [
          makeEpic({
            id: "epic-1",
            stories: [makeStory({ id: "story-1", readiness: "BLOCKED", stalled: false })],
          }),
        ],
      };
      renderWithProviders(<RoadmapTimeline data={data} />);

      expect(screen.getByTestId("roadmap-timeline-story-blocked-badge")).toBeInTheDocument();
      expect(screen.queryByTestId("roadmap-timeline-story-stalled-badge")).not.toBeInTheDocument();
      expect(screen.getByTestId("roadmap-timeline-story-node")).toHaveAttribute("data-risk", "blocked");
    });

    it("shows the stalled badge and data-risk=stalled for a stalled Story, no blocked badge", () => {
      const data: RoadmapTimelineResponse = {
        epics: [
          makeEpic({
            id: "epic-1",
            stories: [makeStory({ id: "story-1", readiness: "READY", stalled: true })],
          }),
        ],
      };
      renderWithProviders(<RoadmapTimeline data={data} />);

      expect(screen.getByTestId("roadmap-timeline-story-stalled-badge")).toBeInTheDocument();
      expect(screen.queryByTestId("roadmap-timeline-story-blocked-badge")).not.toBeInTheDocument();
      expect(screen.getByTestId("roadmap-timeline-story-node")).toHaveAttribute("data-risk", "stalled");
    });

    it("shows both badges and data-risk=blocked-stalled for a Story that is both", () => {
      const data: RoadmapTimelineResponse = {
        epics: [
          makeEpic({
            id: "epic-1",
            stories: [makeStory({ id: "story-1", readiness: "BLOCKED", stalled: true })],
          }),
        ],
      };
      renderWithProviders(<RoadmapTimeline data={data} />);

      expect(screen.getByTestId("roadmap-timeline-story-blocked-badge")).toBeInTheDocument();
      expect(screen.getByTestId("roadmap-timeline-story-stalled-badge")).toBeInTheDocument();
      expect(screen.getByTestId("roadmap-timeline-story-node")).toHaveAttribute("data-risk", "blocked-stalled");
    });

    it("shows neither badge and data-risk=none for an on-track Story", () => {
      const data: RoadmapTimelineResponse = {
        epics: [
          makeEpic({
            id: "epic-1",
            stories: [makeStory({ id: "story-1", readiness: "READY", stalled: false })],
          }),
        ],
      };
      renderWithProviders(<RoadmapTimeline data={data} />);

      expect(screen.queryByTestId("roadmap-timeline-story-blocked-badge")).not.toBeInTheDocument();
      expect(screen.queryByTestId("roadmap-timeline-story-stalled-badge")).not.toBeInTheDocument();
      expect(screen.getByTestId("roadmap-timeline-story-node")).toHaveAttribute("data-risk", "none");
    });

    it("aggregates risk onto the Epic lane node from its Stories", () => {
      const data: RoadmapTimelineResponse = {
        epics: [
          makeEpic({
            id: "epic-1",
            stalled: false,
            stories: [makeStory({ id: "story-1", readiness: "BLOCKED", stalled: true })],
          }),
        ],
      };
      renderWithProviders(<RoadmapTimeline data={data} />);

      expect(screen.getByTestId("roadmap-timeline-epic-blocked-badge")).toBeInTheDocument();
      expect(screen.getByTestId("roadmap-timeline-epic-stalled-badge")).toBeInTheDocument();
      expect(screen.getByTestId("roadmap-timeline-epic-lane")).toHaveAttribute("data-risk", "blocked-stalled");
    });
  });
});
