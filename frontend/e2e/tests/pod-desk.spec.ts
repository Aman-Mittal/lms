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
import { signIn, withoutPermissions } from './support';

/*
 * The proof of delivery desk, line by line: a draft is started, fed with what
 * was received against each order line, given documents and unloading times,
 * and keyed from paper or reversed. The API is stubbed so the same assertions
 * run against both consoles whatever the demo tenant holds; both consoles send
 * the same requests, which the stub records and the spec checks.
 */

const consignment = '33333333-4444-4555-8666-777777777777';
const lineA = '44444444-5555-4666-8777-888888888888';
const lineB = '55555555-6666-4777-8888-999999999999';
const cors = { 'access-control-allow-origin': '*' };
const preflight = { status: 204, headers: { ...cors, 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } };

interface Stub {
  started: boolean;
  status: string;
  lines: Array<Record<string, unknown>>;
  documents: Array<{ type: string; reference: string }>;
  timings: Record<string, unknown> | null;
  paper: Record<string, unknown> | null;
  reversal: Record<string, unknown> | null;
  linesSent: Array<Record<string, unknown>>;
}

async function stubDeliveries(page: Page): Promise<Stub> {
  const state: Stub = {
    started: false, status: 'PENDING', timings: null, paper: null, reversal: null, linesSent: [], documents: [],
    lines: [
      { orderLineId: lineA, lineNo: 1, materialDescription: 'Cement', uom: 'BAG', grossQty: 10 },
      { orderLineId: lineB, lineNo: 2, materialDescription: 'Steel', uom: 'EA', grossQty: 4 },
    ],
  };
  const json = (body: unknown, status = 200) => ({ status, headers: cors, contentType: 'application/json', body: JSON.stringify(body) });
  await page.route(new RegExp(`/api/v1/deliveries/${consignment}`), async (route) => {
    const request = route.request();
    const url = request.url();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const body = request.postData() ? (request.postDataJSON() as Record<string, unknown>) : {};
    if (request.method() === 'POST' && url.endsWith('/pod')) {
      state.started = true;
      return route.fulfill({ status: 204, headers: cors });
    }
    if (request.method() === 'PUT' && url.endsWith('/pod/lines')) {
      state.linesSent = body.lines as Array<Record<string, unknown>>;
      for (const sent of state.linesSent) {
        const line = state.lines.find((l) => l.orderLineId === sent.orderLineId);
        Object.assign(line!, { receivedQty: sent.receivedQty ?? 0, shortQty: sent.shortQty ?? 0, damagedQty: sent.damagedQty ?? 0, excessQty: sent.excessQty ?? 0 });
      }
      return route.fulfill({ status: 204, headers: cors });
    }
    if (request.method() === 'PUT' && url.endsWith('/pod/documents')) {
      state.documents = body.documents as Array<{ type: string; reference: string }>;
      return route.fulfill({ status: 204, headers: cors });
    }
    if (request.method() === 'PUT' && url.endsWith('/pod/timings')) {
      const [reported, started, ended] = ['reportedAt', 'unloadingStartedAt', 'unloadingEndedAt']
        .map((k) => (body[k] ? Date.parse(body[k] as string) : null));
      const ordered = [reported, started, ended].filter((t): t is number => t !== null)
        .every((t, i, all) => i === 0 || t >= all[i - 1]);
      if (!ordered) {
        return route.fulfill(json({ title: 'Conflict', status: 409, code: 'pod-timing-order-invalid',
          detail: 'Reporting must not come after unloading started, nor unloading started after it ended' }, 409));
      }
      state.timings = body;
      return route.fulfill({ status: 204, headers: cors });
    }
    if (request.method() === 'POST' && url.endsWith('/pod/paper')) {
      state.paper = body;
      state.status = 'SUBMITTED';
      return route.fulfill({ status: 204, headers: cors });
    }
    if (request.method() === 'POST' && url.endsWith('/proof/reversal')) {
      state.reversal = body;
      return route.fulfill(json({ id: '66666666-7777-4888-8999-000000000000', status: 'REQUESTED' }, 202));
    }
    if (!state.started && state.status === 'PENDING') {
      return route.fulfill(json({ title: 'Not found', status: 404, code: 'not-found', detail: 'No proof' }, 404));
    }
    return route.fulfill(json({
      consignmentId: consignment, status: state.status, paper: state.paper !== null,
      keyedBy: state.paper ? '77777777-8888-4999-8aaa-bbbbbbbbbbbb' : undefined,
      recipientName: state.paper ? state.paper.recipientName : undefined,
      goodsCondition: 'CLEAN', lines: state.lines, documents: state.documents,
      reportedAt: state.timings?.reportedAt, unloadingStartedAt: state.timings?.unloadingStartedAt,
      unloadingEndedAt: state.timings?.unloadingEndedAt,
    }));
  });
  return state;
}

async function lookUp(page: Page) {
  await page.getByTestId('nav-pod').click();
  await expect(page.getByRole('heading', { name: 'Proof of delivery desk', level: 1 })).toBeVisible();
  await page.getByTestId('pod-consignment-id').fill(consignment);
  await page.getByTestId('pod-consignment-id').press('Enter');
}

const row = (page: Page, id: string) => page.getByTestId('pod-line-row').and(page.locator(`[data-id="${id}"]`));

