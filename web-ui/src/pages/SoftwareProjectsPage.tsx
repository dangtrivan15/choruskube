import { useState } from "react";
import PageHeader from "@/components/layout/PageHeader";
import PageShell from "@/components/layout/PageShell";
import RepositoriesTab from "@/components/software-projects/RepositoriesTab";
import RepoGroupsTab from "@/components/repo-groups/RepoGroupsTab";

type Tab = "repo-groups" | "repositories";

const tabLabels: Record<Tab, string> = {
  "repo-groups": "Repo Groups",
  repositories: "Repositories",
};

const tabs: Tab[] = ["repo-groups", "repositories"];

/**
 * Top-level page for the Software Projects area. Hosts two sub-tabs:
 * "Repo Groups" and "Repositories" (the
 * pre-existing GitRepoListPage content extracted into RepositoriesTab).
 *
 * Note: the route URL stays /git-repos for now — the full route rename
 * is deferred. The sidebar label is the user-facing entry point.
 */
export default function SoftwareProjectsPage() {
  const [activeTab, setActiveTab] = useState<Tab>("repo-groups");

  return (
    <PageShell>
      <PageHeader title="Software Projects" />

      <div className="flex gap-1 border-b">
        {tabs.map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={`px-4 py-2 text-sm font-medium transition-colors ${
              activeTab === tab
                ? "border-b-2 border-primary text-primary"
                : "text-muted-foreground hover:text-foreground"
            }`}
          >
            {tabLabels[tab]}
          </button>
        ))}
      </div>

      {activeTab === "repo-groups" && <RepoGroupsTab />}
      {activeTab === "repositories" && <RepositoriesTab />}
    </PageShell>
  );
}
