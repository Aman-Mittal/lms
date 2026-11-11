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
import { apiSession, openScreen, signIn, signOut, tag, withoutPermissions, type Session } from './support';

const API = process.env.E2E_API_URL ?? 'http://localhost:8080';
const TOLERANCE = 'order.close.tolerancePercent';

/*
 * The demo administrator's own preferences are put back after every test, so
 * the rest of the suite still opens on the control tower in the light theme;
 * the customer profile a test makes is for a customer it made itself.
 */

async function clearMyPreferences(request: APIRequestContext, session: Session): Promise<void> {
  const headers = { Authorization: `Bearer ${session.token}` };
  const mine = await request.get(`${API}/api/v1/settings/me`, { headers });
  expect(mine.ok(), `read preferences: ${mine.status()}`).toBeTruthy();
  const body = await mine.json() as { version: number; values: Record<string, unknown> };
  const values = Object.fromEntries(Object.keys(body.values).map((k) => [k, null]));
  if (Object.keys(values).length === 0) {
    return;
  }
  const cleared = await request.patch(`${API}/api/v1/settings/me`, {
    headers: { ...headers, 'If-Match': `"${body.version}"` }, data: { values },
  });
  expect(cleared.ok(), `clear preferences: ${cleared.status()}`).toBeTruthy();
}

async function newPartner(request: APIRequestContext, session: Session, code: string,
  partnerType: 'CUSTOMER' | 'VENDOR'): Promise<string> {
  const created = await request.post(`${API}/api/v1/partners`, {
    headers: { Authorization: `Bearer ${session.token}` },
    data: { orgUnitId: session.orgUnitId, code, legalName: `${code} Pvt Ltd`, partnerType, taxId: `TAX-${code}` },
  });
  expect(created.ok(), `${partnerType.toLowerCase()}: ${created.status()}`).toBeTruthy();
  return (await created.json()).id as string;
}

const newCustomer = (request: APIRequestContext, session: Session, code: string) =>
  newPartner(request, session, code, 'CUSTOMER');

