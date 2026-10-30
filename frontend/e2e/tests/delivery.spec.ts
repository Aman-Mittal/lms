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
  apiSession, newClaim, newConsignment, openClaims, printed, signIn, stubPrint, tag,
  withoutPermissions,
} from './support';

/** Opens a claim's panel by its number. */
async function openClaim(page: Page, claimNo: string) {
  const row = page.getByTestId('row').filter({ hasText: claimNo });
  await expect(row).toBeVisible();
  await row.click();
  const panel = page.getByTestId('claim-panel');
  await expect(panel.getByRole('heading', { name: claimNo })).toBeVisible();
  return panel;
}

test.describe('claims', () => {
  test('a claim is raised in the console and taken through review to settlement', async ({ page, request }, info) => {
    const id = tag(info);
    const consignment = await newConsignment(request, await apiSession(request));
    await signIn(page);
    await openClaims(page);

    await test.step('raise a loss claim', async () => {
      const form = page.getByTestId('raise-claim');
      await form.getByLabel('Consignment id').fill(consignment);
      await form.getByLabel('Claim type').selectOption('LOSS');
      await form.getByLabel('Amount').fill('1250.50');
      await form.getByLabel('Description').fill(`Lost in transit ${id}`);
      await form.getByRole('button', { name: 'Raise claim' }).click();

      const row = page.getByTestId('row').filter({ hasText: consignment });
      await expect(row).toContainText('OPEN');
      await expect(row).toContainText('CLM-');
      await row.click();
    });

    const panel = page.getByTestId('claim-panel');
    await test.step('only the step that is next is offered', async () => {
      await expect(panel.getByTestId('claim-status')).toHaveText('OPEN');
      await expect(panel.getByTestId('act-review')).toBeVisible();
      await expect(panel.getByTestId('act-approve')).toHaveCount(0);
      await expect(panel.getByTestId('act-settle')).toHaveCount(0);
    });

    await test.step('review, then try to approve more than was claimed', async () => {
      await panel.getByTestId('act-review').click();
      await expect(panel.getByTestId('claim-status')).toHaveText('UNDER_REVIEW');

      await panel.getByTestId('approved-amount').fill('2000');
      await panel.getByTestId('act-approve').click();
      await expect(panel.getByRole('alert')).toContainText('more than');
      await expect(panel.getByTestId('claim-status')).toHaveText('UNDER_REVIEW');
    });

    await test.step('approve for less, then settle', async () => {
      await panel.getByTestId('approved-amount').fill('900.25');
      await panel.getByTestId('act-approve').click();
      await expect(panel.getByTestId('claim-status')).toHaveText('APPROVED');
      await expect(panel.getByTestId('claim-approved')).toHaveText('900.25');

      await panel.getByTestId('act-settle').click();
      await expect(panel.getByTestId('claim-status')).toHaveText('SETTLED');
      await expect(panel.getByTestId('act-settle')).toHaveCount(0);
      await expect(panel.getByTestId('act-review')).toHaveCount(0);
    });
  });

  test('a rejection needs a reason, and the reason is kept', async ({ page, request }) => {
    const session = await apiSession(request);
    const claim = await newClaim(request, session, await newConsignment(request, session));
    await signIn(page);
    await openClaims(page);

    const panel = await openClaim(page, claim.claimNo);
    await panel.getByTestId('act-review').click();
    await expect(panel.getByTestId('claim-status')).toHaveText('UNDER_REVIEW');

    await panel.getByTestId('act-reject').click();
    await expect(panel.getByRole('alert')).toContainText('needs a reason');
    await expect(panel.getByTestId('claim-status')).toHaveText('UNDER_REVIEW');

    await panel.getByTestId('reject-note').fill('Inside the agreed window');
    await panel.getByTestId('act-reject').click();
    await expect(panel.getByTestId('claim-status')).toHaveText('REJECTED');
    await expect(panel).toContainText('Inside the agreed window');
    await expect(panel.getByTestId('act-settle')).toHaveCount(0);
  });

  test('damage cannot be claimed before delivery is proved, and the reason is shown', async ({ page, request }) => {
    const consignment = await newConsignment(request, await apiSession(request));
    await signIn(page);
    await openClaims(page);

    const form = page.getByTestId('raise-claim');
    await form.getByLabel('Consignment id').fill(consignment);
    await form.getByLabel('Claim type').selectOption('DAMAGE');
    await form.getByLabel('Amount').fill('100');
    await form.getByLabel('Description').fill('Crushed pallet');
    await form.getByRole('button', { name: 'Raise claim' }).click();

    await expect(page.getByRole('alert')).toContainText('proof of delivery');
    await expect(page.getByTestId('row').filter({ hasText: consignment })).toHaveCount(0);
  });
});

