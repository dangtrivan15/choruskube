import RepoDependencyGraph from "@/components/repo-groups/RepoDependencyGraph";
import type { RepoGroupMember, RepoGroupTestScopeRequest } from "@/lib/types";

interface Props {
  members: RepoGroupMember[];
  value: RepoGroupTestScopeRequest;
  onChange: (v: RepoGroupTestScopeRequest) => void;
  error?: string;
}

/**
 * Controlled "Testing" section of the repo-group dialog: the affected-only
 * switch plus the graph of which members depend on which. Turning the switch
 * off hides the graph but keeps the arrows, so toggling back loses nothing.
 */
export default function RepoGroupTestScopeSection({ members, value, onChange, error }: Props) {
  return (
    <section className="flex flex-col gap-2">
      <h3 className="text-sm font-medium">Testing</h3>
      <div className="flex items-center gap-2">
        <input
          id="repo-group-affected-only"
          type="checkbox"
          role="switch"
          checked={value.affectedOnly}
          onChange={(e) => onChange({ ...value, affectedOnly: e.target.checked })}
          className="size-4 rounded border"
        />
        <label htmlFor="repo-group-affected-only" className="text-sm font-medium">
          Test only affected repos
        </label>
      </div>
      <p className="text-xs text-muted-foreground">
        Runs a repo's tests only when the run changed it or a repo it depends on.
      </p>

      {value.affectedOnly &&
        (members.length < 2 ? (
          <p className="text-xs text-muted-foreground">
            Add another repo to this group to draw dependencies between them.
          </p>
        ) : (
          <RepoDependencyGraph
            members={members}
            dependencies={value.dependencies}
            onChange={(dependencies) => onChange({ ...value, dependencies })}
          />
        ))}

      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
    </section>
  );
}
