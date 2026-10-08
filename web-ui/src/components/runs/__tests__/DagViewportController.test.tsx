import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render } from "@testing-library/react";
import { createRef } from "react";
import DagViewportController from "../DagViewportController";

let mockWidth = 1000;
let mockHeight = 600;
let mockNodesInitialized = true;
const setViewport = vi.fn();
const getNodes = vi.fn(() => [{ id: "a" }, { id: "b" }]);
const getNodesBounds = vi.fn(() => ({ x: 0, y: 0, width: 400, height: 200 }));

vi.mock("@xyflow/react", () => ({
  useReactFlow: () => ({
    getNodes,
    getNodesBounds,
    setViewport,
  }),
  useNodesInitialized: () => mockNodesInitialized,
  useStore: (selector: (s: { width: number; height: number }) => unknown) =>
    selector({ width: mockWidth, height: mockHeight }),
}));

function renderController(props: Partial<Parameters<typeof DagViewportController>[0]> = {}) {
  const userMovedRef = props.userMovedRef ?? createRef<boolean>();
  if (userMovedRef.current === undefined || userMovedRef.current === null) {
    (userMovedRef as React.MutableRefObject<boolean>).current = false;
  }
  const onApplied = (props.onApplied as ReturnType<typeof vi.fn>) ?? vi.fn();
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
  mockWidth = 1000;
  mockHeight = 600;
  mockNodesInitialized = true;
  getNodes.mockReturnValue([{ id: "a" }, { id: "b" }]);
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
  });

  it("re-applies on a pane-size change (debounced) while the user hasn't moved the view", () => {
    const { rerender, userMovedRef, onApplied } = renderController();
    expect(setViewport).toHaveBeenCalledTimes(1);

    mockWidth = 1200;
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
    mockWidth = 1200;
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
  });

  it("applies nothing and never calls onApplied for a zero-size pane", () => {
    mockWidth = 0;
    const { onApplied } = renderController();

    expect(setViewport).not.toHaveBeenCalled();
    expect(onApplied).not.toHaveBeenCalled();
  });
});
