/*
 * Copyright 2026 Aman Mittal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { defineConfig } from '@playwright/test';

/*
 * The browser suite: the real consoles in a real browser, talking to a real
 * backend. It is not a mock of anything. Start the stack first (see
 * .github/workflows/e2e.yml), then:
 *
 *   E2E_DEMO_PASSWORD=... npx playwright test
 *
 * The same specs run twice, once against each console, as two projects.
 */
const origin = process.env.E2E_BASE_URL ?? 'http://localhost:4300';

export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  // One browser at a time: every spec signs in to the same demo tenant.
  workers: 1,
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  // In CI each shard writes a blob report; a later job merges them into one.
  reporter: process.env.CI ? [['github'], ['blob']] : 'list',
  use: { trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: [
    { name: 'angular', use: { baseURL: `${origin}/angular/` } },
    { name: 'react', use: { baseURL: `${origin}/react/` } },
  ],
  webServer: {
    // The built consoles, laid out as they are published.
    command: 'python3 -m http.server 4300 --directory ../site',
    url: `${origin}/`,
    reuseExistingServer: true,
    timeout: 30_000,
  },
});
