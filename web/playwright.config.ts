import { defineConfig } from '@playwright/test';

// PW_CHANNEL=chrome runs the installed Google Chrome instead of Playwright's own Chromium build.
const channel = process.env.PW_CHANNEL;

export default defineConfig({
  testDir: 'e2e',
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:4173',
    ...(channel ? { channel } : {}),
  },
  webServer: {
    command: 'npm run preview -- --port 4173 --strictPort',
    url: 'http://localhost:4173',
    reuseExistingServer: false,
  },
});
