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

import { expect, type APIRequestContext, type Page, type TestInfo } from '@playwright/test';

export const DEMO_EMAIL = 'admin@demo.local';

function password(): string {
  const value = process.env.E2E_DEMO_PASSWORD;
  if (!value) {
    throw new Error('Set E2E_DEMO_PASSWORD to the demo administrator password');
  }
  return value;
}

/** Signs in as the demo administrator and waits for the control tower. */
export async function signIn(page: Page): Promise<void> {
  await page.goto('./');
  await page.getByLabel('Password').fill(password());
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Control tower', level: 1 })).toBeVisible();
  await expect(page.getByTestId('tiles')).toBeVisible();
}

export async function signOut(page: Page): Promise<void> {
  await page.getByTestId('sign-out').click();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
}

/**
 * A short string that is different on every run and for each console, so
 * data from an earlier run or from the other console never collides. Lower
 * case and digits only, because it is also used inside a custom-field key.
 */
export function tag(info: TestInfo): string {
  return `${info.project.name.slice(0, 2)}${Date.now().toString(36)}`;
}

export async function openPartners(page: Page): Promise<void> {
  await page.getByTestId('nav-partners').click();
  await expect(page.getByRole('heading', { name: 'Partners', level: 1 })).toBeVisible();
  await expect(page.getByText('Loading…')).toHaveCount(0);
}

export async function registerPartner(page: Page, code: string): Promise<void> {
  await openPartners(page);
  const form = page.getByTestId('register-partner');
  await form.getByPlaceholder('Code').fill(code);
  await form.getByPlaceholder('Legal name').fill(`${code} Pvt Ltd`);
  await form.getByRole('button', { name: 'Register' }).click();
  await expect(page.getByTestId('row').filter({ hasText: code })).toBeVisible();
}

const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

/**
 * Retires custom fields straight through the API, whatever state the page is
 * in. A test that defines a required field must call this from a `finally`:
 * the demo tenant is shared, and a required field left behind by a failed run
 * would make every later write to that kind of record fail for want of a value.
 */
export async function retireFields(
  request: APIRequestContext, kind: string, keys: string[],
): Promise<void> {
  const login = await request.post(`${API}/api/v1/auth/login`, {
    data: { tenantCode: 'demo', email: DEMO_EMAIL, password: password() },
  });
  const headers = { Authorization: `Bearer ${(await login.json()).accessToken}` };
  const listed = await request.get(`${API}/api/v1/custom-fields/definitions?entityType=${kind}`, { headers });
  for (const d of (await listed.json()).items as Array<{
    id: string; key: string; label: string; options: string[]; active: boolean;
  }>) {
    if (keys.includes(d.key) && d.active) {
      await request.put(`${API}/api/v1/custom-fields/definitions/${d.id}`, {
        headers,
        data: { label: d.label, required: false, options: d.options, active: false },
      });
    }
  }
}