test.describe('what each person is offered', () => {
  test('without the right to raise or decide, claims can be read and printed but not changed', async ({ page, request }) => {
    const session = await apiSession(request);
    const claim = await newClaim(request, session, await newConsignment(request, session));
    await withoutPermissions(page, ['CLAIM_RAISE', 'CLAIM_DECIDE']);
    await signIn(page);
    await openClaims(page);

    await expect(page.getByTestId('raise-claim')).toHaveCount(0);
    const panel = await openClaim(page, claim.claimNo);
    await expect(panel.getByTestId('claim-status')).toHaveText('OPEN');
    await expect(panel.getByTestId('act-review')).toHaveCount(0);
    await expect(panel.getByTestId('print-claim')).toBeVisible();
  });

  test('without the right to read them, neither claims nor the delivery desk is in the sidebar', async ({ page }) => {
    await withoutPermissions(page, ['CLAIM_READ', 'POD_READ']);
    await signIn(page);

    await expect(page.getByTestId('sidebar')).toBeVisible();
    await expect(page.getByTestId('nav-claims')).toHaveCount(0);
    await expect(page.getByTestId('nav-delivery')).toHaveCount(0);
    await expect(page.getByTestId('nav-partners')).toBeVisible();
  });
});

test.describe('print and QR codes', () => {
  test('a claim slip carries a QR code of its number, and prints on its own', async ({ page, request }) => {
    const session = await apiSession(request);
    const claim = await newClaim(request, session, await newConsignment(request, session));
    await stubPrint(page);
    await signIn(page);
    await openClaims(page);

    const panel = await openClaim(page, claim.claimNo);
    await expect(panel.getByTestId('claim-slip')).toHaveCount(0);
    await panel.getByTestId('print-claim').click();

    const slip = panel.getByTestId('claim-slip');
    await expect(slip).toContainText(claim.claimNo);
    const qr = slip.getByTestId('qr');
    await expect(qr).toHaveAttribute('data-payload', `LMS:CLAIM:${claim.claimNo}`);
    // Drawn in the browser: a data URL that really decoded to an image.
    await expect(qr).toHaveAttribute('src', /^data:image\/png;base64,/);
    await expect.poll(() => qr.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);

    await slip.getByTestId('print-now').click();
    expect(await printed(page)).toBe(1);

    // What the printer is sent: the slip, and none of the console around it.
    await page.emulateMedia({ media: 'print' });
    await expect(slip).toBeVisible();
    await expect(page.getByTestId('sidebar')).toBeHidden();
    await expect(slip.getByTestId('print-now')).toBeHidden();
  });
});

