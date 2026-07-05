export type AppMode = "production" | "development";

export interface AppConfig {
  apiBaseUrl: string;
  appHost?: string;
  oidc: {
    url: string;
    realm: string;
    clientId: string;
  };
  dagPlaygroundEnabled: boolean;
  // `production` is the safe default: keeps native Web Crypto and PKCE-S256 on,
  // failing loudly on non-secure contexts rather than silently degrading auth.
  // Only set to `development` for local stacks where the SPA is reached over
  // plain HTTP from a non-localhost origin (see lib/crypto-shim.ts).
  mode: AppMode;
}

interface RawAppConfig extends Omit<AppConfig, "dagPlaygroundEnabled" | "mode"> {
  dagPlaygroundEnabled?: boolean | string;
  mode?: string;
}

declare global {
  interface Window {
    __APP_CONFIG__?: RawAppConfig;
  }
}

function normalizeFlag(value: boolean | string | undefined): boolean {
  return value === true || value === "true";
}

function normalizeMode(value: string | undefined): AppMode {
  // Fail-closed: any value other than the explicit string "development"
  // (including typos, future modes, or unset) resolves to "production".
  return value === "development" ? "development" : "production";
}

function loadConfig(): AppConfig {
  if (!window.__APP_CONFIG__) {
    // Fallback for dev mode (Vite dev server) — playground enabled, dev mode.
    return {
      apiBaseUrl: "/api/v1",
      oidc: { url: "", realm: "", clientId: "" },
      dagPlaygroundEnabled: true,
      mode: "development",
    };
  }
  const raw = window.__APP_CONFIG__;
  return {
    ...raw,
    dagPlaygroundEnabled: normalizeFlag(raw.dagPlaygroundEnabled),
    mode: normalizeMode(raw.mode),
  };
}

export const config = loadConfig();
