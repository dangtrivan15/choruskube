import { useSoftwareProjects } from "@/hooks/useSoftwareProjects";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

/**
 * Grouped Software Project picker. Lists user-created repo groups and individual
 * git repos in two labeled sections, sourced from {@code GET /api/v1/software-projects}.
 */
export default function SoftwareProjectSelect({
  value,
  onChange,
  testId,
}: {
  value: string;
  onChange: (v: string) => void;
  /**
   * Optional override for the trigger's data-testid. Defaults to a generic
   * value; callers that render the dropdown more than once on a page (e.g. the
   * Edit-vs-Create dialogs) can disambiguate by passing their own.
   */
  testId?: string;
}) {
  const { data } = useSoftwareProjects();
  const projects = data ?? [];
  const repoGroups = projects.filter((p) => p.type === "repo_group");
  const repos = projects.filter((p) => p.type === "git_repo");
  const selected = projects.find((p) => p.id === value);
  return (
    <Select value={value} onValueChange={(v) => onChange(v ?? "")}>
      <SelectTrigger
        data-testid={testId ?? "software-project-select"}
        className="w-full"
      >
        <SelectValue placeholder="Select a software project...">
          {selected ? selected.name : undefined}
        </SelectValue>
      </SelectTrigger>
      <SelectContent>
        {repoGroups.length > 0 && (
          <SelectGroup>
            <SelectLabel>Repo Groups</SelectLabel>
            {repoGroups.map((p) => (
              <SelectItem key={p.id} value={p.id}>
                {p.name}
              </SelectItem>
            ))}
          </SelectGroup>
        )}
        {repos.length > 0 && (
          <SelectGroup>
            <SelectLabel>Repositories</SelectLabel>
            {repos.map((p) => (
              <SelectItem key={p.id} value={p.id}>
                {p.name}
              </SelectItem>
            ))}
          </SelectGroup>
        )}
      </SelectContent>
    </Select>
  );
}
