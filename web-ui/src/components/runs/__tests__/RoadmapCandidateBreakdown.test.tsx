import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapCandidateBreakdown from "../RoadmapCandidateBreakdown";
import type { CandidateEpicProposal, RoadmapCandidatesDocument } from "@/lib/types";

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
        priority: "Medium",
        tasks: [{ title: "Build toggle", description: "New component", priority: "Low" }],
      },
    ],
    key: "epic-1",
    milestone: null,
    ...overrides,
  };
}

function makeDocument(overrides: Partial<RoadmapCandidatesDocument> = {}): RoadmapCandidatesDocument {
  return {
    milestones: [],
    epics: [makeEpic()],
    dependencies: [],
    ...overrides,
  };
}

describe("RoadmapCandidateBreakdown", () => {
  it("renders nothing when there are no epics", () => {
    const onChange = vi.fn();
    const { container } = renderWithProviders(
      <RoadmapCandidateBreakdown value={makeDocument({ epics: [] })} onChange={onChange} />
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("renders Epics, Stories, and Tasks", () => {
    const onChange = vi.fn();
    renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

    expect(screen.getByDisplayValue("Add dark mode")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Support a dark theme")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Users asked for it")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Theme toggle")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Add a toggle")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Build toggle")).toBeInTheDocument();
    expect(screen.getByDisplayValue("New component")).toBeInTheDocument();
  });

  it("renders repos as read-only context text when present", () => {
    const onChange = vi.fn();
    renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

    expect(screen.getByText(/Likely touches: repo-a, repo-b/)).toBeInTheDocument();
  });

  it("does not render context block when repos is null", () => {
    const onChange = vi.fn();
    renderWithProviders(
      <RoadmapCandidateBreakdown
        value={makeDocument({ epics: [makeEpic({ repos: null })] })}
        onChange={onChange}
      />
    );

    expect(screen.queryByTestId("candidate-epic-context-0")).not.toBeInTheDocument();
  });

  it("does not render an editable input for repos", () => {
    const onChange = vi.fn();
    renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

    expect(screen.queryByDisplayValue("repo-a")).not.toBeInTheDocument();
  });

  it("propagates an edit to the epic title via onChange", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

    const titleInput = screen.getByDisplayValue("Add dark mode");
    await user.type(titleInput, "!");

    expect(onChange).toHaveBeenCalled();
    const calls = onChange.mock.calls;
    const lastCall = calls[calls.length - 1][0] as RoadmapCandidatesDocument;
    expect(lastCall.epics[0].title).toBe("Add dark mode!");
  });

  it("removes an epic when Remove epic is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

    await user.click(screen.getByTestId("candidate-epic-remove-0"));

    expect(onChange).toHaveBeenCalledWith(makeDocument({ epics: [] }));
  });

  it("adds a story when Add Story is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

    await user.click(screen.getByTestId("candidate-add-story-0"));

    const calls = onChange.mock.calls;
    const lastCall = calls[calls.length - 1][0] as RoadmapCandidatesDocument;
    expect(lastCall.epics[0].stories).toHaveLength(2);
    expect(lastCall.epics[0].stories[1]).toEqual({ title: "", description: "", tasks: [] });
  });

  it("removes a story when Remove story is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

    await user.click(screen.getByTestId("candidate-story-remove-0-0"));

    const calls = onChange.mock.calls;
    const lastCall = calls[calls.length - 1][0] as RoadmapCandidatesDocument;
    expect(lastCall.epics[0].stories).toHaveLength(0);
  });

  it("adds a task when Add Task is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

    await user.click(screen.getByTestId("candidate-add-task-0-0"));

    const calls = onChange.mock.calls;
    const lastCall = calls[calls.length - 1][0] as RoadmapCandidatesDocument;
    expect(lastCall.epics[0].stories[0].tasks).toHaveLength(2);
  });

  it("removes a task when Remove task is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

    await user.click(screen.getByTestId("candidate-task-remove-0-0-0"));

    const calls = onChange.mock.calls;
    const lastCall = calls[calls.length - 1][0] as RoadmapCandidatesDocument;
    expect(lastCall.epics[0].stories[0].tasks).toHaveLength(0);
  });

  it("disables Add Story once the cap of 8 is reached", () => {
    const onChange = vi.fn();
    const stories = Array.from({ length: 8 }, (_, i) => ({
      title: `Story ${i}`,
      description: "",
      tasks: [],
    }));
    renderWithProviders(
      <RoadmapCandidateBreakdown
        value={makeDocument({ epics: [makeEpic({ stories })] })}
        onChange={onChange}
      />
    );

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
    renderWithProviders(
      <RoadmapCandidateBreakdown value={makeDocument({ epics: [epic] })} onChange={onChange} />
    );

    expect(screen.getByTestId("candidate-add-task-0-0")).toBeDisabled();
  });

  it("does not show the Epic cap warning at or below the cap of 8", () => {
    const onChange = vi.fn();
    const epics = Array.from({ length: 8 }, (_, i) => makeEpic({ title: `Epic ${i}` }));
    renderWithProviders(
      <RoadmapCandidateBreakdown value={makeDocument({ epics })} onChange={onChange} />
    );

    expect(screen.queryByTestId("candidate-epic-cap-warning")).not.toBeInTheDocument();
  });

  it("shows the Epic cap warning above the cap of 8, and it clears once an Epic is removed", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const epics = Array.from({ length: 9 }, (_, i) => makeEpic({ title: `Epic ${i}` }));
    const { rerender } = renderWithProviders(
      <RoadmapCandidateBreakdown value={makeDocument({ epics })} onChange={onChange} />
    );

    expect(screen.getByTestId("candidate-epic-cap-warning")).toHaveTextContent(
      "9 Epics proposed, but only 8 are allowed"
    );

    await user.click(screen.getByTestId("candidate-epic-remove-0"));
    const calls = onChange.mock.calls;
    const afterRemoval = calls[calls.length - 1][0] as RoadmapCandidatesDocument;
    expect(afterRemoval.epics).toHaveLength(8);

    rerender(<RoadmapCandidateBreakdown value={afterRemoval} onChange={onChange} />);
    expect(screen.queryByTestId("candidate-epic-cap-warning")).not.toBeInTheDocument();
  });

  describe("milestones", () => {
    it("renders a read-only milestones section when present", () => {
      const onChange = vi.fn();
      const value = makeDocument({
        milestones: [
          { key: "m1", name: "Q3 Launch", description: "First public release", targetDate: "2026-09-01" },
        ],
      });
      renderWithProviders(<RoadmapCandidateBreakdown value={value} onChange={onChange} />);

      expect(screen.getByTestId("candidate-milestones")).toBeInTheDocument();
      expect(screen.getByText("Q3 Launch")).toBeInTheDocument();
      expect(screen.getByText(/First public release/)).toBeInTheDocument();
      expect(screen.getByText(/2026-09-01/)).toBeInTheDocument();
    });

    it("does not render a milestones section when empty", () => {
      const onChange = vi.fn();
      renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

      expect(screen.queryByTestId("candidate-milestones")).not.toBeInTheDocument();
    });

    it("still renders the milestones section when there are no epics", () => {
      // Regression test: the component used to bail out with `return null` whenever
      // `epics` was empty, even when a milestones-only or dependencies-only document was
      // submitted — hiding those proposed items from the reviewer entirely.
      const onChange = vi.fn();
      const value = makeDocument({
        epics: [],
        milestones: [{ key: "m1", name: "Q3 Launch", description: null, targetDate: null }],
      });
      renderWithProviders(<RoadmapCandidateBreakdown value={value} onChange={onChange} />);

      expect(screen.getByTestId("candidate-milestones")).toBeInTheDocument();
      expect(screen.getByText("Q3 Launch")).toBeInTheDocument();
    });

    it("assigns an Epic to a Milestone via the milestone select, updating the emitted document", async () => {
      const user = userEvent.setup({ pointerEventsCheck: 0 });
      const onChange = vi.fn();
      const value = makeDocument({
        milestones: [{ key: "m1", name: "Q3 Launch", description: null, targetDate: null }],
      });
      renderWithProviders(<RoadmapCandidateBreakdown value={value} onChange={onChange} />);

      await user.click(screen.getByTestId("candidate-epic-milestone-select-0"));
      await user.click(screen.getByTestId("candidate-epic-milestone-select-0-m1"));

      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({
          epics: [expect.objectContaining({ milestone: "m1" })],
        })
      );
    });

    it("clears an Epic's Milestone via the None option", async () => {
      const user = userEvent.setup({ pointerEventsCheck: 0 });
      const onChange = vi.fn();
      const value = makeDocument({
        milestones: [{ key: "m1", name: "Q3 Launch", description: null, targetDate: null }],
        epics: [makeEpic({ milestone: "m1" })],
      });
      renderWithProviders(<RoadmapCandidateBreakdown value={value} onChange={onChange} />);

      await user.click(screen.getByTestId("candidate-epic-milestone-select-0"));
      await user.click(screen.getByTestId("candidate-epic-milestone-select-0-none"));

      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({
          epics: [expect.objectContaining({ milestone: null })],
        })
      );
    });
  });

  describe("per-level priority", () => {
    it("renders a PriorityBadge for the Epic, Story, and Task", () => {
      const onChange = vi.fn();
      renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

      expect(screen.getByTestId("candidate-epic-priority-badge-0")).toHaveTextContent("High");
      expect(screen.getByTestId("candidate-story-priority-badge-0-0")).toHaveTextContent("Medium");
      expect(screen.getByTestId("candidate-task-priority-badge-0-0-0")).toHaveTextContent("Low");
    });

    it("defaults an unrecognized/blank priority to Medium in the badge", () => {
      const onChange = vi.fn();
      const value = makeDocument({ epics: [makeEpic({ priority: null })] });
      renderWithProviders(<RoadmapCandidateBreakdown value={value} onChange={onChange} />);

      expect(screen.getByTestId("candidate-epic-priority-badge-0")).toHaveTextContent("Medium");
    });

    it("edits the Epic priority via its select, updating the emitted document", async () => {
      const user = userEvent.setup({ pointerEventsCheck: 0 });
      const onChange = vi.fn();
      renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

      await user.click(screen.getByTestId("candidate-epic-priority-select-0"));
      await user.click(screen.getByTestId("priority-option-low"));

      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({
          epics: [expect.objectContaining({ priority: "low" })],
        })
      );
    });

    it("edits the Story priority via its select, updating the emitted document", async () => {
      const user = userEvent.setup({ pointerEventsCheck: 0 });
      const onChange = vi.fn();
      renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

      await user.click(screen.getByTestId("candidate-story-priority-select-0-0"));
      await user.click(screen.getByTestId("priority-option-high"));

      const calls = onChange.mock.calls;
      const lastCall = calls[calls.length - 1][0] as RoadmapCandidatesDocument;
      expect(lastCall.epics[0].stories[0].priority).toBe("high");
    });

    it("edits the Task priority via its select, updating the emitted document", async () => {
      const user = userEvent.setup({ pointerEventsCheck: 0 });
      const onChange = vi.fn();
      renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

      await user.click(screen.getByTestId("candidate-task-priority-select-0-0-0"));
      await user.click(screen.getByTestId("priority-option-medium"));

      const calls = onChange.mock.calls;
      const lastCall = calls[calls.length - 1][0] as RoadmapCandidatesDocument;
      expect(lastCall.epics[0].stories[0].tasks[0].priority).toBe("medium");
    });
  });

  describe("dependencies", () => {
    it("renders a read-only dependency list when present", () => {
      const onChange = vi.fn();
      const value = makeDocument({ dependencies: [{ blocking: "epic-1", blocked: "story-a" }] });
      renderWithProviders(<RoadmapCandidateBreakdown value={value} onChange={onChange} />);

      expect(screen.getByTestId("candidate-dependencies")).toBeInTheDocument();
      expect(screen.getByTestId("candidate-dependency-0")).toHaveTextContent("epic-1");
      expect(screen.getByTestId("candidate-dependency-0")).toHaveTextContent("story-a");
    });

    it("does not render a dependency section when empty", () => {
      const onChange = vi.fn();
      renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

      expect(screen.queryByTestId("candidate-dependencies")).not.toBeInTheDocument();
    });

    it("renders no interactive controls (add/remove/edit) inside the dependency list", () => {
      const onChange = vi.fn();
      const value = makeDocument({ dependencies: [{ blocking: "epic-1", blocked: "story-a" }] });
      renderWithProviders(<RoadmapCandidateBreakdown value={value} onChange={onChange} />);

      const dependencyList = screen.getByTestId("candidate-dependencies");
      expect(dependencyList.querySelectorAll("button, input, textarea")).toHaveLength(0);
    });
  });

  describe("existing (anchor) items", () => {
    it("renders an anchored Epic's title as read-only text with the existing badge, and suppresses its edit controls", () => {
      const onChange = vi.fn();
      const value = makeDocument({
        epics: [makeEpic({ existingId: "epic-uuid-1" })],
      });
      renderWithProviders(<RoadmapCandidateBreakdown value={value} onChange={onChange} />);

      expect(screen.queryByDisplayValue("Add dark mode")).not.toBeInTheDocument();
      expect(screen.getByText("Add dark mode")).toBeInTheDocument();
      expect(screen.getByTestId("candidate-existing-badge")).toHaveTextContent("Existing epic");
      expect(screen.queryByTestId("candidate-epic-description-0")).not.toBeInTheDocument();
      expect(screen.queryByTestId("candidate-epic-motivation-0")).not.toBeInTheDocument();
      expect(screen.queryByTestId("candidate-epic-priority-badge-0")).not.toBeInTheDocument();
      expect(screen.queryByTestId("candidate-epic-milestone-select-0")).not.toBeInTheDocument();
      expect(screen.getByTestId("candidate-epic-remove-0")).toHaveAttribute(
        "aria-label",
        "Remove epic from proposal",
      );
    });

    it("renders an anchored Story's title as read-only text with the existing badge, and suppresses its edit controls", () => {
      const onChange = vi.fn();
      const value = makeDocument({
        epics: [
          makeEpic({
            stories: [
              {
                title: "Theme toggle",
                description: "",
                existingId: "story-uuid-1",
                tasks: [],
              },
            ],
          }),
        ],
      });
      renderWithProviders(<RoadmapCandidateBreakdown value={value} onChange={onChange} />);

      expect(screen.queryByDisplayValue("Theme toggle")).not.toBeInTheDocument();
      expect(screen.getByText("Theme toggle")).toBeInTheDocument();
      expect(screen.getAllByTestId("candidate-existing-badge")).toHaveLength(1);
      expect(screen.queryByTestId("candidate-story-description-0-0")).not.toBeInTheDocument();
      expect(screen.queryByTestId("candidate-story-priority-badge-0-0")).not.toBeInTheDocument();
      expect(screen.getByTestId("candidate-story-remove-0-0")).toHaveAttribute(
        "aria-label",
        "Remove story from proposal",
      );
    });

    it("keeps Add Story / Add Task available under an existing Epic/Story", () => {
      const onChange = vi.fn();
      const value = makeDocument({
        epics: [
          makeEpic({
            existingId: "epic-uuid-1",
            stories: [
              {
                title: "Theme toggle",
                description: "",
                existingId: "story-uuid-1",
                tasks: [],
              },
            ],
          }),
        ],
      });
      renderWithProviders(<RoadmapCandidateBreakdown value={value} onChange={onChange} />);

      expect(screen.getByTestId("candidate-add-story-0")).toBeEnabled();
      expect(screen.getByTestId("candidate-add-task-0-0")).toBeEnabled();
    });

    it("adds a new Story under an existing Epic, keeping the Epic's existingId", async () => {
      const user = userEvent.setup();
      const onChange = vi.fn();
      const value = makeDocument({ epics: [makeEpic({ existingId: "epic-uuid-1" })] });
      renderWithProviders(<RoadmapCandidateBreakdown value={value} onChange={onChange} />);

      await user.click(screen.getByTestId("candidate-add-story-0"));

      const calls = onChange.mock.calls;
      const lastCall = calls[calls.length - 1][0] as RoadmapCandidatesDocument;
      expect(lastCall.epics[0].existingId).toBe("epic-uuid-1");
      expect(lastCall.epics[0].stories).toHaveLength(2);
      expect(lastCall.epics[0].stories[1]).toEqual({ title: "", description: "", tasks: [] });
    });

    it("shows 'Not found in this project' for an anchor with a blank title", () => {
      const onChange = vi.fn();
      const value = makeDocument({ epics: [makeEpic({ title: "", existingId: "epic-uuid-1" })] });
      renderWithProviders(<RoadmapCandidateBreakdown value={value} onChange={onChange} />);

      expect(screen.getByTestId("candidate-existing-missing")).toHaveTextContent(
        "Not found in this project",
      );
    });

    it("switches the heading to 'Proposed Roadmap Extension' when any top-level entry is an anchor", () => {
      const onChange = vi.fn();
      const value = makeDocument({ epics: [makeEpic({ existingId: "epic-uuid-1" })] });
      renderWithProviders(<RoadmapCandidateBreakdown value={value} onChange={onChange} />);

      expect(screen.getByText("Proposed Roadmap Extension (1)")).toBeInTheDocument();
    });

    it("keeps the 'Proposed Roadmap Breakdown' heading when no top-level entry is an anchor", () => {
      const onChange = vi.fn();
      renderWithProviders(<RoadmapCandidateBreakdown value={makeDocument()} onChange={onChange} />);

      expect(screen.getByText("Proposed Roadmap Breakdown (1)")).toBeInTheDocument();
    });

    it("removing an anchored Epic drops its subtree's dependencies but leaves an edge between two surviving keys", async () => {
      const user = userEvent.setup();
      const onChange = vi.fn();
      const anchorEpic = makeEpic({
        title: "Anchor Epic",
        key: "epic-anchor",
        existingId: "epic-uuid-1",
        stories: [
          {
            title: "Anchor Story",
            description: "",
            key: "story-anchor",
            existingId: "story-uuid-1",
            tasks: [
              {
                title: "Anchor Task",
                description: "",
                key: "task-anchor",
                existingId: "task-uuid-1",
              },
            ],
          },
        ],
      });
      const survivorEpic = makeEpic({
        title: "Survivor Epic",
        key: "epic-survivor",
        stories: [
          {
            title: "Survivor Story",
            description: "",
            key: "story-survivor",
            tasks: [{ title: "Survivor Task", description: "", key: "task-survivor" }],
          },
        ],
      });
      const value = makeDocument({
        epics: [anchorEpic, survivorEpic],
        dependencies: [
          { blocking: "task-anchor", blocked: "story-survivor" },
          { blocking: "story-survivor", blocked: "task-survivor" },
        ],
      });
      renderWithProviders(<RoadmapCandidateBreakdown value={value} onChange={onChange} />);

      await user.click(screen.getByTestId("candidate-epic-remove-0"));

      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({
          epics: [survivorEpic],
          dependencies: [{ blocking: "story-survivor", blocked: "task-survivor" }],
        }),
      );
    });
  });
});
