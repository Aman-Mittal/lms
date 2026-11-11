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
import { API, apiSession, openScreen, signIn, tag, withoutPermissions, type Session } from './support';

const TOLERANCE = 'order.close.tolerancePercent';

/*
 * Organisation units and the settings hierarchy, in both consoles. The demo
 * tenant is shared and units are never deleted, so each test grows its own
 * country beneath the head office, under a slug no other run uses.
 */

async function unitsOf(request: APIRequestContext, session: Session): Promise<Array<{ id: string; path: string }>> {
  const listed = await request.get(`${API}/api/v1/org-units?limit=200`, {
    headers: { Authorization: `Bearer ${session.token}` },
  });
  expect(listed.ok(), `list units: ${listed.status()}`).toBeTruthy();
  return (await listed.json()).items as Array<{ id: string; path: string }>;
}

async function newUnit(request: APIRequestContext, session: Session, data: Record<string, string>): Promise<string> {
  const created = await request.post(`${API}/api/v1/org-units`, {
    headers: { Authorization: `Bearer ${session.token}` }, data,
  });
  expect(created.ok(), `unit: ${created.status()} ${await created.text()}`).toBeTruthy();
  return (await created.json()).id as string;
}

async function newCustomer(request: APIRequestContext, session: Session, code: string): Promise<string> {
  const created = await request.post(`${API}/api/v1/partners`, {
    headers: { Authorization: `Bearer ${session.token}` },
    data: { orgUnitId: session.orgUnitId, code, legalName: `${code} Mills`, partnerType: 'CUSTOMER', taxId: `TAX-${code}` },
  });
  expect(created.ok(), `customer: ${created.status()}`).toBeTruthy();
  return (await created.json()).id as string;
}

/** The row of the unit at `path`. */
const row = (page: Page, path: string) => page.locator(`[data-testid="org-unit-row"][data-path="${path}"]`);

test.describe('organisation units', () => {
  test('a country, a zone with a state and a branch are added beneath the head office, then renamed',
    async ({ page, request }, info) => {
      const session = await apiSession(request);
      const root = (await unitsOf(request, session))[0];
      const slug = `c${tag(info)}`;

      await signIn(page);
      await openScreen(page, 'nav-admin-org-units', 'Organisation units');
      await expect(row(page, root.path).getByTestId('org-unit-level')).toHaveText('Head office');

      // A country needs its code: the API's refusal is shown as given.
      await row(page, root.path).getByTestId('add-child').click();
      const form = page.getByTestId('unit-form');
      await form.getByTestId('unit-slug').fill(slug);
      await form.getByTestId('unit-name').fill(`Country ${slug}`);
      await form.getByTestId('unit-type').selectOption('COUNTRY');
      await form.getByTestId('save-unit').click();
      await expect(page.getByRole('alert').first()).toContainText('country code');
      await form.getByTestId('unit-country').fill('in');
      await form.getByTestId('save-unit').click();
      await expect(page.getByTestId('unit-saved')).toContainText(`Country ${slug} added.`);
      const country = `${root.path}${slug}/`;
      await expect(row(page, country).getByTestId('org-unit-level')).toHaveText('Country');
      await expect(row(page, country).getByTestId('org-unit-jurisdiction')).toHaveText('IN');

      // Beneath a country only lower levels are offered.
      await row(page, country).getByTestId('add-child').click();
      await expect(form.getByTestId('unit-type').locator('option[value="COUNTRY"]')).toHaveCount(0);
      await expect(form.getByTestId('unit-type').locator('option[value="LOB"]')).toHaveCount(0);
      await form.getByTestId('unit-slug').fill('north');
      await form.getByTestId('unit-name').fill('North');
      await form.getByTestId('unit-type').selectOption('ZONE');
      await form.getByTestId('unit-state').fill('HR');
      await form.getByTestId('save-unit').click();
      const zone = `${country}north/`;
      await expect(row(page, zone).getByTestId('org-unit-level')).toHaveText('Zone');
      await expect(row(page, zone).getByTestId('org-unit-jurisdiction')).toHaveText('IN-HR');

      await row(page, zone).getByTestId('add-child').click();
      await form.getByTestId('unit-slug').fill('gurgaon');
      await form.getByTestId('unit-name').fill('Gurgaon');
      await form.getByTestId('unit-type').selectOption('BRANCH');
      await form.getByTestId('save-unit').click();
      const branch = `${zone}gurgaon/`;
      await expect(row(page, branch).getByTestId('org-unit-level')).toHaveText('Branch');
      await expect(row(page, branch).getByTestId('org-unit-jurisdiction')).toHaveText('IN-HR');

      // Renamed in place; the path stays as it was.
      await row(page, zone).getByTestId('edit-unit').click();
      await expect(form.getByTestId('unit-slug')).toHaveCount(0);
      await form.getByTestId('unit-name').fill('North zone');
      await form.getByTestId('save-unit').click();
      await expect(page.getByTestId('unit-saved')).toContainText('North zone saved.');
      await expect(row(page, zone)).toContainText('North zone');
    });

  test('a person without the right sees no Organisation units entry', async ({ page }) => {
    await withoutPermissions(page, ['ORG_UNIT_MANAGE']);
    await signIn(page);
    await expect(page.getByTestId('nav-admin-org-units')).toHaveCount(0);
  });
});

