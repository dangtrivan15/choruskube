import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import MilestoneSelect from "@/components/roadmap/MilestoneSelect";

const mockUseMilestones = vi.fn();
vi.mock("@/hooks/useMilestones", () => ({
  useMilestones: (...args: unknown[]) => mockUseMilestones(...args),
}));

const PROJECT_MILESTONES: Record<string, { id: string; name: string }[]> = {
  r1: [
    { id: "m1", name: "Q3 Launch" },
    { id: "m2", name: "Q4 Launch" },
  ],
  r2: [{ id: "m3", name: "Beta" }],
};

beforeEach(() => {
  mockUseMilestones.mockReset();
  mockUseMilestones.mockImplementation((softwareProjectId?: string) => ({
    data: { content: softwareProjectId ? (PROJECT_MILESTONES[softwareProjectId] ?? []) : [] },
  }));
});

describe("MilestoneSelect", () => {
  it("fetches Milestones scoped to the given software project", () => {
    renderWithProviders(
      <MilestoneSelect value={null} onChange={() => {}} softwareProjectId="r1" />
    );
    expect(mockUseMilestones).toHaveBeenCalledWith("r1");
  });

  it("always offers a None option to clear the assignment", async () => {
    renderWithProviders(
      <MilestoneSelect value="m1" onChange={() => {}} softwareProjectId="r1" />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.click(screen.getByTestId("milestone-select"));
    expect(screen.getByTestId("milestone-option-none")).toBeInTheDocument();
  });

  it("lists only the Milestones belonging to the given project", async () => {
    renderWithProviders(
      <MilestoneSelect value={null} onChange={() => {}} softwareProjectId="r1" />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.click(screen.getByTestId("milestone-select"));

    expect(screen.getByText("Q3 Launch")).toBeInTheDocument();
    expect(screen.getByText("Q4 Launch")).toBeInTheDocument();
    expect(screen.queryByText("Beta")).not.toBeInTheDocument();
  });

  it("calls onChange with the milestone id when an option is picked", async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <MilestoneSelect value={null} onChange={onChange} softwareProjectId="r1" />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.click(screen.getByTestId("milestone-select"));
    await user.click(screen.getByText("Q3 Launch"));

    expect(onChange).toHaveBeenCalledWith("m1");
  });

  it("calls onChange with null when None is picked", async () => {
    const onChange = vi.fn();
    renderWithProviders(
      <MilestoneSelect value="m1" onChange={onChange} softwareProjectId="r1" />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.click(screen.getByTestId("milestone-select"));
    await user.click(screen.getByTestId("milestone-option-none"));

    expect(onChange).toHaveBeenCalledWith(null);
  });

  it("shows the selected milestone's name in the trigger", () => {
    renderWithProviders(
      <MilestoneSelect value="m1" onChange={() => {}} softwareProjectId="r1" />
    );
    expect(screen.getByTestId("milestone-select")).toHaveTextContent("Q3 Launch");
  });

  it("is disabled when no software project is selected yet", () => {
    renderWithProviders(
      <MilestoneSelect value={null} onChange={() => {}} softwareProjectId={undefined} />
    );
    expect(screen.getByTestId("milestone-select")).toBeDisabled();
  });
});
