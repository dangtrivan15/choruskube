import { useCallback, useEffect, useRef, useState } from "react";

export interface UseResizableOptions {
  /** Which side the handle is on (determines drag direction) */
  side: "left" | "right";
  /** Default width in pixels */
  defaultWidth: number;
  /** Minimum width in pixels */
  minWidth: number;
  /** Maximum width in pixels */
  maxWidth: number;
  /** localStorage key for persistence (omit to disable persistence) */
  storageKey?: string;
}

export interface UseResizableReturn {
  /** Current width in pixels */
  width: number;
  /** Whether the user is currently dragging */
  isDragging: boolean;
  /** Call from the handle's onPointerDown */
  handlePointerDown: (e: React.PointerEvent) => void;
}

function readStoredWidth(
  storageKey: string | undefined,
  defaultWidth: number,
  minWidth: number,
  maxWidth: number,
): number {
  if (!storageKey) return defaultWidth;
  try {
    const raw = localStorage.getItem(storageKey);
    if (raw === null) return defaultWidth;
    const parsed = Number(raw);
    if (!Number.isFinite(parsed)) return defaultWidth;
    return Math.min(maxWidth, Math.max(minWidth, parsed));
  } catch {
    return defaultWidth;
  }
}

export function useResizable({
  side,
  defaultWidth,
  minWidth,
  maxWidth,
  storageKey,
}: UseResizableOptions): UseResizableReturn {
  const [width, setWidth] = useState<number>(() =>
    readStoredWidth(storageKey, defaultWidth, minWidth, maxWidth),
  );
  const [isDragging, setIsDragging] = useState(false);

  // Refs to keep latest values available inside event listeners without
  // re-registering them on every render.
  const startXRef = useRef(0);
  const startWidthRef = useRef(width);
  const sideRef = useRef(side);
  const minRef = useRef(minWidth);
  const maxRef = useRef(maxWidth);

  // Keep refs in sync.
  sideRef.current = side;
  minRef.current = minWidth;
  maxRef.current = maxWidth;

  // Persist to localStorage whenever width changes.
  useEffect(() => {
    if (storageKey) {
      try {
        localStorage.setItem(storageKey, String(width));
      } catch {
        // Storage full or unavailable — ignore.
      }
    }
  }, [width, storageKey]);

  const handlePointerMove = useCallback((e: PointerEvent) => {
    const delta = e.clientX - startXRef.current;
    // For a handle on the right side of a panel, moving right = wider.
    // For a handle on the left side of a panel, moving left = wider.
    const newWidth =
      sideRef.current === "right"
        ? startWidthRef.current + delta
        : startWidthRef.current - delta;

    setWidth(Math.min(maxRef.current, Math.max(minRef.current, newWidth)));
  }, []);

  const handlePointerUp = useCallback(() => {
    setIsDragging(false);
    document.removeEventListener("pointermove", handlePointerMove);
    document.removeEventListener("pointerup", handlePointerUp);
  }, [handlePointerMove]);

  const handlePointerDown = useCallback(
    (e: React.PointerEvent) => {
      e.preventDefault();
      startXRef.current = e.clientX;
      startWidthRef.current = width;
      setIsDragging(true);

      // Capture pointer for reliable tracking even if cursor leaves the handle.
      (e.target as HTMLElement).setPointerCapture(e.pointerId);

      document.addEventListener("pointermove", handlePointerMove);
      document.addEventListener("pointerup", handlePointerUp);
    },
    [width, handlePointerMove, handlePointerUp],
  );

  // Clean up listeners on unmount.
  useEffect(() => {
    return () => {
      document.removeEventListener("pointermove", handlePointerMove);
      document.removeEventListener("pointerup", handlePointerUp);
    };
  }, [handlePointerMove, handlePointerUp]);

  return { width, isDragging, handlePointerDown };
}
