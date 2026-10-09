import { createContext, useContext, useLayoutEffect } from "react";

interface MainLayoutContextValue {
  setFullBleed(on: boolean): void;
}

const MainLayoutContext = createContext<MainLayoutContextValue>({
  setFullBleed: () => {},
});

export const MainLayoutProvider = MainLayoutContext.Provider;

/**
 * Opts the calling page into an unpadded, non-scrolling `<main>` — a canvas page
 * (run detail) wants edge-to-edge space and its own scroll regions instead of the
 * shell's default padded/scrolling one. A layout effect (not a plain effect) sets
 * it before paint, so the page never flashes the padded, scrolling main for one frame.
 */
export function useFullBleedMain(): void {
  const { setFullBleed } = useContext(MainLayoutContext);
  useLayoutEffect(() => {
    setFullBleed(true);
    return () => setFullBleed(false);
  }, [setFullBleed]);
}
