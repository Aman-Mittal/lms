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

import { expect, test } from '@playwright/test';
import { NAV_GROUPS } from '../../shared/nav';
import { signIn } from './support';
import { stubShellApi } from './support-shell';

/*
 * The sidebar and its phone drawer. The API is stubbed, so this needs no backend: it only checks what the shell
 * draws from shared/nav.ts, the same in both consoles.
 */

process.env.E2E_DEMO_PASSWORD ??= 'stub';

const keys = NAV_GROUPS.flatMap((g) => g.sections.flatMap((s) => s.items.map((i) => i.key)));

test.describe('the sidebar', () => {
  test('offers every entry, grouped, in the order the shared definition gives', async ({ page }) => {
    await stubShellApi(page);
    await signIn(page);

    for (const group of NAV_GROUPS) {
      await expect(page.getByTestId(`nav-toggle-${group.id}`)).toHaveAttribute('aria-expanded', 'true');
    }
    const shown = await page.getByTestId('sidebar').locator('a[data-testid^="nav-"]')
      .evaluateAll((links) => links.map((a) => a.getAttribute('data-testid')!.replace(/^nav-/, '')));
    expect(shown).toEqual(keys);
    await expect(page.getByTestId('nav-admin')).toBeVisible();
  });

  test('a person with no extra rights sees only the entries open to everyone', async ({ page }) => {
    await stubShellApi(page, []);
    await signIn(page);

    await expect(page.getByTestId('nav-partners')).toBeVisible();
    await expect(page.getByTestId('nav-order-desk')).toHaveCount(0);
    await expect(page.getByTestId('nav-admin')).toHaveCount(0);
    await expect(page.getByTestId('nav-group-warehouse')).toHaveCount(0);
  });

  test('a group collapses, and stays collapsed after the next sign-in', async ({ page }) => {
    await stubShellApi(page);
    await signIn(page);

    await page.getByTestId('nav-toggle-orders').click();
    await expect(page.getByTestId('nav-order-desk')).toBeHidden();
    await page.reload();
    await signIn(page);
    await expect(page.getByTestId('nav-order-desk')).toBeHidden();
    await expect(page.getByTestId('nav-partners')).toBeVisible();

    await page.getByTestId('nav-toggle-orders').click();
    await expect(page.getByTestId('nav-order-desk')).toBeVisible();
  });

  test('the group holding the page being shown opens, whatever was remembered', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('lms.nav.groups', JSON.stringify({ finance: false })));
    await stubShellApi(page);
    await signIn(page);

    await expect(page.getByTestId('nav-payables')).toBeHidden();
    await page.evaluate(() => { location.hash = '#/payables'; });
    await expect(page.getByTestId('nav-payables')).toBeVisible();
    await expect(page.getByTestId('nav-payables')).toHaveClass(/active/);
  });

  test('unreadable remembered state is ignored', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('lms.nav.groups', '{not json'));
    await stubShellApi(page);
    await signIn(page);

    await expect(page.getByTestId('nav-partners')).toBeVisible();
  });

  test('the filter narrows the entries, opening collapsed groups, and clearing it brings them back', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('lms.nav.groups', JSON.stringify({ finance: false })));
    await stubShellApi(page);
    await signIn(page);

    await page.getByTestId('nav-filter').fill('bill');
    await expect(page.getByTestId('nav-freight-bills')).toBeVisible();
    await expect(page.getByTestId('nav-client-billing')).toBeVisible();
    await expect(page.getByTestId('nav-eway-bills')).toBeVisible();
    await expect(page.getByTestId('nav-partners')).toHaveCount(0);

    await page.getByTestId('nav-filter').fill('zzzz');
    await expect(page.getByText('No menu entry matches.')).toBeVisible();

    await page.getByTestId('nav-filter').fill('');
    await expect(page.getByTestId('nav-partners')).toBeVisible();
    await expect(page.getByTestId('nav-payables')).toBeHidden();
  });
});

test.describe('the drawer on a phone', () => {
  test.use({ viewport: { width: 390, height: 800 } });

  test('opens from the menu button and closes on Escape, on the backdrop and on navigating', async ({ page }) => {
    await stubShellApi(page);
    await signIn(page);

    const sidebar = page.getByTestId('sidebar');
    const toggle = page.getByTestId('nav-toggle');
    await expect(sidebar).toBeHidden();
    // Content starts at the top: the sidebar takes no room in the page.
    const top = await page.locator('main').evaluate((el) => el.getBoundingClientRect().top);
    expect(top).toBeLessThan(120);

    await toggle.click();
    await expect(sidebar).toBeVisible();
    await expect(page.getByTestId('nav-filter')).toBeFocused();
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');

    await page.keyboard.press('Escape');
    await expect(sidebar).toBeHidden();
    await expect(toggle).toBeFocused();

    await toggle.click();
    await page.getByTestId('nav-backdrop').click({ position: { x: 375, y: 400 } });
    await expect(sidebar).toBeHidden();

    await toggle.click();
    await page.getByTestId('nav-close').click();
    await expect(sidebar).toBeHidden();

    await toggle.click();
    await page.getByTestId('nav-toggle-master-data').click();
    await page.getByTestId('nav-partners').click();
    await expect(page.getByRole('heading', { name: 'Partners', level: 1 })).toBeVisible();
    await expect(sidebar).toBeHidden();
  });

  test('on a phone only the group of the page being shown starts open', async ({ page }) => {
    await stubShellApi(page);
    await signIn(page);

    await page.getByTestId('nav-toggle').click();
    await expect(page.getByTestId('nav-overview')).toBeVisible();
    await expect(page.getByTestId('nav-order-desk')).toBeHidden();
    await expect(page.getByTestId('nav-toggle-orders')).toHaveAttribute('aria-expanded', 'false');
  });
});
