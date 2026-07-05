import { useSyncExternalStore } from "react";
import type { ComponentType } from "react";

export interface Impersonation {
  orgId: string;
  orgSlug: string;
}

export interface ImpersonationStore {
  getSnapshot(): Impersonation | null;
  getImpersonation(): Impersonation | null;
  subscribe(callback: () => void): () => void;
}

/** Injected impersonation extension: the external store plus the banner to render. */
export interface ImpersonationExtension {
  store: ImpersonationStore;
  Banner: ComponentType;
}

// Module-level singletons, installed by bootstrap() when an injected extension passes an
// impersonation extension. Null in OSS. These live at module scope (not React context)
// because getImpersonation() is read from the non-React fetch layer (lib/api), which has no
// component tree to read context from. bootstrap() installs them before first render, so the
// references stay stable for useSyncExternalStore.
let store: ImpersonationStore | undefined;
let InjectedBanner: ComponentType | undefined;

/** Installed once by bootstrap(). Passing undefined (OSS) leaves impersonation inert. */
export function setImpersonation(ext: ImpersonationExtension | undefined): void {
  store = ext?.store;
  InjectedBanner = ext?.Banner;
}

// Stable module-level no-ops. getSnapshot MUST be a stable reference or
// useSyncExternalStore loops; never inline these.
const noopSubscribe = (): (() => void) => () => {};
const noopSnapshot = (): Impersonation | null => null;

/** Current impersonation outside React (fetch layer). Null in OSS (no injected impl). */
export function getImpersonation(): Impersonation | null {
  return store?.getImpersonation() ?? null;
}

/** React hook — current impersonation, re-rendering on change. Always null in OSS (nothing injected). */
export function useImpersonation(): Impersonation | null {
  return useSyncExternalStore(
    store?.subscribe ?? noopSubscribe,
    store?.getSnapshot ?? noopSnapshot,
    noopSnapshot,
  );
}

/** Mount point for an injected banner. Renders nothing in OSS. */
export function ImpersonationBannerSlot() {
  return InjectedBanner ? <InjectedBanner /> : null;
}
