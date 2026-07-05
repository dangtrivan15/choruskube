import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/** Extract `owner/repo` from a full git URL (e.g. `https://github.com/org/repo.git` → `org/repo`). */
export function repoDisplayName(url: string): string {
  try {
    return url.replace(/^https?:\/\/[^/]+\//, "").replace(/\.git$/, "");
  } catch {
    return url;
  }
}

/** Format a byte count as a human-readable size string (e.g. 512 → "512 B", 2048 → "2.0 KB"). */
export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}
