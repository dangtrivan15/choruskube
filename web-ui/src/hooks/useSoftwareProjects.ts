import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { SoftwareProject } from "@/lib/types";

/**
 * Lists every SoftwareProject (GitRepo + RepoGroup) in the active org as a
 * single flat array. The {@code type} discriminator on each entry distinguishes
 * git_repo from repo_group rows. Backed by GET /api/v1/software-projects.
 */
export function useSoftwareProjects() {
  return useQuery({
    queryKey: ["software-projects"],
    queryFn: () => api.get<SoftwareProject[]>("/software-projects"),
  });
}
