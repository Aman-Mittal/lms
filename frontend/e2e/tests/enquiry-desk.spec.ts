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
import { signIn, withValue } from './support';
import {
  customer, destinationId, originId, stubEnquiries, stubSession, stubUpload, type EnquiryStub,
} from './support-enquiry';

/*
 * The enquiry desk and the bulk order upload, in both consoles. The API is stubbed so the same assertions
 * run whatever the demo tenant holds; the stub records what each console sends (the If-Match of every
 * change, the filter, the file) and the spec checks it. Set E2E_STUB_SESSION=1 to stub the sign-in too, for
 * a run with no backend at all.
 */

test.beforeEach(async ({ page }) => {
  if (process.env.E2E_STUB_SESSION) {
    await stubSession(page);
  }
});

/** The number in a cell, whatever grouping or decimals the console formats it with. */
const numberIn = (text: string | null) => Number((text ?? '').replace(/[^0-9.]/g, ''));

const line = (page: Page, no: number) => page.getByTestId('enquiry-line-row').nth(no - 1);

async function openDesk(page: Page) {
  await page.getByTestId('nav-enquiries').click();
  await expect(page.getByRole('heading', { name: 'Enquiry desk', level: 1 })).toBeVisible();
}

/** Fills the line being entered, with the pickers' ids pasted. */
async function enterLine(page: Page, quantity: string, destination = destinationId, weight = '1000') {
  await page.getByTestId('enquiry-line-origin').fill(originId);
  await page.getByTestId('enquiry-line-destination').fill(destination);
  await page.getByTestId('enquiry-line-quantity').fill(quantity);
  await page.getByTestId('enquiry-line-weight').fill(weight);
}

async function createEnquiry(page: Page, reference: string) {
  await page.getByTestId('enquiry-customer').fill(customer);
  await page.getByTestId('enquiry-reference').fill(reference);
  await enterLine(page, '10');
  await page.getByTestId('enquiry-line-add').click();
  await expect(page.getByTestId('enquiry-draft-line')).toHaveCount(1);
  await enterLine(page, '6', '66666666-7777-4888-8999-aaaaaaaaaaaa', '400');
  await page.getByTestId('enquiry-reference').press('Enter');
}

