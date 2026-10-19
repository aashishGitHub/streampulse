import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

const stripPrefix = (prefix: string) => (path: string) => path.replace(new RegExp(`^${prefix}`), '');

// None of the backends configure CORS; proxying keeps the browser same-origin (ADR-0018).
const proxy = (prefix: string, target: string) => ({
  [prefix]: { target, changeOrigin: true, rewrite: stripPrefix(prefix) },
});

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      ...proxy('/api/query', 'http://localhost:8082'),
      ...proxy('/api/control', 'http://localhost:8085'),
      ...proxy('/api/alerts', 'http://localhost:8086'),
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
  },
});
