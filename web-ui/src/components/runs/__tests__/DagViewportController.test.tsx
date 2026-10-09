import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render } from "@testing-library/react";
import type { RefObject } from "react";
import DagViewportController from "../DagViewportController";

interface MockInternalNode {
  measured: { width?: number; height?: number };
}

// The store shape the controller reads, both through `useStore` selectors (to re-render) and
// `useStoreApi().getState()` (at apply time). `nodeLookup` holds React Flow's *internal* nodes,
// whose `measured` is what React Flow fills in — unlike the caller's node objects.
const mockState = {
  width: 1000,
  height: 600,
  nodeLookup: new Map<string, MockInternalNode>(),
};
const setViewport = vi.fn();
const getNodesBounds = vi.fn(() => ({ x: 0, y: 0, width: 400, height: 200 }));

function measuredNodes(...ids: string[]): Map<string, MockInternalNode> {
  return new Map(ids.map((id) => [id, { measured: { width: 160, height: 60 } }]));
}

vi.mock("@xyflow/react", () => ({
  useReactFlow: () => ({ getNodesBounds, setViewport }),
  useStoreApi: () => ({ getState: () => mockState }),
  useStore: (selector: (s: typeof mockState) => unknown) => selector(mockState),
}));

interface RenderControllerProps {
  resetKey?: string;
  focusNodeId?: string | null;
  compact?: boolean;
  userMovedRef?: RefObject<boolean>;
  onApplied?: (resetKey: string) => void;
}

function renderController(props: RenderControllerProps = {}) {
  const userMovedRef: RefObject<boolean> = props.userMovedRef ?? { current: false };
  const onApplied = props.onApplied ?? vi.fn();
  const utils = render(
    <DagViewportController
      resetKey={props.resetKey ?? "run-1#topo-1"}
      focusNodeId={props.focusNodeId ?? null}
      compact={props.compact ?? false}
      userMovedRef={userMovedRef}
      onApplied={onApplied}
    />,
  );
  return { ...utils, userMovedRef, onApplied };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockState.width = 1000;
  mockState.height = 600;
  mockState.nodeLookup = measuredNodes("a", "b");
  getNodesBounds.mockReturnValue({ x: 0, y: 0, width: 400, height: 200 });
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("DagViewportController", () => {
  it("applies once after nodes initialize and calls onApplied once", () => {
    const { onApplied } = renderController();

    expect(setViewport).toHaveBeenCalledTimes(1);
    expect(onApplied).toHaveBeenCalledTimes(1);
    expect(onApplied).toHaveBeenCalledWith("run-1#topo-1");
  });

  it("applies nothing until every node is measured, then applies when they are", () => {
    mockState.nodeLookup = new Map([
      ["a", { measured: { width: 160, height: 60 } }],
      ["b", { measured: {} }],
    ]);
    const { rerender, userMovedRef, onApplied } = renderController();
    expect(setViewport).not.toHaveBeenCalled();
    expect(onApplied).not.toHaveBeenCalled();

    mockState.nodeLookup = measuredNodes("a", "b");
    rerender(
      <DagViewportController
        resetKey="run-1#topo-1"
        focusNodeId={null}
        compact={false}
        userMovedRef={userMovedRef}
        onApplied={onApplied}
      />,
    );

    expect(setViewport).toHaveBeenCalledTimes(1);
    expect(onApplied).toHaveBeenCalledTimes(1);
  });

  it("re-applies on a pane-size change (debounced) while the user hasn't moved the view", () => {
    const { rerender, userMovedRef, onApplied } = renderController();
    expect(setViewport).toHaveBeenCalledTimes(1);

    mockState.width = 1200;
    rerender(
      <DagViewportController
        resetKey="run-1#topo-1"
        focusNodeId={null}
        compact={false}
        userMovedRef={userMovedRef}
        onApplied={onApplied}
      />,
    );

    // Not yet — debounced.
    expect(setViewport).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(150);
    expect(setViewport).toHaveBeenCalledTimes(2);
  });

  it("does not re-apply once the user has moved the view", () => {
    const { rerender, userMovedRef, onApplied } = renderController();
    expect(setViewport).toHaveBeenCalledTimes(1);

    userMovedRef.current = true;
    mockState.width = 1200;
    rerender(
      <DagViewportController
        resetKey="run-1#topo-1"
        focusNodeId={null}
        compact={false}
        userMovedRef={userMovedRef}
        onApplied={onApplied}
      />,
    );
    vi.advanceTimersByTime(150);

    expect(setViewport).toHaveBeenCalledTimes(1);
  });

  it("applies again for a new resetKey with the same nodes (the same-template run switch)", () => {
    const { rerender, userMovedRef, onApplied } = renderController({ resetKey: "run-1#topo-1" });
    expect(setViewport).toHaveBeenCalledTimes(1);
    expect(onApplied).toHaveBeenCalledTimes(1);

    rerender(
      <DagViewportController
        resetKey="run-2#topo-1"
        focusNodeId={null}
        compact={false}
        userMovedRef={userMovedRef}
        onApplied={onApplied}
      />,
    );

    expect(setViewport).toHaveBeenCalledTimes(2);
    expect(onApplied).toHaveBeenCalledTimes(2);
    expect(onApplied).toHaveBeenLastCalledWith("run-2#topo-1");
  });

  it("a new resetKey clears a user move left over from the previous run and applies", () => {
    const { rerender, userMovedRef, onApplied } = renderController({ resetKey: "run-1#topo-1" });
    expect(setViewport).toHaveBeenCalledTimes(1);

    userMovedRef.current = true;
    rerender(
      <DagViewportController
        resetKey="run-2#topo-1"
        focusNodeId={null}
        compact={false}
        userMovedRef={userMovedRef}
        onApplied={onApplied}
      />,
    );

    expect(userMovedRef.current).toBe(false);
    expect(setViewport).toHaveBeenCalledTimes(2);
  });

  it("falls back to no focus when the focus node is not on the canvas", () => {
    renderController({ focusNodeId: "missing", compact: true });

    expect(getNodesBounds).toHaveBeenCalledTimes(1);
    expect(getNodesBounds).toHaveBeenCalledWith(["a", "b"]);
    expect(setViewport).toHaveBeenCalledTimes(1);
  });

  it("applies nothing and never calls onApplied for a zero-size pane", () => {
    mockState.width = 0;
    const { onApplied } = renderController();

    expect(setViewport).not.toHaveBeenCalled();
    expect(onApplied).not.toHaveBeenCalled();
  });
});
