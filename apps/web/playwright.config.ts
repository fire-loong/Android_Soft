import { defineConfig } from '@playwright/test'

const apiUrl = 'http://127.0.0.1:8080'
const webUrl = 'http://127.0.0.1:5173'
const dbPassword = process.env.ACTIONLOOP_E2E_DB_PASSWORD

if (!dbPassword) {
  throw new Error('ACTIONLOOP_E2E_DB_PASSWORD is required; refusing to use a default database password')
}

const inheritedEnv = Object.fromEntries(
  Object.entries(process.env).filter((entry): entry is [string, string] => typeof entry[1] === 'string')
)

export default defineConfig({
  testDir: '../../tests/e2e',
  outputDir: './test-results',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }]
  ],
  use: {
    baseURL: webUrl,
    browserName: 'chromium',
    headless: true,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure'
  },
  webServer: [
    {
      command: 'java -jar ../../services/api/target/actionloop-api.jar',
      url: `${apiUrl}/actuator/health`,
      timeout: 240_000,
      reuseExistingServer: false,
      stdout: 'pipe',
      stderr: 'pipe',
      env: {
        ...inheritedEnv,
        ACTIONLOOP_API_PORT: '8080',
        ACTIONLOOP_DB_HOST: process.env.ACTIONLOOP_E2E_DB_HOST ?? '127.0.0.1',
        ACTIONLOOP_DB_PORT: process.env.ACTIONLOOP_E2E_DB_PORT ?? '5433',
        ACTIONLOOP_DB_NAME: process.env.ACTIONLOOP_E2E_DB_NAME ?? 'actionloop_e2e',
        ACTIONLOOP_DB_USER: process.env.ACTIONLOOP_E2E_DB_USER ?? 'postgres',
        ACTIONLOOP_DB_PASSWORD: dbPassword,
        ACTIONLOOP_REDIS_HOST: process.env.ACTIONLOOP_E2E_REDIS_HOST ?? '127.0.0.1',
        ACTIONLOOP_REDIS_PORT: process.env.ACTIONLOOP_E2E_REDIS_PORT ?? '6379',
        ACTIONLOOP_REDIS_PASSWORD: process.env.ACTIONLOOP_E2E_REDIS_PASSWORD ?? '',
        SPRING_DATA_REDIS_DATABASE: '15',
        ACTIONLOOP_AI_PROVIDER: 'deterministic',
        ACTIONLOOP_EMBEDDING_ENABLED: 'false',
        ACTIONLOOP_RERANK_ENABLED: 'false',
        ACTIONLOOP_SEED_DEMO: 'false',
        ACTIONLOOP_STORAGE_LOCAL_ROOT: '../../.dev-infra/e2e/storage',
        ACTIONLOOP_RL_REGISTER_MAX: '0',
        ACTIONLOOP_RL_LOGIN_MAX: '0',
        ACTIONLOOP_RL_REFRESH_MAX: '0'
      }
    },
    {
      command: 'pnpm dev --host 127.0.0.1 --port 5173 --strictPort',
      url: webUrl,
      timeout: 60_000,
      reuseExistingServer: false,
      stdout: 'pipe',
      stderr: 'pipe'
    }
  ]
})
