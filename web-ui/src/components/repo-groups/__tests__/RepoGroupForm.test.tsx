import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RepoGroupForm from "@/components/repo-groups/RepoGroupForm";

const availableRepos = [
  { id: "r1", name: "r1" },
  { id: "r2", name: "r2" },
];

describe("RepoGroupForm", () => {
  it("save is disabled until at least one member is selected", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();

    renderWithProviders(
      <RepoGroupForm availableRepos={availableRepos} onSubmit={onSubmit} />,
    );

    // Save starts disabled (name empty, no members).
    const saveButton = screen.getByRole("button", { name: /save/i });
    expect(saveButton).toBeDisabled();

    // Fill the name — still disabled because no members are selected yet.
    await user.type(screen.getByLabelText(/name/i), "g");
    expect(saveButton).toBeDisabled();

    // Pick a member — now Save unlocks.
    await user.click(screen.getByLabelText(/r1/i));
    expect(saveButton).toBeEnabled();
  });

  it("submit emits payload with members in selected order", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();

    renderWithProviders(
      <RepoGroupForm availableRepos={availableRepos} onSubmit={onSubmit} />,
    );

    await user.type(screen.getByLabelText(/name/i), "g");
    // Click r2 first, then r1 — selection order should win, NOT availableRepos order.
    await user.click(screen.getByLabelText(/r2/i));
    await user.click(screen.getByLabelText(/r1/i));

    await user.click(screen.getByRole("button", { name: /save/i }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "g",
        memberRepoIds: ["r2", "r1"],
      }),
    );
  });

  it("seeds form from `initial` and preserves member order on submit", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();

    renderWithProviders(
      <RepoGroupForm
        initial={{
          name: "preset",
          agentImage: "img:1",
          description: "desc",
          memberRepoIds: ["r2", "r1"],
        }}
        availableRepos={availableRepos}
        onSubmit={onSubmit}
      />,
    );

    expect(screen.getByLabelText(/name/i)).toHaveValue("preset");
    expect(screen.getByLabelText(/agent image/i)).toHaveValue("img:1");
    expect(screen.getByLabelText(/description/i)).toHaveValue("desc");
    expect(screen.getByLabelText(/r2/i)).toBeChecked();
    expect(screen.getByLabelText(/r1/i)).toBeChecked();

    await user.click(screen.getByRole("button", { name: /save/i }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "preset",
        agentImage: "img:1",
        description: "desc",
        memberRepoIds: ["r2", "r1"],
      }),
    );
  });

  it("renders the custom dind image field unconditionally and submits its value", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();

    renderWithProviders(
      <RepoGroupForm availableRepos={availableRepos} onSubmit={onSubmit} />,
    );

    // No per-group Docker toggle exists (Docker is derived from members), so the
    // dind field is always visible — unlike the GitRepo dialog where it is gated.
    const dindInput = screen.getByLabelText(/custom dind image/i);
    expect(dindInput).toBeInTheDocument();

    await user.type(screen.getByLabelText(/name/i), "g");
    await user.click(screen.getByLabelText(/r1/i));
    await user.type(dindInput, "registry.example/dind:custom");
    await user.click(screen.getByRole("button", { name: /save/i }));

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ dindImage: "registry.example/dind:custom" }),
    );
  });

  it("seeds the dind field from `initial`", () => {
    renderWithProviders(
      <RepoGroupForm
        initial={{ name: "preset", dindImage: "seeded/dind:tag" }}
        availableRepos={availableRepos}
        onSubmit={vi.fn()}
      />,
    );

    expect(screen.getByLabelText(/custom dind image/i)).toHaveValue("seeded/dind:tag");
  });
});
