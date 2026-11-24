import { memo, useCallback, useEffect, useMemo, useState, type KeyboardEvent } from "react";
import {
  BaseEdge,
  EdgeLabelRenderer,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  getBezierPath,
  useReactFlow,
  type Connection,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { X } from "lucide-react";
import {
  REPO_NODE_HEIGHT,
  REPO_NODE_WIDTH,
  addArrow,
  cycleThrough,
  layoutRepoDependencies,
  removeArrow,
  toArrows,
  type DependencyArrow,
} from "@/lib/repoDependencyGraph";
import type { RepoDependency, RepoGroupMember } from "@/lib/types";

interface RepoNodeData {
  name: string;
  [key: string]: unknown;
}

interface DependencyEdgeData {
  label: string;
  onRemove: () => void;
  [key: string]: unknown;
}

type RepoNodeType = Node<RepoNodeData, "repo">;
type DependencyEdgeType = Edge<DependencyEdgeData, "dependency">;

// Lets Enter or Space on a dot do what a click does, so an arrow can be drawn from the keyboard.
// React Flow resolves the target handle of a click-connect via `elementFromPoint` at the
// native event's clientX/Y, not the DOM node the click fired on — a coordinate-less
// `.click()` resolves against whatever sits at (0,0) instead of this handle, so dispatch a
// click carrying the handle's own center point.
function clickOnEnter(event: KeyboardEvent<HTMLDivElement>) {
  if (event.key === "Enter" || event.key === " ") {
    event.preventDefault();
    const { left, top, width, height } = event.currentTarget.getBoundingClientRect();
    event.currentTarget.dispatchEvent(
      new MouseEvent("click", {
        bubbles: true,
        cancelable: true,
        clientX: left + width / 2,
        clientY: top + height / 2,
      }),
    );
  }
}

const dotClass =
  "!size-2.5 !border-background focus-visible:!outline-2 focus-visible:!outline-ring [&.clickconnecting]:!outline-2 [&.clickconnecting]:!outline-primary";

// An arrow leaves the dependant's bottom and enters the dependency's top, matching the layout,
// which draws dependencies below the repos that depend on them.
const RepoNode = memo(function RepoNode({ id, data }: NodeProps<RepoNodeType>) {
  return (
    <>
      <Handle
        id="in"
        type="target"
        position={Position.Top}
        data-testid={`repo-dependency-in-${id}`}
        tabIndex={0}
        role="button"
        aria-label={`…depends on ${data.name}`}
        onKeyDown={clickOnEnter}
        className={`${dotClass} !bg-muted-foreground`}
      />
      <div
        style={{ width: REPO_NODE_WIDTH, height: REPO_NODE_HEIGHT }}
        className="flex items-center justify-center rounded-md border bg-card px-2 text-sm text-foreground shadow-sm"
      >
        <span className="truncate" title={data.name}>
          {data.name}
        </span>
      </div>
      <Handle
        id="out"
        type="source"
        position={Position.Bottom}
        data-testid={`repo-dependency-out-${id}`}
        tabIndex={0}
        role="button"
        aria-label={`${data.name} depends on…`}
        onKeyDown={clickOnEnter}
        className={`${dotClass} !bg-primary`}
      />
    </>
  );
});

const DependencyEdge = memo(function DependencyEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  data,
}: EdgeProps<DependencyEdgeType>) {
  const [path, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
  });
  return (
    <>
      <BaseEdge id={id} path={path} markerEnd={markerEnd} />
      {data && (
        <EdgeLabelRenderer>
          <button
            type="button"
            aria-label={`Remove: ${data.label}`}
            title={`Remove: ${data.label}`}
            onClick={data.onRemove}
            style={{
              position: "absolute",
              transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
              pointerEvents: "all",
            }}
            className="nodrag nopan flex size-5 items-center justify-center rounded-full border bg-background text-muted-foreground shadow-sm hover:text-destructive"
          >
            <X className="size-3" />
          </button>
        </EdgeLabelRenderer>
      )}
    </>
  );
});

const nodeTypes = { repo: RepoNode };
const edgeTypes = { dependency: DependencyEdge };

// fitView on ReactFlow only fits the first render; a new arrow can add a layer, so refit each
// time a layout lands.
function FitOnLayout({ positions }: { positions: Map<string, { x: number; y: number }> }) {
  const { fitView } = useReactFlow();
  useEffect(() => {
    void fitView({ padding: 0.2, maxZoom: 1 });
  }, [fitView, positions]);
  return null;
}

/**
 * Returns `value` as first seen until `key` changes. A host may rebuild these props on every
 * render, and React Flow re-measures, and briefly hides, any node whose object changed.
 */
function useHeldUntilChanged<T>(value: T, key: string): T {
  const [held, setHeld] = useState({ key, value });
  if (held.key !== key) {
    setHeld({ key, value });
    return value;
  }
  return held.value;
}

