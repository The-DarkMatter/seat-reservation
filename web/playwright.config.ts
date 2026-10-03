import { defineConfig, devices } from '@playwright/test';

// End-to-end tests against a running stack (docker compose up, or the live URL).
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: true,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 860 } }, testIgnore: /phone.spec.ts/ },
    { name: 'phone', use: { ...devices['Pixel 7'] }, testMatch: /phone\.spec\.ts/ },
  ],
});
