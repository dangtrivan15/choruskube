import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import EditRepoGroupDialog from "@/components/repo-groups/EditRepoGroupDialog";
import type { RepoGroup } from "@/lib/types";

vi.mock("@/lib/api", () => ({
  api: {
    get: vi.fn(),
    getPage: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));

vi.mock("@/lib/toast-messages", () => ({
  showMutationToast: vi.fn((message: string, variant: string) => ({
    id: "mock-id",
    timestamp: Date.now(),
    message,
    variant,
  })),
}));

import { api } from "@/lib/api";

const mockApi = api as unknown as {
  put: ReturnType<typeof vi.fn>;
};

const availableRepos = [
  { id: "r1", name: "r1" },
  { id: "r2", name: "r2" },
  { id: "r3", name: "r3" },
];

function makeGroup(overrides: Partial<RepoGroup> = {}): RepoGroup {
  return {
    id: "g1",
    name: "group-one",
    agentImage: "img:1",
    description: "desc",
    runtimeRequirements: { agentImage: "img:1", enableDocker: false },
    members: [
      { gitRepoId: "r1", name: "r1", position: 0 },
      { gitRepoId: "r2", name: "r2", position: 1 },
    ],
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

describe("EditRepoGroupDialog", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("seeds the form from the group prop", () => {
    renderWithProviders(
      <EditRepoGroupDialog
        group={makeGroup()}
        open={true}
        onOpenChange={vi.fn()}
        availableRepos={availableRepos}
      />,
    );

    expect(screen.getByLabelText(/name/i)).toHaveValue("group-one");
    expect(screen.getByLabelText(/agent image/i)).toHaveValue("img:1");
    expect(screen.getByLabelText(/description/i)).toHaveValue("desc");
    expect(screen.getByLabelText(/r1/i)).toBeChecked();
    expect(screen.getByLabelText(/r2/i)).toBeChecked();
    expect(screen.getByLabelText(/r3/i)).not.toBeChecked();
  });

  it("submits PUT with the edited body and closes the dialog on success", async () => {
    const user = userEvent.setup();
    const onOpenChange = vi.fn();
    mockApi.put.mockResolvedValueOnce(makeGroup({ name: "renamed" }));

    renderWithProviders(
      <EditRepoGroupDialog
        group={makeGroup()}
        open={true}
        onOpenChange={onOpenChange}
        availableRepos={availableRepos}
      />,
    );

    const nameInput = screen.getByLabelText(/name/i);
    await user.clear(nameInput);
    await user.type(nameInput, "renamed");
    // Add r3 so the member set differs (and to verify member ordering passes through).
    await user.click(screen.getByLabelText(/r3/i));

    await user.click(screen.getByRole("button", { name: /save/i }));

    await waitFor(() => {
      expect(mockApi.put).toHaveBeenCalledWith("/repo-groups/g1", {
        name: "renamed",
        agentImage: "img:1",
        description: "desc",
        memberRepoIds: ["r1", "r2", "r3"],
      });
    });
    await waitFor(() => {
      expect(onOpenChange).toHaveBeenCalledWith(false);
    });
  });

  it("renders an inline error when the mutation fails", async () => {
    const user = userEvent.setup();
    mockApi.put.mockRejectedValueOnce(new Error("conflict"));

    renderWithProviders(
      <EditRepoGroupDialog
        group={makeGroup()}
        open={true}
        onOpenChange={vi.fn()}
        availableRepos={availableRepos}
      />,
    );

    await user.click(screen.getByRole("button", { name: /save/i }));

    await waitFor(() => {
      expect(
        screen.getByText(/failed to update repo group/i),
      ).toBeInTheDocument();
    });
  });

  it("renders nothing when group is null", () => {
    renderWithProviders(
      <EditRepoGroupDialog
        group={null}
        open={false}
        onOpenChange={vi.fn()}
        availableRepos={availableRepos}
      />,
    );

    // The form's name input should not render — the dialog body is gated on `group`.
    expect(screen.queryByLabelText(/name/i)).not.toBeInTheDocument();
  });
});
