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
 * Stubs for the e-way bill desk spec: the API answers from a small in-memory model that keeps the server's
 * rules (one live bill per consignment, a reason for an extension or a cancellation, Part-B before a change
 * of vehicle, refusals as problem codes), so the same assertions run against both consoles whatever the demo
 * tenant holds. The requests each console sends are recorded for the spec to check.
 */

export const consignmentId = '11111111-2222-4333-8444-555555555555';
export const otherConsignmentId = '22222222-3333-4444-8555-666666666666';
export const tripId = '33333333-4444-4555-8666-777777777777';
export const unitId = '44444444-5555-4666-8777-888888888888';

const cors = { 'access-control-allow-origin': '*', 'access-control-expose-headers': 'ETag' };
const preflight = { status: 204, headers: { ...cors, 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } };

const json = (body: unknown, status = 200) => ({ status, headers: cors, contentType: 'application/json', body: JSON.stringify(body) });
const problem = (status: number, code: string, detail: string) => json({ title: 'Refused', status, code, detail }, status);

/** The permissions an e-way bill desk holder has; a spec drops some to see what the screen no longer offers. */
export const EWAY_PERMISSIONS = ['ORDER_READ', 'TRIP_READ', 'EWAY_BILL_MANAGE'];

/** Signs in without a backend; used only when E2E_STUB_SESSION is set. */
export async function stubEwaySession(page: Page, dropped: string[] = []): Promise<void> {
  const permissions = EWAY_PERMISSIONS.filter((p) => !dropped.includes(p));
  await page.route('**/api/v1/exceptions/summary', (route) => handle(route, () => json({
    openP1: 0, openP2: 0, openP3: 0, unassigned: 0, settlementHolds: 0, openWithoutTrip: 0 })));
  await page.route('**/api/v1/auth/login', (route) => handle(route, () => json({
    accessToken: 'stub-token', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshToken: 'stub-refresh',
    refreshTokenExpiresAt: '2099-01-01T00:00:00Z', tokenType: 'Bearer', orgUnitId: unitId, permissions })));
}

async function handle(route: Route, answer: () => ReturnType<typeof json>): Promise<void> {
  if (route.request().method() === 'OPTIONS') {
    return route.fulfill(preflight);
  }
  return route.fulfill(answer());
}

interface Bill {
  id: string; billNo: string; status: string; consignmentId: string; trackingRef: string; consignmentStatus: string;
  orgUnitId: string; generatedAt: string; validUntil: string; vehicleNo?: string; distanceKm?: number; source: string;
  extensionCount: number; cancelledAt?: string; cancelReason?: string; expiringSoon: boolean; version: number; createdAt: string;
}

export interface EwayStub {
  bills: Bill[];
  /** The query string of every list request, in order. */
  listQueries: string[];
  /** The path of every lookup by number, in order. */
  lookups: string[];
  /** What each change sent: its method, path and body. */
  changes: Array<{ method: string; path: string; body: unknown }>;
  /** Puts a bill in the model, as if raised earlier. */
  seed(over: Partial<Bill> & { billNo: string }): Bill;
  /** The consignments a trip carries, by tracking reference. */
  tripConsignments: Array<{ id: string; trackingRef: string }>;
}

