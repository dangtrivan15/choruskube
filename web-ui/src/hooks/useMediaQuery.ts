import { useState, useEffect } from "react";

/** Phone tier — matches the compact header/bottom-sheet layout. */
export const MOBILE_QUERY = "(max-width: 767px)";
/** Desktop tier — matches the docked, resizable node panel. */
export const DOCKED_PANEL_QUERY = "(min-width: 1024px)";

/**
 * Generic media-query hook backing `useMobileBreakpoint` and the docked-panel
 * tier query. Reads the current match during render (so a changed `query`
 * takes effect immediately) and subscribes to further changes.
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => {
    if (typeof window === "undefined") return false;
    return window.matchMedia(query).matches;
  });

  useEffect(() => {
    const mql = window.matchMedia(query);
    setMatches(mql.matches);
    const handler = (e: MediaQueryListEvent) => setMatches(e.matches);
    mql.addEventListener("change", handler);
    return () => mql.removeEventListener("change", handler);
  }, [query]);

  return matches;
}
