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
import { openScreen, signIn } from './support';
import { idOf, refOf, stubConsignmentSession, stubConsignments, type ConsignmentStub } from './support-bulk-consignments';

/*
 * Bulk amend and bulk cancel on the consignment register, in both consoles: rows are picked, the same changes (or one reason)
 * go to every picked consignment, each amendment carries its own row's version, and the answer says per consignment which went
 * through and which was refused. The API is stubbed so the same assertions run whatever the demo tenant holds; the stub keeps
 * the server's rules and the spec checks what each console sends. Set E2E_STUB_SESSION=1 to stub the sign-in too, for a run
 * with no backend at all.
 */

test.beforeEach(async ({ page }) => {
  if (process.env.E2E_STUB_SESSION) {
    await stubConsignmentSession(page);
  }
});

/** The last change the stub saw whose path ends as given. */
function sent(stub: ConsignmentStub, ending: string) {
  return stub.changes.filter((c) => c.path.endsWith(ending)).at(-1);
}

async function openRegister(page: Page): Promise<void> {
  await openScreen(page, 'nav-consignments', 'Consignments');
  await expect(page.getByTestId('cons-list')).toBeVisible();
}

/** Ticks the row for a consignment reference. */
async function pick(page: Page, ...refs: string[]): Promise<void> {
  for (const ref of refs) {
    await page.getByRole('checkbox', { name: `Pick consignment ${ref}` }).check();
  }
}

test.describe('picking', () => {
  test('rows are picked one by one or all on the page, and counted', async ({ page }) => {
    await stubConsignments(page);
    await signIn(page);
    await openRegister(page);
    await expect(page.getByTestId('cons-row')).toHaveCount(3);
    await expect(page.getByTestId('cons-picked-count')).toHaveText('0 picked');
    await expect(page.getByTestId('cons-bulk-cancel')).toBeDisabled();
    await expect(page.getByTestId('cons-bulk-amend')).toBeDisabled();

    await pick(page, refOf(1), refOf(3));
    await expect(page.getByTestId('cons-picked-count')).toHaveText('2 picked');
    await expect(page.getByTestId('cons-bulk-cancel')).toBeEnabled();

    await page.getByTestId('cons-pick-all').check();
    await expect(page.getByTestId('cons-picked-count')).toHaveText('3 picked');
    await page.getByTestId('cons-pick-all').uncheck();
    await expect(page.getByTestId('cons-picked-count')).toHaveText('0 picked');
  });

  test('no more than the limit can be picked, across pages, and the message says so', async ({ page }) => {
    await stubConsignments(page, 210);
    await signIn(page);
    await openRegister(page);
    for (let pageNo = 1; pageNo <= 4; pageNo++) {
      await expect(page.getByTestId('cons-row')).toHaveCount(50);
      await page.getByTestId('cons-pick-all').check();
      await expect(page.getByTestId('cons-picked-count')).toHaveText(`${pageNo * 50} picked`);
      await page.getByTestId('cons-page-next').click();
    }
    await expect(page.getByTestId('cons-row')).toHaveCount(10);
    await expect(page.getByTestId('cons-pick-limit')).toHaveCount(0);
    // Nothing more fits, so the box stays unticked and the message says why.
    await page.getByTestId('cons-pick-all').click();
    await expect(page.getByTestId('cons-pick-limit')).toContainText('At most 200 consignments');
    await expect(page.getByTestId('cons-picked-count')).toHaveText('200 picked');
    await page.getByRole('checkbox', { name: `Pick consignment ${refOf(205)}` }).click();
    await expect(page.getByRole('checkbox', { name: `Pick consignment ${refOf(205)}` })).not.toBeChecked();
    await expect(page.getByTestId('cons-picked-count')).toHaveText('200 picked');
  });
});

