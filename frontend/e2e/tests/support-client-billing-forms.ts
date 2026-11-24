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

import type { Page, Route } from '@playwright/test';

/*
 * Stubs for the client billing forms spec: portal access, hand-recorded service usage, a month's storage and the margin
 * report. The API answers from a small in-memory model that keeps the server's rules (a user is granted once and may be
 * granted again, a source key belongs to one usage, an ended month only is computed, the margin report pages by
 * cursor), so the same assertions run against both consoles. The requests each console sends are recorded for the spec.
 * Only the client billing routes are answered; anything else reaches the real API.
 */

export const unitId = '44444444-5555-4666-8777-888888888888';
export const clientId = '55555555-6666-4777-8888-999999999999';
export const portalUserId = '66666666-7777-4888-8999-aaaaaaaaaaaa';
export const strangerId = '77777777-8888-4999-8aaa-bbbbbbbbbbbb';

/** What the holder of the desk may do; a spec drops some to see what the screens no longer offer. */
export const CLIENT_BILLING_PERMISSIONS = ['CLIENT_BILLING_READ', 'CLIENT_BILLING_RUN', 'CLIENT_CONTRACT_MANAGE',
  'CLIENT_MARGIN_READ'];

const cors = { 'access-control-allow-origin': '*', 'access-control-expose-headers': 'ETag' };
const preflight = { status: 204, headers: { ...cors, 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } };
const json = (body: unknown, status = 200) => ({
  status, headers: cors, contentType: 'application/json', body: JSON.stringify(body) });
const problem = (status: number, code: string, detail: string) => json({ title: 'Refused', status, code, detail }, status);
const none = () => ({ status: 204, headers: cors });
const slice = (items: unknown[], nextCursor?: string) => json({ items, hasMore: !!nextCursor, nextCursor });

/** Signs in without a backend; used only when E2E_STUB_SESSION is set. */
export async function stubClientBillingSession(page: Page, dropped: string[] = []): Promise<void> {
  const permissions = CLIENT_BILLING_PERMISSIONS.filter((p) => !dropped.includes(p));
  await page.route('**/api/v1/exceptions/summary', (route) => answer(route, () => json({
    openP1: 0, openP2: 0, openP3: 0, unassigned: 0, settlementHolds: 0, openWithoutTrip: 0 })));
  await page.route('**/api/v1/auth/login', (route) => answer(route, () => json({
    accessToken: 'stub-token', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshToken: 'stub-refresh',
    refreshTokenExpiresAt: '2099-01-01T00:00:00Z', tokenType: 'Bearer', orgUnitId: unitId, permissions })));
}

async function answer(route: Route, reply: () => ReturnType<typeof json>): Promise<void> {
  if (route.request().method() === 'OPTIONS') {
    return route.fulfill(preflight);
  }
  return route.fulfill(reply());
}

export interface Change {
  method: string;
  path: string;
  body: unknown;
}

export interface ClientBillingStub {
  /** What each change sent. */
  changes: Change[];
  /** The users granted portal access. */
  portalUsers: string[];
  /** The source keys of the usage recorded. */
  usageKeys: string[];
  /** The months (first days) computed. */
  computedMonths: string[];
  /** The margin report is refused for lack of the right, though the screen offered it. */
  refuseMargins: boolean;
  /** The users list is refused, so the portal form falls back to typing an id. */
  refuseUsers: boolean;
}

/** The tenant's users: the one to pick, an unknown one the server will refuse, and one switched off that is not offered. */
export const USERS = [
  { id: portalUserId, email: 'asha@example.test', fullName: 'Asha Rao', status: 'ACTIVE', orgName: 'HQ', roles: [], version: 0 },
  { id: strangerId, email: 'ghost@example.test', fullName: 'Ghost Writer', status: 'ACTIVE', orgName: 'HQ', roles: [], version: 0 },
  { id: '88888888-9999-4aaa-8bbb-cccccccccccc', email: 'old@example.test', fullName: 'Old Hand', status: 'SUSPENDED', orgName: 'HQ',
    roles: [], version: 0 },
];

