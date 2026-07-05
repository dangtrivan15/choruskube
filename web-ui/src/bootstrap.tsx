import "./lib/crypto-shim";
import { StrictMode } from "react";
import type { ReactNode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import App from "./App";
import { ExtensionsProvider } from "./ExtensionsContext";
import { setImpersonation } from "./lib/impersonation";
import type { AppExtensions } from "./extensions";

/**
 * Single composition root. The OSS entrypoint calls bootstrap() with no extensions; a downstream
 * extension entrypoint calls bootstrap({ routes, navItems, commands, impersonation,
 * keyboardSequences, appProviders }). Everything an extension contributes is visible in that one
 * call. Core never imports the extension source — the dependency arrow points extension → core only.
 */
export function bootstrap(extensions: AppExtensions = {}): void {
  // Install the impersonation singleton before first render so the non-React fetch layer can
  // read it synchronously (see lib/impersonation).
  setImpersonation(extensions.impersonation);

  const appProviders = extensions.appProviders ?? [];
  const wrapped: ReactNode = appProviders.reduceRight<ReactNode>(
    (children, Provider) => <Provider>{children}</Provider>,
    <App />,
  );

  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <ExtensionsProvider value={extensions}>{wrapped}</ExtensionsProvider>
    </StrictMode>,
  );
}