test.describe('delivery desk', () => {
  test('a consignment that has not left cannot be given a code, and the reason is shown', async ({ page, request }) => {
    const consignment = await newConsignment(request, await apiSession(request));
    await signIn(page);

    await page.getByTestId('nav-delivery').click();
    await expect(page.getByRole('heading', { name: 'Delivery desk', level: 1 })).toBeVisible();

    await page.getByTestId('consignment-id').fill(consignment);
    await page.getByRole('button', { name: 'Look up' }).click();
    await expect(page.getByTestId('no-proof')).toBeVisible();

    await page.getByTestId('issue-otp').click();
    await expect(page.getByRole('alert')).toContainText('has left');
    await expect(page.getByTestId('otp-code')).toHaveCount(0);
    await expect(page.getByTestId('no-proof')).toBeVisible();
  });

  test('a look-up that is not a consignment id is refused rather than ignored', async ({ page }) => {
    await signIn(page);
    await page.getByTestId('nav-delivery').click();

    await page.getByTestId('consignment-id').fill('not-a-consignment');
    await page.getByRole('button', { name: 'Look up' }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.getByTestId('proof')).toHaveCount(0);
  });

  /*
   * The happy path needs a consignment that a real trip has carried, which the
   * backend's own end-to-end suite builds and proves over HTTP. Here the three
   * delivery calls are answered by the test, to check what the screen does
   * with each answer: show the demo code, refuse a wrong one, show the proof,
   * and print its slip.
   */
  test('the code is issued, a wrong one is refused, the right one records the proof and prints', async ({ page }) => {
    const consignment = '11111111-2222-4333-8444-555555555555';
    let state: 'none' | 'issued' | 'confirmed' = 'none';
    const cors = { 'access-control-allow-origin': '*' };
    const problem = (status: number, detail: string, code: string) => ({
      status, headers: { ...cors, 'content-type': 'application/problem+json' },
      body: JSON.stringify({ status, detail, code }),
    });

    await page.route(new RegExp(`/api/v1/deliveries/${consignment}`), async (route) => {
      const request = route.request();
      const url = request.url();
      if (request.method() === 'OPTIONS') {
        return route.fulfill({ status: 204, headers: {
          ...cors, 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } });
      }
      if (request.method() === 'POST' && url.endsWith('/otp')) {
        state = 'issued';
        return route.fulfill({ status: 200, headers: cors, contentType: 'application/json',
          body: JSON.stringify({ expiresAt: '2099-01-01T00:00:00Z', otp: '123456' }) });
      }
      if (request.method() === 'POST' && url.endsWith('/proof')) {
        if ((request.postDataJSON() as { otp: string }).otp !== '123456') {
          return route.fulfill(problem(409, 'The code is not valid, has expired, or has been tried too often', 'otp-invalid'));
        }
        state = 'confirmed';
        return route.fulfill({ status: 204, headers: cors });
      }
      if (state === 'none') {
        return route.fulfill(problem(404, 'Delivery proof not found', 'not-found'));
      }
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json',
        body: JSON.stringify(state === 'issued'
          ? { consignmentId: consignment, status: 'OTP_ISSUED', otpExpiresAt: '2099-01-01T00:00:00Z' }
          : { consignmentId: consignment, status: 'CONFIRMED', recipientName: 'Asha Rao',
              photoRef: 's3://pod/asha.jpg', deliveredAt: '2026-10-30T10:00:00Z' }) });
    });

    await stubPrint(page);
    await signIn(page);
    await page.getByTestId('nav-delivery').click();
    await page.getByTestId('consignment-id').fill(consignment);
    await page.getByRole('button', { name: 'Look up' }).click();
    await expect(page.getByTestId('no-proof')).toBeVisible();

    await page.getByTestId('issue-otp').click();
    await expect(page.getByTestId('otp-code')).toHaveText('123456');
    await expect(page.getByTestId('proof-status')).toHaveText('OTP_ISSUED');

    const form = page.getByTestId('record-proof');
    await form.getByLabel('Recipient name').fill('Asha Rao');
    await form.getByLabel('Code').fill('000000');
    await form.getByRole('button', { name: 'Record proof' }).click();
    await expect(page.getByRole('alert')).toContainText('not valid');
    await expect(page.getByTestId('proof-status')).toHaveText('OTP_ISSUED');

    await form.getByLabel('Code').fill('123456');
    await form.getByLabel('Photo reference').fill('s3://pod/asha.jpg');
    await form.getByRole('button', { name: 'Record proof' }).click();
    await expect(page.getByTestId('proof-status')).toHaveText('CONFIRMED');
    await expect(page.getByTestId('proof-recipient')).toHaveText('Asha Rao');
    await expect(page.getByTestId('issue-otp')).toHaveCount(0);

    await page.getByTestId('print-proof').click();
    const slip = page.getByTestId('proof-slip');
    await expect(slip.getByTestId('qr')).toHaveAttribute('data-payload', `LMS:CONSIGNMENT:${consignment}`);
    await slip.getByTestId('print-now').click();
    expect(await printed(page)).toBe(1);
  });
});
