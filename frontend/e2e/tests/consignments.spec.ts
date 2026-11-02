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
import {
  apiSession, newConsignment, printed, signIn, splitEvenly, stubPrint, trackingRefOf, withoutPermissions,
} from './support';

/** Opens the consignment desk and looks a consignment up by its reference. */
async function lookUp(page: Page, reference: string): Promise<void> {
  await page.getByTestId('nav-consignments').click();
  await expect(page.getByRole('heading', { name: 'Consignments', level: 1 })).toBeVisible();
  await page.getByTestId('consignment-ref').fill(reference);
  await page.getByRole('button', { name: 'Look up' }).click();
  await expect(page.getByRole('heading', { name: reference, level: 2 })).toBeVisible();
}

test.describe('splitting a consignment across vehicles', () => {
  test('two thousand tonnes are planned as a hundred lorry loads, adjusted, and split', async ({ page, request }) => {
    const session = await apiSession(request);
    const whole = await newConsignment(request, session, 2_000_000);
    const reference = await trackingRefOf(request, session, whole);
    await stubPrint(page);
    await signIn(page);
    await lookUp(page, reference);
    await expect(page.getByTestId('consignment-status')).toHaveText('PLANNED');
    await expect(page.getByTestId('consignment-weight')).toHaveText('2000000');

    const form = page.getByTestId('split-form');
    await test.step('plan equal parts from a 20-tonne lorry', async () => {
      await form.getByTestId('split-capacity').fill('20000');
      await form.getByTestId('plan-parts').click();
      await expect(form.getByTestId('planned-parts').locator('tbody tr')).toHaveCount(100);
      await expect(form.getByTestId('part-weight-1')).toHaveValue('20000');
      await expect(form.getByTestId('part-weight-100')).toHaveValue('20000');
      await expect(form.getByTestId('split-totals')).toContainText('they match');
      await expect(form.getByTestId('split-submit')).toHaveText('Split into 100 parts');
      await expect(form.getByTestId('split-submit')).toBeEnabled();
    });

    await test.step('a part changed so the parts no longer add up cannot be split', async () => {
      await form.getByTestId('part-weight-1').fill('19999.5');
      await expect(form.getByTestId('split-totals')).toContainText('1999999.5 kg');
      await expect(form.getByTestId('split-totals')).toContainText('must match exactly');
      await expect(form.getByTestId('split-submit')).toBeDisabled();
      // Moving the half kilogram to the last part makes them add up again.
      await form.getByTestId('part-weight-100').fill('20000.5');
      await expect(form.getByTestId('split-totals')).toContainText('they match');
    });

    await test.step('split, and the consignment reads as a hundred parts, none delivered yet', async () => {
      await form.getByTestId('split-submit').click();
      await expect(page.getByTestId('consignment-status')).toHaveText('SPLIT');
      await expect(page.getByTestId('part-count')).toHaveText('100');
      await expect(page.getByTestId('parts-delivered')).toHaveText('0');
      await expect(page.getByTestId('parts').getByTestId('part-row')).toHaveCount(100);
      await expect(page.getByTestId('parts').getByTestId('part-row').first()).toContainText(`${reference}-001`);
      await expect(page.getByTestId('parts').getByTestId('part-row').first()).toContainText('19999.5');
      await expect(page.getByTestId('parts').getByTestId('part-row').last()).toContainText(`${reference}-100`);
      await expect(page.getByTestId('parts').getByTestId('part-row').last()).toContainText('20000.5');
      await expect(page.getByTestId('split-form')).toHaveCount(0);
      await expect(page.getByTestId('print-lr')).toHaveCount(0);
    });

    await test.step('a part prints its own lorry receipt with a QR code, and leads back to the whole', async () => {
      await page.getByRole('button', { name: `${reference}-002` }).click();
      await expect(page.getByRole('heading', { name: `${reference}-002`, level: 2 })).toBeVisible();
      await expect(page.getByTestId('consignment-status')).toHaveText('PLANNED');
      await expect(page.getByTestId('consignment-parent')).toHaveText(reference);
      await expect(page.getByTestId('split-form')).toHaveCount(0);

      await page.getByTestId('print-lr').click();
      const slip = page.getByTestId('lr-slip');
      await expect(slip).toContainText(`Lorry receipt ${reference}-002`);
      await expect(slip).toContainText(reference);
      await expect(slip.getByTestId('qr')).toHaveAttribute('data-payload', /^LMS:CONSIGNMENT:[0-9a-f-]{36}$/);
      await slip.getByTestId('print-now').click();
      expect(await printed(page)).toBe(1);

      await page.getByTestId('consignment-parent').click();
      await expect(page.getByRole('heading', { name: reference, level: 2 })).toBeVisible();
      await expect(page.getByTestId('consignment-status')).toHaveText('SPLIT');
    });
  });

  test('a consignment that fits on one vehicle is not split, and the reason is shown', async ({ page, request }) => {
    const session = await apiSession(request);
    const reference = await trackingRefOf(request, session, await newConsignment(request, session, 500));
    await signIn(page);
    await lookUp(page, reference);

    const form = page.getByTestId('split-form');
    await form.getByTestId('split-capacity').fill('20000');
    await form.getByTestId('plan-parts').click();
    await expect(form.getByRole('alert')).toContainText('already fits on one vehicle');
    await expect(form.getByTestId('planned-parts')).toHaveCount(0);
  });

  test('a customer link on a split consignment counts the vehicles', async ({ page, request, browser }) => {
    const session = await apiSession(request);
    const whole = await newConsignment(request, session, 60_000);
    await splitEvenly(request, session, whole, 3, 20_000);
    await signIn(page);
    await page.getByTestId('nav-tracking').click();
    await page.getByTestId('consignment-id').fill(whole);
    await page.getByRole('button', { name: 'Look up' }).click();
    await expect(page.getByTestId('links-panel')).toBeVisible();
    await page.getByTestId('issue-link').click();
    await expect(page.getByTestId('issued-link')).toBeVisible();
    const url = await page.getByTestId('link-url').inputValue();

    const customer = await browser.newContext();
    try {
      const visitor = await customer.newPage();
      await visitor.goto(url);
      await expect(visitor.getByTestId('track-status')).toHaveText('PLANNED');
      await expect(visitor.getByTestId('track-parts')).toHaveText('0 of 3 delivered');
    } finally {
      await customer.close();
    }
  });
});

test.describe('what each person is offered', () => {
  test('without the right to build loads, a consignment can be read and printed but not split', async ({ page, request }) => {
    const session = await apiSession(request);
    const reference = await trackingRefOf(request, session, await newConsignment(request, session, 40_000));
    await withoutPermissions(page, ['LOAD_CREATE']);
    await signIn(page);
    await lookUp(page, reference);

    await expect(page.getByTestId('consignment-status')).toHaveText('PLANNED');
    await expect(page.getByTestId('split-form')).toHaveCount(0);
    await expect(page.getByTestId('print-lr')).toBeVisible();
  });

  test('without the right to read loads, the consignment desk is not in the sidebar', async ({ page }) => {
    await withoutPermissions(page, ['LOAD_READ']);
    await signIn(page);
    await expect(page.getByTestId('sidebar')).toBeVisible();
    await expect(page.getByTestId('nav-consignments')).toHaveCount(0);
  });
});
