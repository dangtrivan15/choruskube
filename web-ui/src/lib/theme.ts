import { config } from "@/config";

/** Cookie name shared across ecosystem apps for consistent theming. */
export const THEME_COOKIE = "theme";

/** Fallback when cookie is missing, unreadable, or blocked. */
export const DEFAULT_THEME: Theme = "light";

export type Theme = "dark" | "light";

/**
 * Read a cookie value by name.
 * Returns null if the cookie is absent or document.cookie is blocked.
 *
 * SYNC: The FOUC script in index.html duplicates this regex.
 *       If you change the pattern, update the script too.
 */
export function getCookie(name: string): string | null {
  try {
    const match = document.cookie.match(
      new RegExp("(?:^|; )" + name + "=([^;]*)"),
    );
    return match ? decodeURIComponent(match[1]) : null;
  } catch {
    return null; // cookie access blocked by browser privacy settings
  }
}

/**
 * Derive the root domain from appHost for cross-subdomain cookie sharing.
 * e.g. "app.choruskube.com" → ".choruskube.com"
 *      "choruskube.com"     → ".choruskube.com"
 * Returns empty string for localhost / IPs (no domain attr needed).
 */
function rootDomain(): string {
  const host = config.appHost;
  if (!host) return "";
  if (host === "localhost" || /^\d+(\.\d+){3}$/.test(host)) return "";
  const parts = host.split(".");
  if (parts.length < 2) return "";
  // Already a root domain (e.g. "choruskube.com") — use as-is
  // Subdomain (e.g. "app.choruskube.com") — drop the first label
  const root = parts.length > 2 ? parts.slice(1).join(".") : host;
  return `.${root}`;
}

/**
 * Write a cookie. 1-year max-age, SameSite=Lax, path=/ for ecosystem sharing.
 * When appHost is configured, infers the root domain and sets domain + Secure
 * so the cookie is shared across subdomains (e.g. app.choruskube.com ↔ auth.choruskube.com).
 * Silently no-ops if cookies are blocked.
 */
export function setCookie(name: string, value: string): void {
  try {
    const base = `${name}=${encodeURIComponent(value)};path=/;max-age=31536000;SameSite=Lax`;
    const domain = rootDomain();
    if (domain) {
      document.cookie = `${base};domain=${domain};Secure`;
    } else {
      document.cookie = base;
    }
  } catch {
    // cookie write blocked -- theme still works in-memory for this session
  }
}