test.describe('enquiry desk', () => {
  test('an enquiry is made with lines, quoted, revised, revoked, decided, and converted to one order', async ({ page }) => {
    const stub = await stubEnquiries(page);
    await signIn(page);
    await openDesk(page);

    await test.step('it is made with two lines, one of them added by the Enter that submits the form', async () => {
      await expect(page.getByTestId('enquiry-none')).toBeVisible();
      await createEnquiry(page, 'RFQ-100');
      await expect(page.getByTestId('enquiry-status')).toHaveAttribute('data-value', 'OPEN');
      await expect(page.getByTestId('enquiry-line-row')).toHaveCount(2);
      await expect(page.getByTestId('enquiry-row')).toHaveCount(1);
      await expect(page.getByTestId('enquiry-deadline')).not.toBeEmpty();
      expect(stub.changes[0].body).toMatchObject({ customerPartnerId: customer, reference: 'RFQ-100' });
      expect((stub.changes[0].body as { lines: unknown[] }).lines).toHaveLength(2);
    });

    await test.step('the contract lane suggests a rate for the line it serves, and only that line', async () => {
      await expect(line(page, 1).getByTestId('enquiry-line-suggested')).toContainText('MUM-DEL');
      expect(numberIn(await line(page, 1).getByTestId('enquiry-line-suggested').textContent())).toBe(5000);
      await expect(line(page, 2).getByTestId('enquiry-line-suggested')).toHaveText('—');
    });

    await test.step('a quote is issued, revised into a new revision, revoked and issued again, each with its version', async () => {
      await line(page, 1).getByTestId('enquiry-quote-amount').fill('4800');
      await line(page, 1).getByTestId('enquiry-quote-issue').click();
      await expect(line(page, 1).getByTestId('enquiry-quote-row')).toHaveCount(1);
      await expect(line(page, 1).getByTestId('enquiry-quote-row').first()).toContainText(/4,?800/);
      await expect(line(page, 1).getByTestId('enquiry-quote-row').first()).toContainText('ISSUED');

      await line(page, 1).getByTestId('enquiry-quote-amount').fill('4700');
      await line(page, 1).getByTestId('enquiry-quote-revise').click();
      await expect(line(page, 1).getByTestId('enquiry-quote-row')).toHaveCount(2);
      await expect(line(page, 1).getByTestId('enquiry-quote-row').first()).toContainText('SUPERSEDED');
      await expect(line(page, 1).getByTestId('enquiry-quote-row').nth(1)).toContainText('ISSUED');
      await expect(line(page, 1).getByTestId('enquiry-quote-row').nth(1)).toContainText(/4,?700/);

      await line(page, 1).getByTestId('enquiry-line-reason').fill('customer asked again');
      await line(page, 1).getByTestId('enquiry-quote-revoke').click();
      await expect(line(page, 1).getByTestId('enquiry-quote-row').nth(1)).toContainText('REVOKED');
      await line(page, 1).getByTestId('enquiry-quote-amount').fill('4600');
      await line(page, 1).getByTestId('enquiry-quote-issue').click();
      await expect(line(page, 1).getByTestId('enquiry-quote-row')).toHaveCount(3);

      const quoted = stub.changes.filter((c) => c.path.includes('/quote'));
      expect(quoted).toHaveLength(4);
      quoted.forEach((c, i) => expect(Number((c.ifMatch ?? '').replace(/"/g, ''))).toBe(i));
      expect(stub.changes.at(-2)?.body).toMatchObject({ reason: 'customer asked again' });
    });

    await test.step('a stale version is refused with the problem code, and nothing the screen shows is changed', async () => {
      stub.touch(stub.enquiries[0].id);
      await line(page, 2).getByTestId('enquiry-quote-amount').fill('1000');
      await line(page, 2).getByTestId('enquiry-quote-issue').click();
      await expect(page.getByTestId('enquiry-error')).toContainText('concurrent-modification');
      await expect(line(page, 2).getByTestId('enquiry-quote-row')).toHaveCount(0);
      await page.getByTestId('enquiry-row-open').click();
      await expect(page.getByTestId('enquiry-error')).toHaveCount(0);
    });

    await test.step('a lost line needs its reason, a partly won line its quantity', async () => {
      await line(page, 2).getByTestId('enquiry-outcome-lost').click();
      await expect(page.getByTestId('enquiry-error')).toContainText('enquiry-lost-reason-required');
      await line(page, 2).getByTestId('enquiry-line-reason').fill('a cheaper carrier');
      await line(page, 2).getByTestId('enquiry-line-won-quantity').fill('');
      await line(page, 1).getByTestId('enquiry-outcome-partial').click();
      await expect(page.getByTestId('enquiry-error')).toContainText('enquiry-partial-quantity-invalid');
    });

    await test.step('lines are decided and the enquiry follows them', async () => {
      await line(page, 1).getByTestId('enquiry-line-won-quantity').fill('4');
      await line(page, 1).getByTestId('enquiry-outcome-partial').click();
      await expect(withValue(line(page, 1).getByTestId('enquiry-line-status'), 'PARTIAL')).toBeVisible();
      await expect(page.getByTestId('enquiry-status')).toHaveAttribute('data-value', 'OPEN');
      await line(page, 2).getByTestId('enquiry-outcome-lost').click();
      await expect(line(page, 2).getByTestId('enquiry-line-status')).toContainText('a cheaper carrier');
      await expect(page.getByTestId('enquiry-status')).toHaveAttribute('data-value', 'PARTIAL');
      await expect(page.getByTestId('enquiry-row-status')).toHaveAttribute('data-value', 'PARTIAL');
      await expect(line(page, 1).getByTestId('enquiry-quote-amount')).toHaveCount(0);
      await expect(page.getByTestId('enquiry-cancel')).toHaveCount(0);
    });

    await test.step('converting makes one order, and asking again shows the same one', async () => {
      await page.getByTestId('enquiry-convert').click();
      await expect(page.getByTestId('enquiry-status')).toHaveAttribute('data-value', 'CONVERTED');
      await expect(page.getByTestId('enquiry-order')).toContainText('ORD-2026-000042');
      const order = await page.getByTestId('enquiry-order').getAttribute('data-id');
      await page.getByTestId('enquiry-convert').click();
      await expect(page.getByTestId('enquiry-note')).toBeVisible();
      await expect(page.getByTestId('enquiry-order')).toHaveAttribute('data-id', order!);
      expect(stub.ordersMade).toBe(1);
      expect(stub.changes.filter((c) => c.path.endsWith('/convert')).every((c) => c.ifMatch === null)).toBe(true);
    });
  });

  test('an open enquiry is cancelled with a reason, and then offers nothing more', async ({ page }) => {
    const stub: EnquiryStub = await stubEnquiries(page);
    await signIn(page);
    await openDesk(page);
    await createEnquiry(page, 'RFQ-200');
    await expect(page.getByTestId('enquiry-status')).toHaveAttribute('data-value', 'OPEN');

    await page.getByTestId('enquiry-rediscover').click();
    await expect(page.getByTestId('enquiry-note')).toContainText('rates');
    await page.getByTestId('enquiry-cancel').click();
    await expect(page.getByTestId('enquiry-error')).toBeVisible();

    await page.getByTestId('enquiry-cancel-reason').fill('customer went away');
    await page.getByTestId('enquiry-cancel-reason').press('Enter');
    await expect(page.getByTestId('enquiry-status')).toHaveAttribute('data-value', 'CANCELLED');
    await expect(page.getByTestId('enquiry-cancel-shown')).toHaveText('customer went away');
    await expect(withValue(page.getByTestId('enquiry-line-status').first(), 'CANCELLED')).toBeVisible();
    await expect(page.getByTestId('enquiry-cancel')).toHaveCount(0);
    await expect(page.getByTestId('enquiry-quote-amount')).toHaveCount(0);
    expect(stub.changes.at(-1)).toMatchObject({ body: { reason: 'customer went away' } });
  });

  test('the list is filtered by status and by text, and Enter in either field applies it', async ({ page }) => {
    const stub = await stubEnquiries(page);
    await signIn(page);
    await openDesk(page);
    await createEnquiry(page, 'RFQ-300');
    await createEnquiry(page, 'ZED-400');
    await expect(page.getByTestId('enquiry-row')).toHaveCount(2);

    await page.getByTestId('enquiry-filter-q').fill('zed');
    await page.getByTestId('enquiry-filter-q').press('Enter');
    await expect(page.getByTestId('enquiry-row')).toHaveCount(1);
    expect(stub.listQueries.at(-1)).toContain('q=zed');

    await page.getByTestId('enquiry-filter-q').fill('');
    await page.getByTestId('enquiry-filter-status').selectOption('WON');
    await page.getByTestId('enquiry-filter-q').press('Enter');
    await expect(page.getByTestId('enquiry-none')).toBeVisible();
    expect(stub.listQueries.at(-1)).toContain('status=WON');
  });

  test('without the right to create or update, the forms and the actions are not offered', async ({ page }) => {
    test.skip(!process.env.E2E_STUB_SESSION, 'dropping rights is done on the stubbed sign-in');
    await stubSession(page, ['ORDER_CREATE', 'ORDER_UPDATE']);
    const stub = await stubEnquiries(page);
    await signIn(page);
    await openDesk(page);
    await expect(page.getByTestId('enquiry-create-form')).toHaveCount(0);
    await expect(page.getByTestId('nav-order-upload')).toHaveCount(0);
    expect(stub.enquiries).toHaveLength(0);
  });
});

test.describe('bulk order upload', () => {
  const csv = 'order_ref,origin,destination,quantity\nA1,MUM,DEL,10\nA1,MUM,DEL,5\nB1,MUM,NOWHERE,1\n';

  async function openUpload(page: Page) {
    await page.getByTestId('nav-order-upload').click();
    await expect(page.getByRole('heading', { name: 'Bulk order upload', level: 1 })).toBeVisible();
  }

  test('a file is uploaded and every row is answered with its number, its order and its reasons', async ({ page }) => {
    const stub = await stubUpload(page);
    await signIn(page);
    await openUpload(page);

    await page.getByTestId('upload-customer').fill(customer);
    await page.getByTestId('upload-submit').click();
    await expect(page.getByTestId('upload-error')).toContainText('file');

    await page.getByTestId('upload-file').setInputFiles({ name: 'orders.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
    await page.getByTestId('upload-submit').click();
    await expect(page.getByTestId('upload-row')).toHaveCount(3);
    expect(numberIn(await page.getByTestId('upload-created').textContent())).toBe(1);
    expect(numberIn(await page.getByTestId('upload-failed').textContent())).toBe(1);

    const rows = page.getByTestId('upload-row');
    await expect(rows.nth(0)).toHaveAttribute('data-id', '2');
    await expect(rows.nth(0).getByTestId('upload-row-status')).toHaveAttribute('data-value', 'CREATED');
    await expect(rows.nth(2)).toHaveAttribute('data-id', '4');
    await expect(rows.nth(2).getByTestId('upload-row-status')).toHaveAttribute('data-value', 'FAILED');
    await expect(rows.nth(2).getByTestId('upload-row-reasons')).toContainText('NOWHERE');
    const first = await rows.nth(0).getByTestId('upload-row-order').getAttribute('data-id');
    await expect(rows.nth(1).getByTestId('upload-row-order')).toHaveAttribute('data-id', first!);

    expect(stub.uploads).toHaveLength(1);
    expect(stub.uploads[0]).toContain(customer);
    expect(stub.uploads[0]).toContain('filename="orders.csv"');
    expect(stub.uploads[0]).toContain('A1,MUM,DEL,10');
  });

  test('a refused file shows its problem code and no rows', async ({ page }) => {
    const stub = await stubUpload(page);
    stub.next = { status: 409, body: { title: 'Refused', status: 409, code: 'intake-columns-missing',
      detail: 'The file has no column for: destination (header "destination")' } };
    await signIn(page);
    await openUpload(page);
    await page.getByTestId('upload-customer').fill(customer);
    await page.getByTestId('upload-file').setInputFiles({ name: 'orders.csv', mimeType: 'text/csv', buffer: Buffer.from('order_ref\nA1\n') });
    await page.getByTestId('upload-submit').click();
    await expect(page.getByTestId('upload-error')).toContainText('intake-columns-missing');
    await expect(page.getByTestId('upload-row')).toHaveCount(0);
  });
});