test.describe('settings by level and combination', () => {
  test('a branch-and-customer combination overrides the customer, names itself, and the picker shows levels',
    async ({ page, request }, info) => {
      const session = await apiSession(request);
      const root = (await unitsOf(request, session))[0];
      const t = tag(info);
      const country = await newUnit(request, session, {
        parentId: root.id, slug: `k${t}`, name: `Land ${t}`, unitType: 'COUNTRY', countryCode: 'IN',
      });
      const branch = await newUnit(request, session, {
        parentId: country, slug: 'depot', name: `Depot ${t}`, unitType: 'BRANCH',
      });
      const code = `ZZ-CB-${t.toUpperCase()}`;
      const customer = await newCustomer(request, session, code);

      await signIn(page);
      await openScreen(page, 'nav-admin-settings', 'Profile settings');
      const add = page.getByTestId('add-profile');
      await add.getByTestId('new-scope').selectOption('COMBINATION');
      // The unit picker names each unit's level.
      await add.getByTestId('cond-unit').fill(`Depot ${t}`);
      await expect(page.getByRole('option', { name: new RegExp(`Branch: Depot ${t}`) }).first()).toBeVisible();
      await add.getByTestId('cond-unit').fill(branch);
      await add.getByTestId('cond-customer').fill(customer);
      await add.getByTestId('cond-mode').selectOption('LTL');
      await add.getByTestId('new-name').fill(`Depot terms ${t}`);
      await add.getByTestId('create-profile').click();

      const panel = page.getByTestId('profile-panel');
      await expect(panel.getByRole('heading', { name: `Depot terms ${t}` })).toBeVisible();
      await expect(panel).toContainText(`Branch Depot ${t} + Customer ${code} Mills + Mode LTL`);
      await panel.getByTestId(`value-${TOLERANCE}`).fill('7');
      await panel.getByTestId('save-profile').click();
      await expect(panel.getByTestId('profile-saved')).toHaveText('Saved.');

      const context = page.getByTestId('effective-form');
      await context.getByTestId('ctx-unit').fill(branch);
      await context.getByTestId('ctx-customer').fill(customer);
      await context.getByTestId('ctx-freight-mode').selectOption('LTL');
      await context.getByTestId('show-effective').click();
      const effective = page.getByTestId(`effective-${TOLERANCE}`);
      await expect(effective).toContainText('7');
      await expect(effective).toContainText(`Branch Depot ${t} + Customer ${code} Mills + Mode LTL`);

      // Without the mode the combination does not hold.
      await context.getByTestId('ctx-freight-mode').selectOption('');
      await context.getByTestId('show-effective').click();
      await expect(effective).not.toContainText(`Depot ${t} +`);

      // Its conditions are edited in place: dropping the mode makes it apply to every mode.
      await panel.getByTestId('edit-cond-mode').selectOption('');
      await panel.getByTestId('save-conditions').click();
      await expect(panel.getByTestId('profile-saved')).toHaveText('Saved.');
      await context.getByTestId('show-effective').click();
      await expect(effective).toContainText(`Branch Depot ${t} + Customer ${code} Mills`);

      await panel.getByTestId('delete-profile').click();
      await expect(page.getByTestId('profile-panel')).toHaveCount(0);
    });

  test('a unit profile says which level it came from', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const root = (await unitsOf(request, session))[0];
    const t = tag(info);
    const country = await newUnit(request, session, {
      parentId: root.id, slug: `z${t}`, name: `Realm ${t}`, unitType: 'COUNTRY', countryCode: 'IN',
    });
    const zone = await newUnit(request, session, { parentId: country, slug: 'east', name: `East ${t}`, unitType: 'ZONE' });

    await signIn(page);
    await openScreen(page, 'nav-admin-settings', 'Profile settings');
    const add = page.getByTestId('add-profile');
    await add.getByTestId('new-scope').selectOption('ORG_UNIT');
    await add.getByTestId('new-scope-id').fill(country);
    await add.getByTestId('new-name').fill(`Realm terms ${t}`);
    await add.getByTestId('create-profile').click();
    const panel = page.getByTestId('profile-panel');
    await expect(panel.getByRole('heading', { name: `Realm terms ${t}` })).toBeVisible();
    await panel.getByTestId(`value-${TOLERANCE}`).fill('4');
    await panel.getByTestId('save-profile').click();
    await expect(panel.getByTestId('profile-saved')).toHaveText('Saved.');

    const context = page.getByTestId('effective-form');
    await context.getByTestId('ctx-unit').fill(zone);
    await context.getByTestId('show-effective').click();
    await expect(page.getByTestId(`effective-${TOLERANCE}`)).toContainText(`Country: Realm ${t}`);

    await panel.getByTestId('delete-profile').click();
    await expect(page.getByTestId('profile-panel')).toHaveCount(0);
  });
});
