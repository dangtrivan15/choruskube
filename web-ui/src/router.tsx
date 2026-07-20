import { createBrowserRouter, Navigate } from "react-router";
import type { RouteObject } from "react-router";
import AppLayout from "@/components/layout/AppLayout";
import RunListPage from "@/pages/RunListPage";
import RunMonitorPage from "@/pages/RunMonitorPage";
import ApprovalsPage from "@/pages/ApprovalsPage";

import SoftwareProjectsPage from "@/pages/SoftwareProjectsPage";
import AnalyticsPage from "@/pages/AnalyticsPage";
import RoadmapPage from "@/pages/RoadmapPage";
import RoadmapBoardPage from "@/pages/RoadmapBoardPage";
import RoadmapGraphPage from "@/pages/RoadmapGraphPage";
import EpicDetailPage from "@/pages/EpicDetailPage";
import StoryDetailPage from "@/pages/StoryDetailPage";
import TaskDetailPage from "@/pages/TaskDetailPage";
import NotFoundPage from "@/pages/NotFoundPage";
import DagPlaygroundPage from "@/pages/DagPlaygroundPage";
import { config } from "@/config";
import DocsPage from "@/pages/DocsPage";

const coreChildren: RouteObject[] = [
  { index: true, element: <Navigate to="/runs" replace /> },
  { path: "runs", element: <RunListPage /> },
  { path: "runs/:id", element: <RunMonitorPage /> },
  { path: "approvals", element: <ApprovalsPage /> },
  { path: "git-repos", element: <SoftwareProjectsPage /> },
  { path: "analytics", element: <AnalyticsPage /> },
  { path: "roadmap", element: <RoadmapPage /> },
  { path: "roadmap/board", element: <RoadmapBoardPage /> },
  { path: "roadmap/epics/:epicId", element: <EpicDetailPage /> },
  { path: "roadmap/epics/:epicId/graph", element: <RoadmapGraphPage /> },
  { path: "roadmap/epics/:epicId/stories/:storyId", element: <StoryDetailPage /> },
  { path: "tasks/:id", element: <TaskDetailPage /> },
  { path: "docs", element: <DocsPage /> },
  { path: "docs/:slug", element: <DocsPage /> },
];

/**
 * Build the app router. Core defines its own routes; an extension entrypoint passes its extra
 * routes via `extraRoutes` (from AppExtensions). Core does not reach into extensions — the catch-all
 * stays last so injected routes are matched first.
 */
export function buildRouter(extraRoutes: RouteObject[] = []) {
  return createBrowserRouter([
    {
      element: <AppLayout />,
      children: [
        ...coreChildren,
        ...(config.dagPlaygroundEnabled ? [{ path: "dev/dag-playground", element: <DagPlaygroundPage /> }] : []),
        ...extraRoutes,
        { path: "*", element: <NotFoundPage /> },
      ],
    },
  ]);
}
