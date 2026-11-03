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

import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { apiSession, signIn, withoutPermissions, type Session } from './support';

const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

/** Registers a partner through the API, as a visitor's leftovers, and returns its id. */
async function leftover(request: APIRequestContext, session: Session): Promise<string> {
  const code = `ZZ-RESET-${Date.now().toString(36)}`;
  const created = await request.post(`${API}/api/v1/partners`, {
    headers: { Authorization: `Bearer ${session.token}` },
    data: { orgUnitId: session.orgUnitId, code, legalName: `${code} Pvt Ltd`, partnerType: 'CUSTOMER',
      taxId: `TAX-${code}` },
  });
  expect(created.status(), await created.text()).toBe(201);
  return (await created.json()).id;
}

async function partnerStatus(request: APIRequestContext, session: Session, id: string): Promise<number> {
  const got = await request.get(`${API}/api/v1/partners/${id}`, {
    headers: { Authorization: `Bearer ${session.token}` },
  });
  return got.status();
}

async function openDemo(page: Page): Promise<void> {
  await page.getByTestId('nav-demo').click();
  await expect(page.getByRole('heading', { name: 'Demo data', level: 1 })).toBeVisible();
}

// A real reset, against the real backend: each shard has its own backend and
// runs one test at a time, and every spec builds its own data, so clearing the
// demo between specs takes nothing another spec still needs.
test.describe('resetting the demo', () => {
  test('cancelling the confirmation resets nothing', async ({ page, request }) => {
    const session = await apiSession(request);
    const partner = await leftover(request, session);
    await signIn(page);
    await openDemo(page);

    await page.getByTestId('demo-reset').click();
    await expect(page.getByTestId('demo-reset-confirm')).toBeVisible();
    await page.getByTestId('demo-reset-cancel').click();

    await expect(page.getByTestId('demo-reset-confirm')).toHaveCount(0);
    await expect(page.getByTestId('demo-reset')).toBeVisible();
    expect(await partnerStatus(request, session, partner), 'the partner is still there').toBe(200);
  });

  test('confirming with the wrong company code is refused and resets nothing', async ({ page, request }) => {
    const session = await apiSession(request);
    const partner = await leftover(request, session);
    await signIn(page);
    await openDemo(page);

    await page.getByTestId('demo-reset').click();
    await page.getByTestId('demo-reset-code').fill('not-the-demo');
    await page.getByTestId('demo-reset-yes').click();

    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.getByTestId('demo-reset-done')).toHaveCount(0);
    expect(await partnerStatus(request, session, partner), 'the partner is still there').toBe(200);
  });

  test('a confirmed reset clears the demo and the administrator signs in again', async ({ page, request }) => {
    const partner = await leftover(request, await apiSession(request));
    await signIn(page);
    await openDemo(page);

    await page.getByTestId('demo-reset').click();
    await expect(page.getByTestId('demo-reset-yes'), 'nothing to confirm with yet').toBeDisabled();
    await page.getByTestId('demo-reset-code').fill('demo');
    await page.getByTestId('demo-reset-yes').click();

    const done = page.getByTestId('demo-reset-done');
    await expect(done).toBeVisible();
    await expect(done).toContainText('rows deleted');
    await expect(page.getByRole('alert')).toHaveCount(0);
    await page.getByTestId('demo-reset-sign-in').click();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();

    await signIn(page);
    expect(await partnerStatus(request, await apiSession(request), partner), 'the partner is gone').toBe(404);
  });
});

test.describe('what each person is offered', () => {
  test('without the right to reset, the sidebar does not offer it and the screen says so', async ({ page }) => {
    await withoutPermissions(page, ['DEMO_RESET']);
    await signIn(page);

    await expect(page.getByTestId('nav-custom-fields')).toBeVisible();
    await expect(page.getByTestId('nav-demo')).toHaveCount(0);

    await page.evaluate(() => { window.location.hash = '#/settings/demo'; });
    await expect(page.getByTestId('demo-reset-forbidden')).toBeVisible();
    await expect(page.getByTestId('demo-reset')).toHaveCount(0);
  });
});