test.describe('profile and settings', () => {
  test.beforeEach(async ({ request }) => clearMyPreferences(request, await apiSession(request)));
  test.afterEach(async ({ request }) => clearMyPreferences(request, await apiSession(request)));

  test('my profile shows who I am, and my preferences apply at once and at the next sign-in', async ({ page }) => {
    await signIn(page);
    await openScreen(page, 'nav-me', 'My profile & preferences');
    await expect(page.getByTestId('me-email')).toHaveText('admin@demo.local');
    await expect(page.getByTestId('me-tenant')).toContainText('demo');
    await expect(page.getByTestId('me-roles')).toContainText('ADMIN');
    await expect(page.getByTestId('pref-source-ui.theme')).toContainText('System default');

    await page.getByTestId('pref-ui.theme').selectOption('dark');
    await page.getByTestId('pref-ui.dateFormat').selectOption('DD/MM/YYYY');
    await page.getByTestId('pref-notify.orderClosed.email').check();
    await page.getByTestId('save-preferences').click();
    await expect(page.getByTestId('preferences-saved')).toHaveText('Saved.');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(page.getByTestId('pref-source-ui.theme')).toContainText('You');
    await expect(page.getByTestId('pref-notify.orderClosed.email')).toBeChecked();

    // Applied again at the next sign-in, before any screen is opened.
    await signOut(page);
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'system');
    await signIn(page);
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');

    await openScreen(page, 'nav-me', 'My profile & preferences');
    await page.getByTestId('reset-preferences').click();
    await expect(page.getByTestId('preferences-saved')).toHaveText('Saved.');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'system');
    await expect(page.getByTestId('pref-source-ui.theme')).toContainText('System default');
  });

  test('a customer profile overrides the company close tolerance, says so, and keeps its history',
    async ({ page, request }, info) => {
      const session = await apiSession(request);
      const code = `ZZ-PS-${tag(info).toUpperCase()}`;
      const customer = await newCustomer(request, session, code);

      await signIn(page);
      await openScreen(page, 'nav-admin-settings', 'Profile settings');
      const add = page.getByTestId('add-profile');
      await add.getByTestId('new-scope').selectOption('CUSTOMER');
      await add.getByTestId('new-scope-id').fill(customer);
      await add.getByTestId('new-name').fill(`Terms ${code}`);
      await add.getByTestId('create-profile').click();

      const panel = page.getByTestId('profile-panel');
      await expect(panel.getByRole('heading', { name: `Terms ${code}` })).toBeVisible();
      await panel.getByTestId(`value-${TOLERANCE}`).fill('3');
      await panel.getByTestId('save-profile').click();
      await expect(panel.getByTestId('profile-saved')).toHaveText('Saved.');
      await expect(panel.getByTestId('history-row').filter({ hasText: TOLERANCE }).first()).toContainText('3');

      // An unknown or out-of-range value is the API's refusal, shown as given.
      await panel.getByTestId(`value-${TOLERANCE}`).fill('101');
      await panel.getByTestId('save-profile').click();
      await expect(page.getByRole('alert').first()).toContainText(TOLERANCE);

      const context = page.getByTestId('effective-form');
      await context.getByTestId('ctx-customer').fill(customer);
      await context.getByTestId('show-effective').click();
      const row = page.getByTestId(`effective-${TOLERANCE}`);
      await expect(row).toContainText('3');
      await expect(row).toContainText(`Customer: Terms ${code}`);

      await panel.getByTestId('delete-profile').click();
      await expect(page.getByTestId('profile-panel')).toHaveCount(0);
      await context.getByTestId('show-effective').click();
      await expect(page.getByTestId(`effective-${TOLERANCE}`)).not.toContainText(`Terms ${code}`);
    });

  test('a vendor profile holds the vendor keys only, and its match tolerance is in force for that vendor',
    async ({ page, request }, info) => {
      const MATCH = 'finance.match.tolerancePercent';
      const session = await apiSession(request);
      const code = `ZZ-PV-${tag(info).toUpperCase()}`;
      const vendor = await newPartner(request, session, code, 'VENDOR');

      await signIn(page);
      await openScreen(page, 'nav-admin-settings', 'Profile settings');
      const add = page.getByTestId('add-profile');
      await add.getByTestId('new-scope').selectOption('VENDOR');
      await add.getByTestId('new-scope-id').fill(vendor);
      await add.getByTestId('new-name').fill(`Vendor ${code}`);
      await add.getByTestId('create-profile').click();

      const panel = page.getByTestId('profile-panel');
      await expect(panel.getByRole('heading', { name: `Vendor ${code}` })).toBeVisible();
      // Definitions-driven: the keys kept per vendor, and nothing else.
      await expect(panel.getByTestId('value-finance.match.toleranceAbsolute')).toBeVisible();
      await expect(panel.getByTestId(`value-${TOLERANCE}`)).toHaveCount(0);
      await panel.getByTestId(`value-${MATCH}`).fill('6');
      await panel.getByTestId('save-profile').click();
      await expect(panel.getByTestId('profile-saved')).toHaveText('Saved.');

      const context = page.getByTestId('effective-form');
      await context.getByTestId('ctx-vendor').fill(vendor);
      await context.getByTestId('show-effective').click();
      const row = page.getByTestId(`effective-${MATCH}`);
      await expect(row).toContainText('6');
      await expect(row).toContainText(`Vendor: Vendor ${code}`);

      await panel.getByTestId('delete-profile').click();
      await expect(page.getByTestId('profile-panel')).toHaveCount(0);
    });

  test('a person without the settings rights keeps My profile but not Profile settings', async ({ page }) => {
    await withoutPermissions(page, ['SETTINGS_READ', 'SETTINGS_MANAGE']);
    await signIn(page);
    await expect(page.getByTestId('nav-admin-settings')).toHaveCount(0);
    await openScreen(page, 'nav-me', 'My profile & preferences');
    await expect(page.getByTestId('me-email')).toHaveText('admin@demo.local');
  });
});
