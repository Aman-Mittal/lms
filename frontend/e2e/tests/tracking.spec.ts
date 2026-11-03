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

import { expect, test, type Page } from '@playwright/test';
import { apiSession, newConsignment, printed, signIn, stubPrint, withoutPermissions } from './support';

/** Opens the tracking links screen and looks a consignment up. */
async function openLinks(page: Page, consignment: string): Promise<void> {
  await page.getByTestId('nav-tracking').click();
  await expect(page.getByRole('heading', { name: 'Tracking links', level: 1 })).toBeVisible();
  await page.getByTestId('consignment-id').fill(consignment);
  await page.getByRole('button', { name: 'Look up' }).click();
  await expect(page.getByTestId('links-panel')).toBeVisible();
}

/** Issues a link and returns the address the customer would open. */
async function issueLink(page: Page): Promise<string> {
  await page.getByTestId('issue-link').click();
  await expect(page.getByTestId('issued-link')).toBeVisible();
  return page.getByTestId('link-url').inputValue();
}

test.describe('tracking links', () => {
  test('a link is issued once, with its address and a QR code of that address', async ({ page, request }) => {
    const consignment = await newConsignment(request, await apiSession(request));
    await signIn(page);
    await openLinks(page, consignment);
    await expect(page.getByTestId('no-links')).toBeVisible();

    const url = await issueLink(page);
    expect(url).toContain('#/track/');
    await expect(page.getByTestId('links-table').getByTestId('link-row')).toHaveCount(1);
    await expect(page.getByTestId('link-status')).toHaveText('ACTIVE');

    await test.step('the secret is shown this once and not again', async () => {
      await page.getByTestId('consignment-id').fill(consignment);
      await page.getByRole('button', { name: 'Look up' }).click();
      await expect(page.getByTestId('links-panel')).toBeVisible();
      await expect(page.getByTestId('issued-link')).toHaveCount(0);
      await expect(page.getByTestId('link-row')).toHaveCount(1);
      await expect(page.locator('body')).not.toContainText(url.split('#/track/')[1]);
    });
  });

  test('the link opens a plain page for someone who is not signed in', async ({ page, request, browser }) => {
    const consignment = await newConsignment(request, await apiSession(request));
    await signIn(page);
    await openLinks(page, consignment);
    const url = await issueLink(page);

    const customer = await browser.newContext();
    try {
      const visitor = await customer.newPage();
      await visitor.goto(url);

      await expect(visitor.getByRole('heading', { name: 'Track your shipment', level: 1 })).toBeVisible();
      await expect(visitor.getByTestId('track-view')).toBeVisible();
      await expect(visitor.getByTestId('track-reference')).not.toBeEmpty();
      await expect(visitor.getByTestId('track-status')).toHaveText(/^(PLANNED|IN_TRANSIT|DELIVERED|CANCELLED)$/);
      await expect(visitor.getByTestId('track-origin')).toContainText('E2E ORIG');
      await expect(visitor.getByTestId('track-destination')).toContainText('E2E DEST');
      await expect(visitor.getByTestId('track-proof')).toHaveText('No');
      // It is not the console: no sidebar, nothing to sign in to, and nothing that
      // names the customer or the consignee.
      await expect(visitor.getByTestId('sidebar')).toHaveCount(0);
      await expect(visitor.locator('body')).not.toContainText('E2E customer');
      await expect(visitor.locator('body')).not.toContainText('E2E consignee');
    } finally {
      await customer.close();
    }
  });

  test('a revoked link, and a link that never existed, look exactly alike', async ({ page, request, browser }) => {
    const consignment = await newConsignment(request, await apiSession(request));
    await signIn(page);
    await openLinks(page, consignment);
    const url = await issueLink(page);

    await page.getByTestId('revoke-link').click();
    await expect(page.getByTestId('link-status')).toHaveText('REVOKED');
    await expect(page.getByTestId('revoke-link')).toHaveCount(0);

    const customer = await browser.newContext();
    try {
      const visitor = await customer.newPage();
      await visitor.goto(url);
      await expect(visitor.getByTestId('track-not-found')).toBeVisible();
      const revoked = await visitor.locator('main').innerText();

      await visitor.goto(url.replace(/#\/track\/.*$/, '#/track/not-a-real-token'));
      await expect(visitor.getByTestId('track-not-found')).toBeVisible();
      expect(await visitor.locator('main').innerText()).toBe(revoked);
      await expect(visitor.getByTestId('track-view')).toHaveCount(0);
    } finally {
      await customer.close();
    }
  });

  test('the public read is made without the signed-in console\'s token', async ({ page, request }) => {
    const consignment = await newConsignment(request, await apiSession(request));
    await signIn(page);
    await openLinks(page, consignment);
    const url = await issueLink(page);

    const seen: Array<string | undefined> = [];
    page.on('request', (r) => {
      if (r.url().includes('/api/v1/public/tracking/')) {
        seen.push(r.headers()['authorization']);
      }
    });
    // Same page, still signed in: only the hash changes, so the session survives.
    await page.evaluate((hash) => { window.location.hash = hash; }, url.split('#')[1]);
    await expect(page.getByTestId('track-view')).toBeVisible();

    expect(seen).toHaveLength(1);
    expect(seen[0]).toBeUndefined();
  });
});

test.describe('print and QR codes', () => {
  test('the link slip carries a QR code of the address and prints on its own', async ({ page, request }) => {
    const consignment = await newConsignment(request, await apiSession(request));
    await stubPrint(page);
    await signIn(page);
    await openLinks(page, consignment);
    const url = await issueLink(page);

    await expect(page.getByTestId('link-slip')).toHaveCount(0);
    await page.getByTestId('print-link').click();

    const slip = page.getByTestId('link-slip');
    await expect(slip).toContainText(url);
    await expect(slip.getByTestId('qr')).toHaveAttribute('data-payload', url);
    expect(await slip.getByTestId('qr').getAttribute('src')).toMatch(/^data:image\/png;base64,/);

    await slip.getByTestId('print-now').click();
    expect(await printed(page)).toBe(1);
  });
});

test.describe('what each person is offered', () => {
  test('without the right to manage links the sidebar does not offer them, and the screen says so', async ({ page }) => {
    await withoutPermissions(page, ['TRACKING_LINK_MANAGE']);
    await signIn(page);

    await expect(page.getByTestId('sidebar')).toBeVisible();
    await expect(page.getByTestId('nav-tracking')).toHaveCount(0);
    await expect(page.getByTestId('nav-delivery')).toBeVisible();

    // Reached anyway, by address: the screen offers nothing to someone without the right.
    await page.evaluate(() => { window.location.hash = '#/tracking'; });
    await expect(page.getByRole('heading', { name: 'Tracking links', level: 1 })).toBeVisible();
    await expect(page.getByTestId('no-rights')).toBeVisible();
    await expect(page.getByTestId('consignment-id')).toHaveCount(0);
  });
});
