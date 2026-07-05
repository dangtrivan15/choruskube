import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import FileUploadZone from "../FileUploadZone";

describe("FileUploadZone", () => {
  const onFilesChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders the drop zone", () => {
    renderWithProviders(<FileUploadZone onFilesChange={onFilesChange} />);

    expect(screen.getByTestId("file-upload-zone")).toBeInTheDocument();
    expect(screen.getByText("Attach files — drag & drop or click to browse")).toBeInTheDocument();
  });

  it("calls onFilesChange when files are selected via input", async () => {
    const user = userEvent.setup();
    renderWithProviders(<FileUploadZone onFilesChange={onFilesChange} />);

    const file = new File(["content"], "test.txt", { type: "text/plain" });
    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;

    await user.upload(fileInput, file);

    expect(onFilesChange).toHaveBeenCalledWith([file]);
  });

  it("disabled prop prevents click interaction", () => {
    renderWithProviders(<FileUploadZone onFilesChange={onFilesChange} disabled />);

    const zone = screen.getByTestId("file-upload-zone");
    expect(zone.className).toContain("cursor-not-allowed");
    expect(zone.className).toContain("opacity-50");

    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
    expect(fileInput).toBeDisabled();
  });

  it("skips files exceeding maxSizeMB", async () => {
    renderWithProviders(<FileUploadZone onFilesChange={onFilesChange} maxSizeMB={1} />);

    // 2MB file — over the 1MB limit
    const bigFile = new File([new ArrayBuffer(2 * 1024 * 1024)], "big.bin", { type: "application/octet-stream" });
    Object.defineProperty(bigFile, "size", { value: 2 * 1024 * 1024 });

    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
    const user = userEvent.setup();
    await user.upload(fileInput, bigFile);

    // onFilesChange should be called with an empty array (file was skipped)
    expect(onFilesChange).toHaveBeenCalledWith([]);
  });

  it("truncates list at maxFiles", async () => {
    const user = userEvent.setup();
    renderWithProviders(<FileUploadZone onFilesChange={onFilesChange} maxFiles={2} />);

    const files = [
      new File(["a"], "a.txt", { type: "text/plain" }),
      new File(["b"], "b.txt", { type: "text/plain" }),
      new File(["c"], "c.txt", { type: "text/plain" }),
    ];

    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
    await user.upload(fileInput, files);

    // Only the first 2 files should be passed to onFilesChange
    const lastCall = onFilesChange.mock.calls[onFilesChange.mock.calls.length - 1][0];
    expect(lastCall).toHaveLength(2);
    expect(lastCall[0].name).toBe("a.txt");
    expect(lastCall[1].name).toBe("b.txt");
  });

  it("remove button removes the file and calls onFilesChange", async () => {
    const user = userEvent.setup();
    renderWithProviders(<FileUploadZone onFilesChange={onFilesChange} />);

    const file = new File(["content"], "remove-me.txt", { type: "text/plain" });
    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
    await user.upload(fileInput, file);

    // File should be listed
    expect(screen.getByText("remove-me.txt")).toBeInTheDocument();

    // Click the remove button
    const removeButton = screen.getByRole("button", { name: "Remove remove-me.txt" });
    await user.click(removeButton);

    // File should be removed from the list
    expect(screen.queryByText("remove-me.txt")).not.toBeInTheDocument();
    // onFilesChange should have been called with an empty array
    const lastCall = onFilesChange.mock.calls[onFilesChange.mock.calls.length - 1][0];
    expect(lastCall).toHaveLength(0);
  });

  it("shows file list after files are added", async () => {
    const user = userEvent.setup();
    renderWithProviders(<FileUploadZone onFilesChange={onFilesChange} />);

    const files = [
      new File(["a"], "file-one.txt", { type: "text/plain" }),
      new File(["b"], "file-two.txt", { type: "text/plain" }),
    ];

    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
    await user.upload(fileInput, files);

    expect(screen.getByText("file-one.txt")).toBeInTheDocument();
    expect(screen.getByText("file-two.txt")).toBeInTheDocument();
  });

  it("disabled remove button when disabled prop is true", async () => {
    const user = userEvent.setup();
    // First render enabled to add a file
    const { rerender } = renderWithProviders(<FileUploadZone onFilesChange={onFilesChange} />);

    const file = new File(["content"], "locked.txt", { type: "text/plain" });
    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
    await user.upload(fileInput, file);

    expect(screen.getByText("locked.txt")).toBeInTheDocument();

    // Re-render with disabled
    rerender(<FileUploadZone onFilesChange={onFilesChange} disabled />);

    const removeButton = screen.getByRole("button", { name: "Remove locked.txt" });
    expect(removeButton).toBeDisabled();
  });
});
