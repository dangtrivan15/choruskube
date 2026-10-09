import path from "path"
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The server keeps only the current build's files, so a JS file fetched after page load (a lazy
// chunk, a worker) 404s in any tab opened before a deploy. docs/decisions/2026-10-09---01-web-ui-ships-no-lazy-chunks.md
function noLazyChunks(): Plugin {
  return {
    name: "no-lazy-chunks",
    apply: "build",
    generateBundle(_options, bundle) {
      const chunks = Object.values(bundle).filter((file) => file.type === "chunk")
      const loaded = new Set<string>()
      const pending = chunks.filter((chunk) => chunk.isEntry).map((chunk) => chunk.fileName)
      for (let fileName = pending.pop(); fileName !== undefined; fileName = pending.pop()) {
        if (loaded.has(fileName)) continue
        loaded.add(fileName)
        const file = bundle[fileName]
        if (file?.type === "chunk") pending.push(...file.imports)
      }
      // Workers and `?url` scripts are emitted as assets, not chunks, and are fetched after load too.
      const lazy = Object.values(bundle)
        .filter((file) => (file.type === "chunk" ? !loaded.has(file.fileName) : /\.m?js$/.test(file.fileName)))
        .map((file) => file.fileName)
      if (lazy.length > 0) {
        this.error(
          `These files would be fetched after page load: ${lazy.join(", ")}. ` +
            "See docs/decisions/2026-10-09---01-web-ui-ships-no-lazy-chunks.md",
        )
      }
    },
  }
}

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    noLazyChunks(),
  ],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  build: {
    rollupOptions: {
      output: {
        // CSS stays out: a separate library stylesheet would load before the app's and flip the cascade.
        manualChunks(id) {
          if (id.includes("/node_modules/") && !/\.css($|\?)/.test(id)) return "vendor"
        },
      },
    },
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/internal': 'http://localhost:8080',
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
})
