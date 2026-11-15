import type { RepoGroupMember, RepoGroupTestScopeRequest } from "@/lib/types";

interface Props {
  members: RepoGroupMember[];
  value: RepoGroupTestScopeRequest;
  onChange: (v: RepoGroupTestScopeRequest) => void;
  error?: string;
}

/**
 * Controlled "Testing" section of the repo-group dialog: the affected-only
 * switch plus, per member, the sibling repos it depends on. Turning the switch
 * off hides the rows but keeps the edges, so toggling back loses nothing.
 */
export default function RepoGroupTestScopeSection({ members, value, onChange, error }: Props) {
  function dependsOn(repoId: string): string[] {
    return value.dependencies.find((d) => d.repoId === repoId)?.dependsOnRepoIds ?? [];
  }

  function toggleDependency(repoId: string, targetId: string) {
    const current = dependsOn(repoId);
    const next = current.includes(targetId)
      ? current.filter((x) => x !== targetId)
      : [...current, targetId];
    const others = value.dependencies.filter((d) => d.repoId !== repoId);
    onChange({
      ...value,
      dependencies: next.length > 0 ? [...others, { repoId, dependsOnRepoIds: next }] : others,
    });
  }

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

      {value.affectedOnly && (
        <div className="flex flex-col gap-3">
          {members.map((m) => {
            const others = members.filter((o) => o.gitRepoId !== m.gitRepoId);
            const selected = dependsOn(m.gitRepoId);
            return (
              <div key={m.gitRepoId} role="group" aria-label={m.name} className="flex flex-col gap-1">
                <span className="text-sm">{m.name}</span>
                {others.length === 0 ? (
                  <p className="text-xs text-muted-foreground">No other repos in this group.</p>
                ) : (
                  <div className="flex flex-col gap-1 pl-4">
                    <span className="text-xs text-muted-foreground">Depends on</span>
                    {others.map((o) => {
                      const id = `repo-group-dep-${m.gitRepoId}-${o.gitRepoId}`;
                      return (
                        <div key={o.gitRepoId} className="flex items-center gap-2">
                          <input
                            id={id}
                            type="checkbox"
                            className="size-4 rounded border"
                            aria-label={`${m.name} depends on ${o.name}`}
                            checked={selected.includes(o.gitRepoId)}
                            onChange={() => toggleDependency(m.gitRepoId, o.gitRepoId)}
                          />
                          <label htmlFor={id} className="text-sm" aria-hidden>
                            {o.name}
                          </label>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
    </section>
  );
}