test.describe('proof of delivery desk', () => {
  test('a draft is started, fed per line, given documents and times, keyed from paper and a reversal is asked for', async ({ page }) => {
    const stub = await stubDeliveries(page);
    await signIn(page);
    await lookUp(page);

    await test.step('nothing is started yet, and starting it shows every order line', async () => {
      await expect(page.getByTestId('pod-none')).toBeVisible();
      await page.getByTestId('pod-start').click();
      await expect(page.getByTestId('pod-status')).toHaveText('PENDING');
      await expect(page.getByTestId('pod-line-row')).toHaveCount(2);
      await expect(row(page, lineA).getByTestId('pod-line-gross')).toContainText('10');
      await expect(row(page, lineB).getByTestId('pod-line-gross')).toContainText('4');
    });

    await test.step('what was received, short, damaged and in excess is fed against each line', async () => {
      await row(page, lineA).getByTestId('pod-line-received').fill('8');
      await row(page, lineA).getByTestId('pod-line-short').fill('1');
      await row(page, lineA).getByTestId('pod-line-damaged').fill('1');
      await row(page, lineB).getByTestId('pod-line-received').fill('4');
      await row(page, lineB).getByTestId('pod-line-excess').fill('2');
      await page.getByTestId('pod-save-lines').click();
      await expect(row(page, lineA).getByTestId('pod-line-received')).toHaveValue(/^8(\.0+)?$/);
      await expect(row(page, lineA).getByTestId('pod-line-damaged')).toHaveValue(/^1(\.0+)?$/);
      await expect(row(page, lineB).getByTestId('pod-line-excess')).toHaveValue(/^2(\.0+)?$/);
      expect(stub.linesSent).toHaveLength(2);
      expect(stub.linesSent[0]).toMatchObject({ orderLineId: lineA, receivedQty: 8, shortQty: 1, damagedQty: 1 });
    });

    await test.step('documents are listed, kept in order and removed', async () => {
      await page.getByTestId('pod-doc-ref').fill('store://pod/front.jpg');
      await page.getByTestId('pod-doc-add').click();
      await expect(page.getByTestId('pod-doc-row')).toHaveCount(1);
      await page.getByTestId('pod-doc-type').selectOption('BACK');
      await page.getByTestId('pod-doc-ref').fill('store://pod/back.jpg');
      await page.getByTestId('pod-doc-add').click();
      await expect(page.getByTestId('pod-doc-row')).toHaveCount(2);
      await expect(page.getByTestId('pod-doc-text').first()).toHaveText('FRONT: store://pod/front.jpg');
      await expect(page.getByTestId('pod-doc-text').nth(1)).toHaveText('BACK: store://pod/back.jpg');
      expect(stub.documents).toEqual([{ type: 'FRONT', reference: 'store://pod/front.jpg' }, { type: 'BACK', reference: 'store://pod/back.jpg' }]);
      await page.getByTestId('pod-doc-remove').nth(1).click();
      await expect(page.getByTestId('pod-doc-row')).toHaveCount(1);
    });

    await test.step('times out of order are refused with the problem code, in order are saved', async () => {
      await page.getByTestId('pod-reported-at').fill('2026-11-13T10:00');
      await page.getByTestId('pod-unloading-start').fill('2026-11-13T09:00');
      await page.getByTestId('pod-unloading-end').fill('2026-11-13T11:00');
      await page.getByTestId('pod-save-timings').click();
      await expect(page.getByTestId('pod-error')).toContainText('pod-timing-order-invalid');
      await expect(page.getByTestId('pod-timings-saved')).toHaveCount(0);

      await page.getByTestId('pod-unloading-start').fill('2026-11-13T10:30');
      await page.getByTestId('pod-save-timings').click();
      await expect(page.getByTestId('pod-timings-saved')).toBeVisible();
      await expect(page.getByTestId('pod-error')).toHaveCount(0);
      expect(Object.keys(stub.timings ?? {}).sort()).toEqual(['reportedAt', 'unloadingEndedAt', 'unloadingStartedAt']);
    });

    await test.step('paper is keyed with no code and says it was', async () => {
      await page.getByTestId('pod-paper-recipient').fill('Asha Rao');
      await page.getByTestId('pod-paper-submit').click();
      await expect(page.getByTestId('pod-status')).toHaveText('SUBMITTED');
      await expect(page.getByTestId('pod-paper')).toBeVisible();
      await expect(page.getByTestId('pod-keyed-by')).toBeVisible();
      await expect(page.getByTestId('pod-recipient-shown')).toHaveText('Asha Rao');
      await expect(page.getByTestId('pod-save-lines')).toHaveCount(0);
      await expect(row(page, lineA).getByTestId('pod-line-received')).toBeDisabled();
      expect(stub.paper).toMatchObject({ recipientName: 'Asha Rao' });
    });

    await test.step('a submitted proof is reversed only by asking', async () => {
      await page.getByTestId('pod-reversal-reason').fill('Signed by the wrong person');
      await page.getByTestId('pod-request-reversal').click();
      await expect(page.getByTestId('pod-reversal-requested')).toBeVisible();
      expect(stub.reversal).toMatchObject({ reason: 'Signed by the wrong person' });
    });
  });

  test('a repeat lookup reads the proof again', async ({ page }) => {
    const stub = await stubDeliveries(page);
    await signIn(page);
    await lookUp(page);
    await page.getByTestId('pod-start').click();
    await expect(page.getByTestId('pod-status')).toHaveText('PENDING');
    stub.status = 'NOT_RECEIVED';
    await page.getByTestId('pod-consignment-id').press('Enter');
    await expect(page.getByTestId('pod-status')).toHaveText('NOT_RECEIVED');
  });

  test('without the right to key paper, the form is not offered', async ({ page }) => {
    const stub = await stubDeliveries(page);
    stub.started = true;
    await withoutPermissions(page, ['POD_PAPER_KEY']);
    await signIn(page);
    await lookUp(page);
    await expect(page.getByTestId('pod-status')).toHaveText('PENDING');
    await expect(page.getByTestId('pod-submit-form')).toBeVisible();
    await expect(page.getByTestId('pod-paper-form')).toHaveCount(0);
  });
});
