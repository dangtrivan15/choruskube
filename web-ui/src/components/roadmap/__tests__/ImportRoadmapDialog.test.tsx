import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import ImportRoadmapDialog from "@/components/roadmap/ImportRoadmapDialog";
import { ApiError } from "@/lib/api";
import type { RoadmapImportResponse } from "@/lib/types";

const mockMutate = vi.fn();
const mockReset = vi.fn();
vi.mock("@/hooks/useRoadmapImport", () => ({
  useImportRoadmap: () => ({
    mutate: mockMutate,
    reset: mockReset,
    isPending: false,
    variables: undefined,
  }),
}));

vi.mock("@/hooks/useSoftwareProjects", () => ({
  useSoftwareProjects: () => ({
    data: [
      {
        id: "r1",
        name: "backend-api",
        type: "git_repo",
        organizationId: "o1",
        agentImage: null,
        description: null,
        runtimeRequirements: { agentImage: null, enableDocker: false },
        createdAt: "2026-01-01",
        updatedAt: "2026-01-01",
      },
    ],
  }),
}));

const DOCUMENT = {
  epics: [
    {
      title: "Checkout",
      description: "d",
      stories: [{ title: "Cart", description: "d", tasks: [{ title: "API", description: "d" }] }],
    },
    { existingId: "11111111-1111-1111-1111-111111111111", key: "old" },
  ],
};

const DRY_RUN: RoadmapImportResponse = {
  dryRun: true,
  milestones: 0,
  newEpics: 1,
  newStories: 1,
  newTasks: 1,
  existingItems: 1,
  dependencies: 0,
  createdEpicIds: [],
};

type MutateOptions = { onSuccess?: (r: RoadmapImportResponse) => void; onError?: (e: unknown) => void };

function pasteDocument(json: string) {
  // fireEvent rather than user.type: braces are userEvent key descriptors.
  fireEvent.change(screen.getByTestId("import-roadmap-json"), { target: { value: json } });
}

async function pickProject(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByTestId("import-roadmap-project"));
  await user.click(screen.getByText("backend-api"));
}

beforeEach(() => {
  mockMutate.mockReset();
  mockReset.mockReset();
});

describe("ImportRoadmapDialog", () => {
  it("keeps Validate disabled until a project is picked and the text is a JSON object", async () => {
    renderWithProviders(<ImportRoadmapDialog open={true} onOpenChange={() => {}} />);
    const user = userEvent.setup({ pointerEventsCheck: 0, delay: null });
    const validate = screen.getByTestId("import-roadmap-validate");
    expect(validate).toBeDisabled();

    pasteDocument("{ not json");
    expect(screen.getByTestId("import-roadmap-parse-error")).toHaveTextContent("Invalid JSON");
    await pickProject(user);
    expect(validate).toBeDisabled();

    pasteDocument("[]");
    expect(screen.getByTestId("import-roadmap-parse-error")).toHaveTextContent("must be a JSON object");

    pasteDocument(JSON.stringify(DOCUMENT));
    expect(screen.queryByTestId("import-roadmap-parse-error")).not.toBeInTheDocument();
    expect(validate).toBeEnabled();
    expect(screen.getByTestId("import-roadmap-submit")).toBeDisabled();
  });

  it("dry-runs the parsed document, previews it, and only then enables Import", async () => {
    mockMutate.mockImplementation((_vars, opts: MutateOptions) => opts.onSuccess?.(DRY_RUN));
    renderWithProviders(<ImportRoadmapDialog open={true} onOpenChange={() => {}} />);
    const user = userEvent.setup({ pointerEventsCheck: 0, delay: null });
    await pickProject(user);
    pasteDocument(JSON.stringify(DOCUMENT));

    await user.click(screen.getByTestId("import-roadmap-validate"));

    expect(mockMutate.mock.calls[0][0]).toEqual({ softwareProjectId: "r1", document: DOCUMENT, dryRun: true });
    const preview = screen.getByTestId("import-roadmap-preview");
    expect(preview).toHaveTextContent("1 new epic, 1 story, 1 task, 0 milestones, 0 dependencies");
    expect(preview).toHaveTextContent("attaching to 1 existing item");
    expect(preview).toHaveTextContent("Checkout");
    expect(preview).toHaveTextContent("Existing epic 11111111-1111-1111-1111-111111111111");
    expect(screen.getByTestId("import-roadmap-submit")).toBeEnabled();

    // Any edit invalidates the dry run: the import must match what was previewed.
    pasteDocument(JSON.stringify({ ...DOCUMENT, dependencies: [] }));
    expect(screen.queryByTestId("import-roadmap-preview")).not.toBeInTheDocument();
    expect(screen.getByTestId("import-roadmap-submit")).toBeDisabled();
  });

  it("lists every error of a rejected document", async () => {
    mockMutate.mockImplementation((_vars, opts: MutateOptions) =>
      opts.onError?.(
        new ApiError(400, {
          valid: false,
          errors: ["epics[0]: a new epic needs at least one story", "dependencies[0]: unknown key 'x'"],
        })
      )
    );
    renderWithProviders(<ImportRoadmapDialog open={true} onOpenChange={() => {}} />);
    const user = userEvent.setup({ pointerEventsCheck: 0, delay: null });
    await pickProject(user);
    pasteDocument(JSON.stringify(DOCUMENT));

    await user.click(screen.getByTestId("import-roadmap-validate"));

    const errors = screen.getByTestId("import-roadmap-errors");
    expect(errors).toHaveTextContent("2 problems — nothing was imported");
    expect(errors).toHaveTextContent("epics[0]: a new epic needs at least one story");
    expect(errors).toHaveTextContent("dependencies[0]: unknown key 'x'");
    expect(screen.getByTestId("import-roadmap-submit")).toBeDisabled();
  });

  it("imports after a successful dry run and closes", async () => {
    mockMutate.mockImplementation((vars: { dryRun: boolean }, opts: MutateOptions) =>
      opts.onSuccess?.(vars.dryRun ? DRY_RUN : { ...DRY_RUN, dryRun: false, createdEpicIds: ["e1"] })
    );
    const onOpenChange = vi.fn();
    renderWithProviders(<ImportRoadmapDialog open={true} onOpenChange={onOpenChange} />);
    const user = userEvent.setup({ pointerEventsCheck: 0, delay: null });
    await pickProject(user);
    pasteDocument(JSON.stringify(DOCUMENT));
    await user.click(screen.getByTestId("import-roadmap-validate"));

    await user.click(screen.getByTestId("import-roadmap-submit"));

    expect(mockMutate.mock.calls[1][0]).toEqual({ softwareProjectId: "r1", document: DOCUMENT, dryRun: false });
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });
});
