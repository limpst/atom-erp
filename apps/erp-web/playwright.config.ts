import { defineConfig, devices } from '@playwright/test';

/**
 * E2E — 기본: 실제 백엔드(내장 PostgreSQL dev 서버, scripts/backend.mjs)를 띄우고 Vite dev 서버로 구동.
 * 이미 떠 있는 서버가 있으면 재사용(reuseExistingServer). 로컬 전용(127.0.0.1).
 * 브라우저: 설치된 Microsoft Edge(channel msedge) — 별도 다운로드 불필요. PW_CHANNEL 로 변경 가능.
 */
const backendPort = Number(process.env.ATOM_DEV_PORT ?? 8081);

export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  outputDir: 'test-results',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    viewport: { width: 1440, height: 900 },
  },
  projects: [{ name: 'edge', use: { ...devices['Desktop Edge'], channel: process.env.PW_CHANNEL ?? 'msedge', viewport: { width: 1440, height: 900 } } }],
  webServer: [
    {
      command: 'node scripts/backend.mjs',
      url: `http://127.0.0.1:${backendPort}/actuator/health`,
      // 시드 완료 표식까지 기다린다(health 는 시드 전에 UP)
      wait: { stdout: /\[dev-server\] READY/ },
      reuseExistingServer: true,
      timeout: 600_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      command: 'npm run dev',
      url: 'http://127.0.0.1:5173',
      reuseExistingServer: true,
      timeout: 60_000,
    },
  ],
});
