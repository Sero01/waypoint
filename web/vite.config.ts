import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// The service is a plain Spring Boot app with no CORS configuration, and
// adding some would mean changing Java to suit a dev server. Proxying instead
// keeps development same-origin, so the code that runs against the proxy is
// byte-for-byte the code that runs in production.
const TARGET = process.env.WAYPOINT_API ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: Object.fromEntries(
      ['/search', '/stats', '/health'].map((path) => [
        path,
        { target: TARGET, changeOrigin: true },
      ]),
    ),
  },
  // Spring Boot serves anything under `resources/static` from the context
  // root, so a production build drops straight into the service jar and is
  // served same-origin with no extra process and no CORS.
  build: {
    outDir: '../service/src/main/resources/static',
    emptyOutDir: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test-setup.ts',
    css: false,
  },
});