let sequence = 0;
const uuid = () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`;
const isLive = (b: Bill) => b.status === 'ACTIVE' || b.status === 'EXTENDED';
const normal = (vehicle: string) => vehicle.replace(/ /g, '').toUpperCase();
const addHours = (iso: string, hours: number) => new Date(new Date(iso).getTime() + hours * 3_600_000).toISOString();

function view(b: Bill) {
  return { ...b };
}

export async function stubEway(page: Page): Promise<EwayStub> {
  const stub: EwayStub = {
    bills: [], listQueries: [], lookups: [], changes: [],
    tripConsignments: [
      { id: consignmentId, trackingRef: 'LR-100' },
      { id: otherConsignmentId, trackingRef: 'LR-200' },
      { id: '55555555-6666-4777-8888-999999999999', trackingRef: 'LR-REFUSE-300' },
    ],
    seed: (over) => {
      const now = new Date().toISOString();
      const bill: Bill = {
        id: uuid(), status: 'ACTIVE', consignmentId: uuid(), trackingRef: `LR-${over.billNo.slice(-3)}`, consignmentStatus: 'IN_TRANSIT',
        orgUnitId: unitId, generatedAt: now, validUntil: addHours(now, 24), source: 'STUB', extensionCount: 0, expiringSoon: false,
        version: 0, createdAt: now, ...over,
      };
      stub.bills.unshift(bill);
      return bill;
    },
  };
  const tripBills = () => stub.tripConsignments.map((c) => ({ c, bill: stub.bills.find((b) => b.consignmentId === c.id && isLive(b)) }));

  await page.route(/\/api\/v1\/consignments\/[^/]+\/eway-bills$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const id = new URL(request.url()).pathname.split('/')[4];
    if (request.method() === 'GET') {
      return route.fulfill(json(stub.bills.filter((b) => b.consignmentId === id).map(view)));
    }
    const body = request.postDataJSON() as { documentRef?: string; distanceKm?: number };
    stub.changes.push({ method: 'POST', path: new URL(request.url()).pathname, body });
    if (stub.bills.some((b) => b.consignmentId === id && isLive(b))) {
      return route.fulfill(problem(409, 'eway-bill-already-live', 'The consignment already has an e-way bill'));
    }
    if ((body.documentRef ?? '').toUpperCase().includes('REFUSE')) {
      return route.fulfill(problem(409, 'eway-bill-refused', 'The e-way bill system refused to generate'));
    }
    const km = body.distanceKm ?? 100;
    const now = new Date().toISOString();
    const bill = stub.seed({
      billNo: String(300_000_000_000 + stub.bills.length + 1), consignmentId: id, generatedAt: now,
      validUntil: addHours(now, 24 * Math.max(1, Math.ceil(km / 200))), distanceKm: km,
    });
    return route.fulfill(json({ id: bill.id }, 201));
  });

  await page.route(/\/api\/v1\/trips\/[^/]+\/eway-bills\/part-b$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const body = request.postDataJSON() as { vehicleNo?: string };
    stub.changes.push({ method: 'POST', path: new URL(request.url()).pathname, body });
    const vehicle = normal(body.vehicleNo || 'MH12TRIP0001');
    const items = tripBills().map(({ c, bill }) => {
      if (!bill) {
        return { consignmentId: c.id, trackingRef: c.trackingRef, outcome: 'SKIPPED', code: 'eway-bill-missing', message: 'No live bill' };
      }
      if (c.trackingRef.toUpperCase().includes('REFUSE')) {
        return { consignmentId: c.id, trackingRef: c.trackingRef, ewayBillId: bill.id, billNo: bill.billNo, outcome: 'REFUSED',
          code: 'eway-bill-refused', message: 'Refused' };
      }
      bill.vehicleNo = vehicle;
      bill.version += 1;
      return { consignmentId: c.id, trackingRef: c.trackingRef, ewayBillId: bill.id, billNo: bill.billNo, outcome: 'UPDATED' };
    });
    return route.fulfill(json({ tripId, vehicleNo: vehicle, updated: items.filter((i) => i.outcome === 'UPDATED').length, items }));
  });

  await page.route(/\/api\/v1\/eway-bills(\/.*)?(\?.*)?$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const url = new URL(request.url());
    const parts = url.pathname.split('/').filter(Boolean).slice(3); // after api/v1/eway-bills
    const method = request.method();
    if (parts.length === 0 && method === 'GET') {
      stub.listQueries.push(url.search);
      const status = url.searchParams.get('status');
      const q = (url.searchParams.get('q') ?? '').toLowerCase();
      const items = stub.bills.filter((b) => (!status || b.status === status)
        && (!q || b.billNo.toLowerCase().includes(q) || b.trackingRef.toLowerCase().includes(q)));
      return route.fulfill(json({ items: items.map(view), hasMore: false }));
    }
    if (parts[0] === 'expiring') {
      return route.fulfill(json({ items: stub.bills.filter((b) => isLive(b) && b.expiringSoon).map(view), hasMore: false }));
    }
    if (parts[0] === 'by-number') {
      stub.lookups.push(url.pathname);
      const bill = stub.bills.find((b) => b.billNo === decodeURIComponent(parts[1]));
      return route.fulfill(bill ? json(view(bill)) : problem(404, 'not-found', 'No e-way bill has that number'));
    }
    const bill = stub.bills.find((b) => b.id === parts[0]);
    if (!bill) {
      return route.fulfill(problem(404, 'not-found', 'No such e-way bill'));
    }
    if (parts.length === 1 && method === 'GET') {
      return route.fulfill(json(view(bill)));
    }
    if (parts[1] === 'actions' && method === 'POST') {
      const body = request.postDataJSON() as { action?: string; reason?: string; vehicleNo?: string };
      stub.changes.push({ method, path: url.pathname, body });
      const action = (body.action ?? '').toUpperCase();
      if (!['PART_B', 'EXTEND', 'CANCEL', 'CHANGE_VEHICLE'].includes(action)) {
        return route.fulfill(problem(409, 'eway-action-unknown', 'Unknown e-way bill action'));
      }
      if (!isLive(bill)) {
        return route.fulfill(problem(409, 'eway-bill-not-live', 'Only an active or extended bill can be changed'));
      }
      if ((action === 'EXTEND' || action === 'CANCEL' || action === 'CHANGE_VEHICLE') && !(body.reason ?? '').trim()) {
        return route.fulfill(problem(409, 'eway-reason-required', 'A reason is required for this action'));
      }
      if ((action === 'PART_B' || action === 'CHANGE_VEHICLE') && !(body.vehicleNo ?? '').trim()) {
        return route.fulfill(problem(409, 'eway-vehicle-required', 'A vehicle number is required'));
      }
      if (action === 'CHANGE_VEHICLE' && !bill.vehicleNo) {
        return route.fulfill(problem(409, 'eway-part-b-missing', 'Update Part-B before changing the vehicle'));
      }
      if (action === 'PART_B' || action === 'CHANGE_VEHICLE') {
        bill.vehicleNo = normal(body.vehicleNo ?? '');
      } else if (action === 'EXTEND') {
        bill.status = 'EXTENDED';
        bill.extensionCount += 1;
        bill.validUntil = addHours(bill.validUntil, 24);
        bill.expiringSoon = false;
      } else {
        bill.status = 'CANCELLED';
        bill.cancelReason = (body.reason ?? '').trim();
        bill.cancelledAt = new Date().toISOString();
        bill.expiringSoon = false;
      }
      bill.version += 1;
      return route.fulfill(json(view(bill)));
    }
    return route.fulfill(problem(404, 'not-found', 'No such route'));
  });
  return stub;
}
