import path from "path";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  test: {
    globals: true,
    environment: "happy-dom",
    setupFiles: ["./src/__tests__/setup.ts"],
    // `e2e/helpers/**` is included alongside `src/**` so the framework-agnostic
    // (non-Playwright-runtime-dependent) helpers under web-ui/e2e/helpers/ — e.g.
    // `uniqueName` in api-client.ts — can have plain Vitest unit tests co-located
    // with the source, matching this repo's existing unit-test convention,
    // without needing a running Playwright test (and therefore the full stack).
    include: ["src/**/*.test.{ts,tsx}", "e2e/helpers/**/*.test.ts"],
    css: false,
    testTimeout: 15000,
    coverage: {
      provider: "v8",
      reportsDirectory: "build/coverage",
      reporter: ["text", "html", "json"],
      thresholds: {
        lines: 60,
        functions: 60,
        branches: 60,
        statements: 60,
      },
    },
  },
});
