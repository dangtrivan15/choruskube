import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import RoadmapCandidateBreakdown from "../RoadmapCandidateBreakdown";
import type { CandidateEpicProposal } from "@/lib/types";

function makeEpic(overrides: Partial<CandidateEpicProposal> = {}): CandidateEpicProposal {
  return {
    title: "Add dark mode",
    description: "Support a dark theme",
    motivation: "Users asked for it",
    repos: ["repo-a", "repo-b"],
    priority: "High",
    stories: [
      {
        title: "Theme toggle",
        description: "Add a toggle",
        tasks: [{ title: "Build toggle", description: "New component" }],
      },
    ],
    ...overrides,
  };
}

describe("RoadmapCandidateBreakdown", () => {
  it("renders nothing when value is empty", () => {
    const onChange = vi.fn();
    const { container } = render(<RoadmapCandidateBreakdown value={[]} onChange={onChange} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders Epics, Stories, and Tasks", () => {
    const onChange = vi.fn();
    render(<RoadmapCandidateBreakdown value={[makeEpic()]} onChange={onChange} />);

    expect(screen.getByDisplayValue("Add dark mode")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Support a dark theme")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Users asked for it")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Theme toggle")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Add a toggle")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Build toggle")).toBeInTheDocument();
    expect(screen.getByDisplayValue("New component")).toBeInTheDocument();
  });

  it("renders repos/priority as read-only context text when present", () => {
    const onChange = vi.fn();
    render(<RoadmapCandidateBreakdown value={[makeEpic()]} onChange={onChange} />);

    expect(screen.getByText(/Likely touches: repo-a, repo-b/)).toBeInTheDocument();
    expect(screen.getByText(/Priority: High/)).toBeInTheDocument();
  });

  it("does not render context block when repos is null and priority is null", () => {
    const onChange = vi.fn();
    render(
      <RoadmapCandidateBreakdown
        value={[makeEpic({ repos: null, priority: null })]}
        onChange={onChange}
      />
    );

    expect(screen.queryByTestId("candidate-epic-context-0")).not.toBeInTheDocument();
  });

  it("does not render editable inputs for repos/priority", () => {
    const onChange = vi.fn();
    render(<RoadmapCandidateBreakdown value={[makeEpic()]} onChange={onChange} />);

    expect(screen.queryByDisplayValue("repo-a")).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue("High")).not.toBeInTheDocument();
  });

  it("propagates an edit to the epic title via onChange", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<RoadmapCandidateBreakdown value={[makeEpic()]} onChange={onChange} />);

    const titleInput = screen.getByDisplayValue("Add dark mode");
    await user.type(titleInput, "!");

    expect(onChange).toHaveBeenCalled();
    const calls = onChange.mock.calls;
    const lastCall = calls[calls.length - 1][0] as CandidateEpicProposal[];
    expect(lastCall[0].title).toBe("Add dark mode!");
  });

  it("removes an epic when Remove epic is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<RoadmapCandidateBreakdown value={[makeEpic()]} onChange={onChange} />);

    await user.click(screen.getByTestId("candidate-epic-remove-0"));

    expect(onChange).toHaveBeenCalledWith([]);
  });

  it("adds a story when Add Story is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<RoadmapCandidateBreakdown value={[makeEpic()]} onChange={onChange} />);

    await user.click(screen.getByTestId("candidate-add-story-0"));

    const calls = onChange.mock.calls;
    const lastCall = calls[calls.length - 1][0] as CandidateEpicProposal[];
    expect(lastCall[0].stories).toHaveLength(2);
    expect(lastCall[0].stories[1]).toEqual({ title: "", description: "", tasks: [] });
  });

  it("removes a story when Remove story is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<RoadmapCandidateBreakdown value={[makeEpic()]} onChange={onChange} />);

    await user.click(screen.getByTestId("candidate-story-remove-0-0"));

    const calls = onChange.mock.calls;
    const lastCall = calls[calls.length - 1][0] as CandidateEpicProposal[];
    expect(lastCall[0].stories).toHaveLength(0);
  });

  it("adds a task when Add Task is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<RoadmapCandidateBreakdown value={[makeEpic()]} onChange={onChange} />);

    await user.click(screen.getByTestId("candidate-add-task-0-0"));

    const calls = onChange.mock.calls;
    const lastCall = calls[calls.length - 1][0] as CandidateEpicProposal[];
    expect(lastCall[0].stories[0].tasks).toHaveLength(2);
  });

  it("removes a task when Remove task is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<RoadmapCandidateBreakdown value={[makeEpic()]} onChange={onChange} />);

    await user.click(screen.getByTestId("candidate-task-remove-0-0-0"));

    const calls = onChange.mock.calls;
    const lastCall = calls[calls.length - 1][0] as CandidateEpicProposal[];
    expect(lastCall[0].stories[0].tasks).toHaveLength(0);
  });

  it("disables Add Story once the cap of 8 is reached", () => {
    const onChange = vi.fn();
    const stories = Array.from({ length: 8 }, (_, i) => ({
      title: `Story ${i}`,
      description: "",
      tasks: [],
    }));
    render(<RoadmapCandidateBreakdown value={[makeEpic({ stories })]} onChange={onChange} />);

    expect(screen.getByTestId("candidate-add-story-0")).toBeDisabled();
  });

  it("disables Add Task once the cap of 8 is reached", () => {
    const onChange = vi.fn();
    const tasks = Array.from({ length: 8 }, (_, i) => ({
      title: `Task ${i}`,
      description: "",
    }));
    const epic = makeEpic({
      stories: [{ title: "Story", description: "", tasks }],
    });
    render(<RoadmapCandidateBreakdown value={[epic]} onChange={onChange} />);

    expect(screen.getByTestId("candidate-add-task-0-0")).toBeDisabled();
  });

  it("does not show the Epic cap warning at or below the cap of 8", () => {
    const onChange = vi.fn();
    const epics = Array.from({ length: 8 }, (_, i) => makeEpic({ title: `Epic ${i}` }));
    render(<RoadmapCandidateBreakdown value={epics} onChange={onChange} />);

    expect(screen.queryByTestId("candidate-epic-cap-warning")).not.toBeInTheDocument();
  });

  it("shows the Epic cap warning above the cap of 8, and it clears once an Epic is removed", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const epics = Array.from({ length: 9 }, (_, i) => makeEpic({ title: `Epic ${i}` }));
    const { rerender } = render(<RoadmapCandidateBreakdown value={epics} onChange={onChange} />);

    expect(screen.getByTestId("candidate-epic-cap-warning")).toHaveTextContent(
      "9 Epics proposed, but only 8 are allowed"
    );

    await user.click(screen.getByTestId("candidate-epic-remove-0"));
    const calls = onChange.mock.calls;
    const afterRemoval = calls[calls.length - 1][0] as CandidateEpicProposal[];
    expect(afterRemoval).toHaveLength(8);

    rerender(<RoadmapCandidateBreakdown value={afterRemoval} onChange={onChange} />);
    expect(screen.queryByTestId("candidate-epic-cap-warning")).not.toBeInTheDocument();
  });
});
