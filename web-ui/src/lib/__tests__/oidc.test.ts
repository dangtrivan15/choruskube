import { describe, it, expect } from "vitest";
import {
  isAuthEnabled,
  isAuthenticated,
  initAuth,
  getToken,
  getClaims,
  login,
  logout,
  refreshToken,
} from "@/lib/oidc";

describe("oidc stub (OSS core has no auth)", () => {
  it("reports auth disabled", () => {
    expect(isAuthEnabled()).toBe(false);
    expect(isAuthenticated()).toBe(false);
  });

  it("returns no token or claims", () => {
    expect(getToken()).toBeUndefined();
    expect(getClaims()).toBeUndefined();
  });

  it("login/logout/initAuth/refreshToken are no-ops that never throw", async () => {
    await expect(initAuth()).resolves.toBe(false);
    await expect(login()).resolves.toBeUndefined();
    await expect(logout()).resolves.toBeUndefined();
    await expect(refreshToken(30)).resolves.toBeUndefined();
  });
});