interface Props {
  members: RepoGroupMember[];
  dependencies: RepoDependency[];
  onChange: (dependencies: RepoDependency[]) => void;
}

/**
 * Canvas for a repo group's dependencies: each repo is a box, and an arrow from A to B says
 * A depends on B. Positions come from the layout, never from the user, so the picture always
 * shows the layering: repos that depend on nothing at the bottom, dependants stacked above.
 */
export default function RepoDependencyGraph(props: Props) {
  const { onChange } = props;
  const members = useHeldUntilChanged(
    props.members,
    props.members.map((m) => `${m.gitRepoId}\u0000${m.name}`).join("\n"),
  );
  const dependencies = useHeldUntilChanged(props.dependencies, JSON.stringify(props.dependencies));
  const [positions, setPositions] = useState<Map<string, { x: number; y: number }> | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [pickingFrom, setPickingFrom] = useState<string | null>(null);

  const memberIds = useMemo(() => members.map((m) => m.gitRepoId), [members]);
  const nameOf = useCallback(
    (id: string) => members.find((m) => m.gitRepoId === id)?.name ?? id,
    [members],
  );
  const arrows = useMemo(
    () => toArrows(dependencies).filter((a) => memberIds.includes(a.from) && memberIds.includes(a.to)),
    [dependencies, memberIds],
  );
  useEffect(() => {
    let cancelled = false;
    void layoutRepoDependencies(memberIds, dependencies).then((p) => {
      if (!cancelled) setPositions(p);
    });
    return () => {
      cancelled = true;
    };
  }, [memberIds, dependencies]);

  const remove = useCallback(
    (arrow: DependencyArrow) => {
      setRefusal(null);
      onChange(removeArrow(dependencies, arrow));
    },
    [dependencies, onChange],
  );

  const nodes: RepoNodeType[] = useMemo(
    () =>
      members.map((m) => ({
        id: m.gitRepoId,
        type: "repo",
        position: positions?.get(m.gitRepoId) ?? { x: 0, y: 0 },
        data: { name: m.name },
        ariaLabel: m.name,
        selectable: false,
        deletable: false,
      })),
    [members, positions],
  );

  const edges: DependencyEdgeType[] = useMemo(
    () =>
      arrows.map((a) => {
        const label = `${nameOf(a.from)} depends on ${nameOf(a.to)}`;
        return {
          id: `${a.from}->${a.to}`,
          type: "dependency",
          source: a.from,
          sourceHandle: "out",
          target: a.to,
          targetHandle: "in",
          ariaLabel: label,
          markerEnd: { type: MarkerType.ArrowClosed, width: 18, height: 18 },
          data: { label, onRemove: () => remove(a) },
        };
      }),
    [arrows, nameOf, remove],
  );

  // Self-arrows and repeats are silently not offered; a loop is refused on drop, with the reason.
  const isValidConnection = useCallback(
    (c: Connection | Edge) =>
      c.source !== c.target && !arrows.some((a) => a.from === c.source && a.to === c.target),
    [arrows],
  );

  const onConnect = useCallback(
    (c: Connection) => {
      const arrow = { from: c.source, to: c.target };
      const loop = cycleThrough(dependencies, arrow);
      if (loop) {
        setRefusal(
          `${nameOf(arrow.from)} can't depend on ${nameOf(arrow.to)}: that would make a loop (${loop
            .map(nameOf)
            .join(" → ")}).`,
        );
        return;
      }
      setRefusal(null);
      onChange(addArrow(dependencies, arrow));
    },
    [dependencies, nameOf, onChange],
  );

  return (
    <div className="flex flex-col gap-1">
      <div
        data-testid="repo-dependency-graph"
        data-layout-ready={positions ? "true" : "false"}
        className="h-64 w-full rounded-md border bg-muted/30"
      >
        {positions && (
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            isValidConnection={isValidConnection}
            onConnect={onConnect}
            onClickConnectStart={(_event, { nodeId }) => setPickingFrom(nodeId)}
            onClickConnectEnd={() => setPickingFrom(null)}
            nodesFocusable={false}
            edgesFocusable={false}
            deleteKeyCode={null}
            nodesDraggable={false}
            panOnDrag={false}
            zoomOnScroll={false}
            zoomOnPinch={false}
            zoomOnDoubleClick={false}
            preventScrolling={false}
            fitView
            fitViewOptions={{ padding: 0.2, maxZoom: 1 }}
            proOptions={{ hideAttribution: true }}
          >
            <FitOnLayout positions={positions} />
          </ReactFlow>
        )}
      </div>
      <p role="status" className="text-xs text-muted-foreground">
        {pickingFrom
          ? `Now pick what ${nameOf(pickingFrom)} depends on: click its top dot.`
          : "Draw an arrow from a repo to the repo it depends on: drag from its bottom dot, or click the two dots. × removes an arrow."}
      </p>
      {refusal && (
        <p role="alert" className="text-xs text-destructive">
          {refusal}
        </p>
      )}
    </div>
  );
}