test.describe('bulk cancel', () => {
  test('the picked are cancelled with one reason; the one refused is shown with its code and stays picked', async ({ page }) => {
    const stub = await stubConsignments(page);
    await signIn(page);
    await openRegister(page);
    await pick(page, refOf(1), refOf(2), refOf(3));

    await page.getByTestId('cons-bulk-cancel').click();
    const dialog = page.getByTestId('confirm-dialog');
    await expect(dialog).toContainText('Cancel 3 picked consignments?');
    await dialog.locator('input, textarea').fill('Duplicate bookings');
    await page.getByTestId('confirm-accept').click();

    await expect(page.getByTestId('cons-bulk-done')).toHaveText('2 cancelled, 1 refused');
    expect(sent(stub, '/consignments/cancellations')?.body).toEqual({
      consignmentIds: [idOf(1), idOf(2), idOf(3)], reason: 'Duplicate bookings' });

    const results = page.getByTestId('cons-bulk-results').getByTestId('cons-bulk-result');
    await expect(results).toHaveCount(3);
    await expect(results.nth(0)).toHaveAttribute('data-outcome', 'ok');
    await expect(results.nth(0)).toContainText(refOf(1));
    await expect(results.nth(1)).toHaveAttribute('data-outcome', 'refused');
    await expect(results.nth(1).getByTestId('cons-bulk-result-code')).toHaveText('consignment-dispatched');
    await expect(results.nth(1)).toContainText('It is already on its way');
    await expect(results.nth(2)).toHaveAttribute('data-outcome', 'ok');

    // The list is read again, and only the refused one is still picked.
    await expect(page.getByTestId('cons-row').nth(0).locator('[data-value]')).toHaveAttribute('data-value', 'CANCELLED');
    await expect(page.getByTestId('cons-row').nth(1).locator('[data-value]')).toHaveAttribute('data-value', 'PLANNED');
    await expect(page.getByTestId('cons-picked-count')).toHaveText('1 picked');
    await expect(page.getByRole('checkbox', { name: `Pick consignment ${refOf(2)}` })).toBeChecked();
    await expect(page.getByRole('checkbox', { name: `Pick consignment ${refOf(1)}` })).not.toBeChecked();
  });

  test('a reason left out sends none', async ({ page }) => {
    const stub = await stubConsignments(page);
    await signIn(page);
    await openRegister(page);
    await pick(page, refOf(3));
    await page.getByTestId('cons-bulk-cancel').click();
    await page.getByTestId('confirm-accept').click();
    await expect(page.getByTestId('cons-bulk-done')).toHaveText('1 cancelled');
    expect(sent(stub, '/consignments/cancellations')?.body).toEqual({ consignmentIds: [idOf(3)] });
  });

  test('dismissing the confirmation sends nothing and keeps the picks', async ({ page }) => {
    const stub = await stubConsignments(page);
    await signIn(page);
    await openRegister(page);
    await pick(page, refOf(1), refOf(3));
    await page.getByTestId('cons-bulk-cancel').click();
    await page.getByTestId('confirm-dialog').locator('input, textarea').fill('changed my mind');
    await page.getByTestId('confirm-dismiss').click();
    await expect(page.getByTestId('confirm-dialog')).toHaveCount(0);
    expect(stub.changes).toEqual([]);
    await expect(page.getByTestId('cons-bulk-done')).toHaveCount(0);
    await expect(page.getByTestId('cons-picked-count')).toHaveText('2 picked');
  });
});

test.describe('bulk amend', () => {
  test('only what is filled or cleared is sent, each consignment with its own version, and a stale one is refused', async ({ page }) => {
    const stub = await stubConsignments(page);
    await signIn(page);
    await openRegister(page);
    await pick(page, refOf(1), refOf(2), refOf(3));
    // Someone else edits the second after it was listed.
    stub.changeUnderfoot(refOf(2));

    await page.getByTestId('cons-bulk-amend').click();
    const form = page.getByTestId('cons-bulk-amend-form');
    await expect(form.getByTestId('cons-bulk-apply')).toBeDisabled();
    await form.getByTestId('bulk-particular-contactName').fill('  Meera, stores ');
    await form.getByTestId('bulk-clear-remarks').check();
    await expect(form.getByTestId('bulk-particular-remarks')).toBeDisabled();
    await expect(form.getByTestId('cons-bulk-apply')).toBeEnabled();
    await form.getByTestId('cons-bulk-apply').click();

    await expect(page.getByTestId('cons-bulk-done')).toHaveText('2 amended, 1 refused');
    await expect(page.getByTestId('cons-bulk-amend-form')).toHaveCount(0);
    expect(sent(stub, '/consignments/amendments')?.body).toEqual({ items: [
      { consignmentId: idOf(1), version: 3, changes: { contactName: 'Meera, stores', remarks: '' } },
      { consignmentId: idOf(2), version: 5, changes: { contactName: 'Meera, stores', remarks: '' } },
      { consignmentId: idOf(3), version: 7, changes: { contactName: 'Meera, stores', remarks: '' } },
    ] });

    const results = page.getByTestId('cons-bulk-results').getByTestId('cons-bulk-result');
    await expect(results).toHaveCount(3);
    await expect(results.nth(0)).toHaveAttribute('data-outcome', 'ok');
    await expect(results.nth(1)).toHaveAttribute('data-outcome', 'refused');
    await expect(results.nth(1).getByTestId('cons-bulk-result-code')).toHaveText('concurrent-modification');
    await expect(results.nth(2)).toHaveAttribute('data-outcome', 'ok');
    await expect(page.getByTestId('cons-picked-count')).toHaveText('1 picked');

    await test.step('retrying the refused one sends the version the list was refreshed with', async () => {
      await page.getByTestId('cons-bulk-amend').click();
      await page.getByTestId('bulk-particular-contactName').fill('Asha');
      await page.getByTestId('cons-bulk-apply').click();
      await expect(page.getByTestId('cons-bulk-done')).toHaveText('1 amended');
      expect(sent(stub, '/consignments/amendments')?.body).toEqual({ items: [
        { consignmentId: idOf(2), version: 6, changes: { contactName: 'Asha' } }] });
      await expect(page.getByTestId('cons-picked-count')).toHaveText('0 picked');
    });
  });
});

test.describe('permissions', () => {
  test('without the right to build loads the register is read but nothing can be picked', async ({ page }) => {
    test.skip(!process.env.E2E_STUB_SESSION, 'dropping rights is done on the stubbed sign-in');
    await stubConsignmentSession(page, ['LOAD_CREATE']);
    await stubConsignments(page);
    await signIn(page);
    await openRegister(page);
    await expect(page.getByTestId('cons-row')).toHaveCount(3);
    await expect(page.getByTestId('cons-pick')).toHaveCount(0);
    await expect(page.getByTestId('cons-pick-all')).toHaveCount(0);
    await expect(page.getByTestId('cons-bulk-amend')).toHaveCount(0);
    await expect(page.getByTestId('cons-bulk-cancel')).toHaveCount(0);
  });
});
