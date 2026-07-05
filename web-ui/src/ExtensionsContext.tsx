import { createContext, useContext } from "react";
import type { ReactNode } from "react";
import type { AppExtensions } from "@/extensions";

// Default {}: OSS reads empty extensions everywhere. There is exactly one provider, mounted
// at the root by bootstrap(), so consumers never need to thread props through the tree.
const ExtensionsContext = createContext<AppExtensions>({});

export function ExtensionsProvider({ value, children }: { value: AppExtensions; children: ReactNode }) {
  return <ExtensionsContext.Provider value={value}>{children}</ExtensionsContext.Provider>;
}

/** Read the injected extensions. Returns {} in OSS (and in tests with no provider). */
export function useExtensions(): AppExtensions {
  return useContext(ExtensionsContext);
}
