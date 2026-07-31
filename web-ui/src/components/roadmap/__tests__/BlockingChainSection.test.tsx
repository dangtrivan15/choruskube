import { describe, it, expect } from "vitest";
import { render, screen, within } from "@testing-library/react";
import BlockingChainSection from "@/components/roadmap/BlockingChainSection";
import type { BlockingChainNode, BlockingChainResponse } from "@/lib/types";

const leafNode: BlockingChainNode = {
  itemType: "task",
  itemId: "task-leaf",
  title: "Provision database",
  status: "in_progress",
  blockedBy: [],
};

const doneNode: BlockingChainNode = {
  itemType: "story",
  itemId: "story-mid",
  title: "Set up infra",
  status: "done",
  blockedBy: [leafNode],
};

const chain: BlockingChainResponse = {
  itemType: "task",
  itemId: "task-root",
  title: "Deploy service",
  status: "backlog",
  readiness: "BLOCKED",
  blockedBy: [doneNode],
  truncated: false,
};

describe("BlockingChainSection", () => {
  it("renders nothing when not loading and there is no chain data", () => {
    const { container } = render(<BlockingChainSection chain={undefined} isLoading={false} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing when not loading and blockedBy is empty", () => {
    const emptyChain: BlockingChainResponse = { ...chain, blockedBy: [] };
    const { container } = render(<BlockingChainSection chain={emptyChain} isLoading={false} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders a loading indicator while isLoading is true", () => {
    render(<BlockingChainSection chain={undefined} isLoading={true} />);
    expect(screen.getByTestId("roadmap-blocking-chain-loading")).toBeInTheDocument();
    expect(screen.queryByTestId("roadmap-blocking-chain")).not.toBeInTheDocument();
  });

  it("renders nested entries at the correct depth, with the child inside the parent's subtree", () => {
    render(<BlockingChainSection chain={chain} isLoading={false} />);

    const section = screen.getByTestId("roadmap-blocking-chain");
    expect(section).toHaveTextContent("Set up infra");

    const nodes = within(section).getAllByTestId("roadmap-blocking-chain-node");
    expect(nodes).toHaveLength(2);

    const [parentNode, childNode] = nodes;
    expect(parentNode).toHaveTextContent("Set up infra");
    expect(childNode).toHaveTextContent("Provision database");
    // The leaf node is nested inside the parent node's own DOM subtree.
    expect(within(parentNode).getByText("Provision database")).toBeInTheDocument();
  });

  it("renders the truncation notice when truncated is true", () => {
    const truncatedChain: BlockingChainResponse = { ...chain, truncated: true };
    render(<BlockingChainSection chain={truncatedChain} isLoading={false} />);
    expect(screen.getByTestId("roadmap-blocking-chain-truncated")).toBeInTheDocument();
  });

  it("does not render the truncation notice when truncated is false", () => {
    render(<BlockingChainSection chain={chain} isLoading={false} />);
    expect(screen.queryByTestId("roadmap-blocking-chain-truncated")).not.toBeInTheDocument();
  });

  it("visually distinguishes a done node from a not-done node", () => {
    render(<BlockingChainSection chain={chain} isLoading={false} />);

    expect(screen.getByTestId("roadmap-blocking-chain-node-status-done")).toHaveTextContent("done");
    expect(screen.getByTestId("roadmap-blocking-chain-node-status-pending")).toHaveTextContent(
      "in progress",
    );
  });
});
