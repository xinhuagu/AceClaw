import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Tier 1 dashboard (epic #430). Plan F — SVG + Framer Motion, no React Flow.
// The dashboard talks to the daemon's WebSocket bridge (#431) on a separate
// port (default 3141), so dev-server and daemon co-exist without proxying.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    target: 'es2022',
  },
});
