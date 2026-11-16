import { act, type ComponentType, type ReactNode } from "react";

/**
 * A stand-in for `@xyflow/react` in tests of RepoDependencyGraph and the components that host it.
 * Real ReactFlow's pan/zoom handlers crash happy-dom (see RoadmapGraph.test.tsx), so this shell
 * renders the graph's own node and edge components directly and keeps the props it was given,
 * letting a test make the connection a drag or a click would. Use it with
 * `vi.mock("@xyflow/react", async () => (await import("@/__tests__/reactFlowShell")).reactFlowShell)`.
 */
export interface ShellConnection {
  source: string;
  target: string;
  sourceHandle: string | null;
  targetHandle: string | null;
}

interface ShellProps {
  nodes: { id: string; type: string; data: unknown }[];
  edges: { id: string; type: string; data: unknown }[];
  nodeTypes: Record<string, ComponentType<{ id: string; data: unknown }>>;
  edgeTypes: Record<string, ComponentType<Record<string, unknown>>>;
  onConnect: (c: ShellConnection) => void;
  isValidConnection: (c: ShellConnection) => boolean;
  children?: ReactNode;
}

let latest: ShellProps | undefined;

export function shellProps(): ShellProps {
  if (!latest) throw new Error("ReactFlow has not rendered");
  return latest;
}

/** Connects `source`'s outgoing dot to `target`'s incoming one, as a user would. */
export function connectInShell(source: string, target: string) {
  act(() => shellProps().onConnect({ source, target, sourceHandle: "out", targetHandle: "in" }));
}

export const reactFlowShell = {
  ReactFlow: (props: ShellProps) => {
    latest = props;
    return (
      <div>
        {props.nodes.map((n) => {
          const Node = props.nodeTypes[n.type];
          return (
            <div key={n.id} data-testid={`flow-node-${n.id}`}>
              <Node id={n.id} data={n.data} />
            </div>
          );
        })}
        {props.edges.map((e) => {
          const Edge = props.edgeTypes[e.type];
          return <Edge key={e.id} id={e.id} data={e.data} sourceX={0} sourceY={0} targetX={0} targetY={0} />;
        })}
        {props.children}
      </div>
    );
  },
  Handle: () => null,
  BaseEdge: () => null,
  EdgeLabelRenderer: ({ children }: { children: ReactNode }) => <>{children}</>,
  getBezierPath: () => ["M0 0", 0, 0],
  useReactFlow: () => ({ fitView: () => Promise.resolve(true) }),
  MarkerType: { ArrowClosed: "arrowclosed" },
  Position: { Top: "top", Bottom: "bottom" },
};
