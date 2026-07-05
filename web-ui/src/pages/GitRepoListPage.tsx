import PageHeader from "@/components/layout/PageHeader";
import PageShell from "@/components/layout/PageShell";
import RepositoriesTab from "@/components/software-projects/RepositoriesTab";

/**
 * Thin wrapper around RepositoriesTab kept for backwards compatibility with
 * existing tests/imports. The live route (/git-repos) now renders
 * SoftwareProjectsPage; this component is only a standalone framing for the
 * repositories list. New callers should use SoftwareProjectsPage instead.
 */
export default function GitRepoListPage() {
  return (
    <PageShell>
      <PageHeader title="Repositories" />
      <RepositoriesTab />
    </PageShell>
  );
}