const margin = (invoiceNo: string, code: string, receivable: number, carrier: number) => ({
  clientPartnerId: clientId, clientCode: code, clientName: `${code} Traders`, invoiceId: invoiceNo, invoiceNo,
  periodStart: '2026-10-01', currency: 'INR', receivable, transportReceivable: receivable - 100, warehouseReceivable: 100,
  credited: 0, carrierPayable: carrier, margin: receivable - carrier, marginPct: 20, movements: 3, unpricedMovements: 0 });

export async function stubClientBilling(page: Page): Promise<ClientBillingStub> {
  const stub: ClientBillingStub = { changes: [], portalUsers: [], usageKeys: [], computedMonths: [], refuseMargins: false, refuseUsers: false };
  await page.route(/\/api\/v1\/users(\?.*)?$/, (route) => answer(route, () => {
    if (stub.refuseUsers) {
      return problem(403, 'access-denied', 'Not allowed');
    }
    const status = new URL(route.request().url()).searchParams.get('status');
    return json(USERS.filter((u) => !status || u.status === status));
  }));
  await page.route('**/api/v1/client-billing/**', async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    const body = method === 'GET' ? undefined : request.postDataJSON();
    if (method !== 'GET') {
      stub.changes.push({ method, path, body });
    }

    if (path.endsWith('/portal-users')) {
      if (body.userId === strangerId) {
        return route.fulfill(problem(404, 'not-found', 'No such user'));
      }
      stub.portalUsers.push(body.userId);
      return route.fulfill(none());
    }
    if (path.endsWith('/service-usage')) {
      if (stub.usageKeys.includes(body.sourceKey)) {
        return route.fulfill(problem(409, 'client-charge-key-reused', 'That source key belongs to another charge'));
      }
      stub.usageKeys.push(body.sourceKey);
      return route.fulfill(json({ id: `00000000-0000-4000-8000-${String(stub.usageKeys.length).padStart(12, '0')}` }, 201));
    }
    if (path.endsWith('/storage-computations')) {
      const period = String(body.period).slice(0, 7);
      if (period >= new Date().toISOString().slice(0, 7)) {
        return route.fulfill(problem(409, 'client-period-not-ended', 'The month has not ended'));
      }
      if (!stub.computedMonths.includes(`${period}-01`)) {
        stub.computedMonths.push(`${period}-01`);
      }
      return route.fulfill(json(storage(stub, `${period}-01`)));
    }
    if (path.endsWith('/storage')) {
      return route.fulfill(json(storage(stub, `${String(url.searchParams.get('period')).slice(0, 7)}-01`)));
    }
    if (path.endsWith('/margins')) {
      if (stub.refuseMargins) {
        return route.fulfill(problem(403, 'access-denied', 'Not allowed'));
      }
      if (url.searchParams.get('period') === '2026-09-01') {
        return route.fulfill(slice([]));
      }
      return route.fulfill(url.searchParams.get('cursor') === 'page-2'
        ? slice([margin('INV-0002', 'BETA', 2000, 1500)])
        : slice([margin('INV-0001', 'ALPHA', 1000, 800)], 'page-2'));
    }
    if (path.endsWith('/ageing')) {
      return route.fulfill(json({ currency: 'INR', outstanding: 0, unappliedCredit: 0, buckets: {} }));
    }
    if (/\/(charges|invoices|statement)$/.test(path)) {
      return route.fulfill(slice([]));
    }
    return route.fulfill(json([]));
  });
  return stub;
}

function storage(stub: ClientBillingStub, periodStart: string) {
  const computed = stub.computedMonths.includes(periodStart);
  return {
    clientPartnerId: clientId, periodStart, computed, unitDays: computed ? 60 : 0, palletDays: computed ? 6 : 0,
    days: computed ? [
      { heldOn: periodStart, unitsHeld: 30, unitsPerPallet: 10, pallets: 3, chargeId: unitId },
      { heldOn: `${periodStart.slice(0, 8)}02`, unitsHeld: 30, unitsPerPallet: 10, pallets: 3, chargeId: unitId },
    ] : [],
  };
}
