import { useMemo, useState } from "react";
import { useSearchParams } from "react-router";
import RunDag from "@/components/runs/RunDag";
import { DAG_FIXTURES, type DagFixtureKey } from "@/lib/fixtures/dagFixtures";

const FIXTURE_KEYS: DagFixtureKey[] = ["feature_development", "sparse_chain", "dense_fanout"];

export default function DagPlaygroundPage() {
  const [params, setParams] = useSearchParams();
  const initial = (params.get("fixture") as DagFixtureKey | null) ?? "feature_development";
  const [selected, setSelected] = useState<DagFixtureKey>(
    FIXTURE_KEYS.includes(initial) ? initial : "feature_development",
  );

  const run = useMemo(() => DAG_FIXTURES[selected], [selected]);

  return (
    <div className="flex h-screen w-screen flex-col">
      <div className="flex items-center gap-3 border-b px-4 py-2 text-xs text-muted-foreground">
        <span>DAG layout playground —</span>
        <span>fixture:</span>
        <select
          className="rounded border bg-background px-2 py-1 text-sm"
          value={selected}
          onChange={(e) => {
            const next = e.target.value as DagFixtureKey;
            setSelected(next);
            setParams({ fixture: next });
          }}
          data-testid="dag-fixture-picker"
        >
          {FIXTURE_KEYS.map((k) => (
            <option key={k} value={k}>{k}</option>
          ))}
        </select>
        <span>
          ({run.graphSnapshot?.nodes.length ?? 0} nodes,{" "}
          {run.graphSnapshot?.edges.length ?? 0} edges)
        </span>
      </div>
      <div className="flex-1">
        <RunDag run={run} onNodeSelect={() => undefined} />
      </div>
    </div>
  );
}
