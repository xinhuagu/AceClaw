import { defineConfig } from 'vitest/config';

// Vitest config lives separately from vite.config.ts to avoid the duplicate-
// vite-installation type clash that surfaces when Vitest brings its own
// nested vite peer (the two vite copies disagree on PluginOption types under
// exactOptionalPropertyTypes). Vitest auto-loads this file when running.
export default defineConfig({
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['tests/**/*.test.ts', 'tests/**/*.test.tsx'],
  },
});
