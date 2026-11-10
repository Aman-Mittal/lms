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

import { expect, test, type APIRequestContext } from '@playwright/test';
import { apiSession, openScreen, signIn, tag, type Session } from './support';

const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

/*
 * The demo tenant allows one active tenant-wide series per document type. These specs
 * number LOAD documents, which nothing else in the suite configures, and switch off any
 * tenant-wide LOAD series before and after each test, so a run that died half way does
 * not refuse the next one.
 */

async function switchOffLoadSeries(request: APIRequestContext, session: Session): Promise<void> {
  const headers = { Authorization: `Bearer ${session.token}` };
  const listed = await request.get(`${API}/api/v1/number-series?documentType=LOAD&limit=200`, { headers });
  expect(listed.ok(), `list series: ${listed.status()}`).toBeTruthy();
  const items = (await listed.json()).items as Array<{ id: string; active: boolean; orgUnitId?: string | null }>;
  for (const s of items.filter((i) => i.active && !i.orgUnitId)) {
    const off = await request.post(`${API}/api/v1/number-series/${s.id}/deactivate`, { headers });
    expect(off.ok(), `deactivate ${s.id}: ${off.status()}`).toBeTruthy();
  }
}

test.describe('number series', () => {
  test.beforeEach(async ({ request }) => switchOffLoadSeries(request, await apiSession(request)));
  test.afterEach(async ({ request }) => switchOffLoadSeries(request, await apiSession(request)));

  test('a series is previewed, created, issues in turn, takes a number back and verifies it',
    async ({ page }, info) => {
      const prefix = `P${tag(info).toUpperCase()}`;
      await signIn(page);
      await openScreen(page, 'nav-admin-number-series', 'Number series');

      const form = page.getByTestId('series-create');
      await form.getByTestId('series-code').fill(`pw-${tag(info)}`);
      await form.getByTestId('series-type').selectOption('LOAD');
      await form.getByTestId('series-prefix').fill(prefix);
      await form.getByTestId('series-year-code').selectOption('NONE');
      await form.getByTestId('series-digits').fill('3');
      await form.getByTestId('series-preview').click();
      await expect(form.getByTestId('series-sample')).toContainText(`${prefix}-001`);

      await form.getByTestId('series-create-submit').click();
      await expect(page.getByTestId('series-message')).toHaveText('Series created.');
      await expect(form.getByTestId('series-prefix')).toHaveValue('');
      const detail = page.getByTestId('series-detail');
      await expect(detail.getByTestId('series-state')).toHaveText('Active');
      await expect(detail.getByTestId('series-next')).toHaveText(`${prefix}-001`);

      await detail.getByTestId('series-issue').click();
      await expect(detail.getByTestId('series-issued')).toContainText(`${prefix}-001`);
      await expect(detail.getByTestId('series-next')).toHaveText(`${prefix}-002`);
      await detail.getByTestId('series-issue').click();
      await expect(detail.getByTestId('series-issued')).toContainText(`${prefix}-002`);

      await detail.getByTestId('release-number').fill(`${prefix}-001`);
      await detail.getByTestId('release-reason').fill('load undone');
      await detail.getByTestId('release-submit').click();
      await expect(page.getByTestId('series-message')).toHaveText(`${prefix}-001 given back.`);
      await expect(detail.getByTestId('release-number')).toHaveValue('');
      await expect(detail.getByTestId('releases-row').filter({ hasText: `${prefix}-001` })).toContainText('load undone');

      const verify = page.getByTestId('series-verify');
      await verify.getByTestId('verify-type').selectOption('LOAD');
      await verify.getByTestId('verify-number').fill(`${prefix}-001`);
      await verify.getByTestId('verify-submit').click();
      await expect(page.getByTestId('verify-verdict')).toContainText('RELEASED');
      await verify.getByTestId('verify-number').fill(`${prefix}-002`);
      await verify.getByTestId('verify-submit').click();
      await expect(page.getByTestId('verify-verdict')).toContainText('ISSUED');

      await detail.getByTestId('series-delete').click();
      await expect(page.getByTestId('series-refusal')).toHaveText('number-series-in-use');
      await detail.getByTestId('series-deactivate').click();
      await expect(detail.getByTestId('series-state')).toHaveText('Inactive');
      await expect(detail.getByTestId('series-activate')).toBeVisible();
    });

  test('a format that could not be saved is refused with its reason', async ({ page }) => {
    await signIn(page);
    await openScreen(page, 'nav-admin-number-series', 'Number series');
    const form = page.getByTestId('series-create');
    await form.getByTestId('series-prefix').fill('lower');
    await form.getByTestId('series-preview').click();
    await expect(page.getByTestId('series-refusal')).toHaveText('invalid-argument');
    await expect(form.getByTestId('series-sample')).toHaveCount(0);
  });
});
