import { useSyncExternalStore } from "react";
import { type Theme } from "@/lib/theme";
import { themeStore } from "@/lib/themeStore";

export type { Theme };

/**
 * Delegates to the module-level themeStore (see themeStore.ts) so every
 * caller in the tab shares one live value instead of each holding its own
 * copy that only reconverges on reload.
 */
export function useTheme() {
  const theme = useSyncExternalStore(themeStore.subscribe, themeStore.getSnapshot);
  return { theme, toggle: themeStore.toggle };
}
