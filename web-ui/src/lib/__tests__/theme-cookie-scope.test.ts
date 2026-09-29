/**
 * Pins the exact cookie attribute string setCookie() writes: any surface sharing
 * this cookie on a sibling host of the same root domain depends on this staying stable.
 */
import { describe, it, expect, afterEach, vi } from "vitest";

const configState = vi.hoisted(() => ({ appHost: undefined as string | undefined }));

vi.mock("@/config", () => ({
  config: configState,
}));

import { setCookie } from "@/lib/theme";

describe("setCookie root-domain scope", () => {
  let cookieSpy: ReturnType<typeof vi.fn>;
  let originalCookieDescriptor: PropertyDescriptor | undefined;

  afterEach(() => {
    if (originalCookieDescriptor) {
      Object.defineProperty(document, "cookie", originalCookieDescriptor);
    }
    configState.appHost = undefined;
  });

  function stubCookieSetter() {
    originalCookieDescriptor = Object.getOwnPropertyDescriptor(
      Document.prototype,
      "cookie",
    );
    cookieSpy = vi.fn();
    Object.defineProperty(document, "cookie", {
      configurable: true,
      get: () => "",
      set: cookieSpy,
    });
  }

  it.each([["app.example.com"], ["example.com"]])(
    "appHost=%s writes a domain-scoped cookie on the two-label root",
    (appHost) => {
      configState.appHost = appHost;
      stubCookieSetter();

      setCookie("theme", "dark");

      expect(cookieSpy).toHaveBeenCalledWith(
        "theme=dark;path=/;max-age=31536000;SameSite=Lax;domain=.example.com;Secure",
      );
    },
  );

  it("appHost with a deeper subdomain drops only the first label", () => {
    configState.appHost = "a.b.example.com";
    stubCookieSetter();

    setCookie("theme", "dark");

    expect(cookieSpy).toHaveBeenCalledWith(
      "theme=dark;path=/;max-age=31536000;SameSite=Lax;domain=.b.example.com;Secure",
    );
  });

  it.each([["localhost"], ["127.0.0.1"], ["intranet"], [""], [undefined]])(
    "appHost=%s writes a host-only cookie with no domain/Secure attrs",
    (appHost) => {
      configState.appHost = appHost;
      stubCookieSetter();

      setCookie("theme", "dark");

      expect(cookieSpy).toHaveBeenCalledWith(
        "theme=dark;path=/;max-age=31536000;SameSite=Lax",
      );
    },
  );
});
