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
 * Stubs for the bulk consignments spec: the consignment register and the bulk amend and bulk cancel. The API answers from a small
 * in-memory model that keeps the server's rules (each item is judged on its own, an amendment under a stale version is refused
 * with concurrent-modification, a consignment already on its way is refused its cancellation, every item answered in the order
 * asked), so the same assertions run against both consoles. The requests each console sends are recorded for the spec. Only the
 * consignment routes are answered; anything else reaches the real API.
 */

export const unitId = '44444444-5555-4666-8777-888888888888';

/** What the holder of the desk may do; a spec drops some to see what the screens no longer offer. */
export const CONSIGNMENT_PERMISSIONS = ['LOAD_READ', 'LOAD_CREATE'];

const cors = { 'access-control-allow-origin': '*', 'access-control-expose-headers': 'ETag' };
const preflight = { status: 204, headers: { ...cors, 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } };
const json = (body: unknown, status = 200) => ({
  status, headers: cors, contentType: 'application/json', body: JSON.stringify(body) });

/** Signs in without a backend; used only when E2E_STUB_SESSION is set. */
export async function stubConsignmentSession(page: Page, dropped: string[] = []): Promise<void> {
  const permissions = CONSIGNMENT_PERMISSIONS.filter((p) => !dropped.includes(p));
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
  body: any;
}

export interface StubConsignment {
  id: string;
  trackingRef: string;
  status: string;
  version: number;
  contactName?: string;
  remarks?: string;
}

export interface ConsignmentStub {
  /** What each change sent. */
  changes: Change[];
  /** The consignments, by tracking reference order. */
  rows: StubConsignment[];
  /** The reference of the one consignment that cannot be cancelled (it is already on its way). */
  onItsWay: string;
  /** Someone else edited this consignment: its version moves on without the console knowing. */
  changeUnderfoot(ref: string): void;
}

/** Ids are built from the number so a spec can name them: `idOf(2)`. */
export const idOf = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
export const refOf = (n: number) => `CN-${String(n).padStart(4, '0')}`;

/** `count` consignments CN-0001 and on, at versions 3, 5, 7 ... for the first three and 1 after. */
export async function stubConsignments(page: Page, count = 3): Promise<ConsignmentStub> {
  const stub: ConsignmentStub = {
    changes: [],
    rows: Array.from({ length: count }, (_, i) => ({
      id: idOf(i + 1), trackingRef: refOf(i + 1), status: 'PLANNED', version: i < 3 ? 3 + 2 * i : 1 })),
    onItsWay: refOf(2),
    changeUnderfoot(ref) {
      const row = stub.rows.find((r) => r.trackingRef === ref);
      if (row) {
        row.version += 1;
      }
    },
  };
  const view = (r: StubConsignment) => ({
    id: r.id, trackingRef: r.trackingRef, status: r.status, orderNo: 'SO-1', consigneeName: 'Acme Traders',
    destinationTerminalCode: 'DEL', totalDeadWeightKg: 100, totalVolumeM3: 1, chargeableWeightKg: 100,
    containsHazmat: false, materialClasses: [], freightMode: 'FTL', contactName: r.contactName, remarks: r.remarks,
    version: r.version });

  await page.route(/\/api\/v1\/consignments(\?.*)?$/, (route) => answer(route, () => {
    const url = new URL(route.request().url());
    const status = url.searchParams.get('status');
    const q = (url.searchParams.get('q') ?? '').toLowerCase();
    const matching = stub.rows.filter((r) => (!status || r.status === status) && r.trackingRef.toLowerCase().includes(q));
    const from = Number(url.searchParams.get('cursor') ?? 0);
    const limit = Math.min(Number(url.searchParams.get('limit') ?? 50), 50);
    const items = matching.slice(from, from + limit).map(view);
    const more = from + limit < matching.length;
    return json({ items, hasMore: more, nextCursor: more ? String(from + limit) : undefined });
  }));

  await page.route(/\/api\/v1\/consignments\/amendments$/, (route) => answer(route, () => {
    const body = route.request().postDataJSON();
    stub.changes.push({ method: 'POST', path: '/api/v1/consignments/amendments', body });
    return json({ items: body.items.map((item: { consignmentId: string; version: number; changes: Record<string, string> }) => {
      const row = stub.rows.find((r) => r.id === item.consignmentId);
      if (!row) {
        return { consignmentId: item.consignmentId, outcome: 'REFUSED', code: 'not-found', message: 'No such consignment' };
      }
      if (row.version !== item.version) {
        return { consignmentId: row.id, outcome: 'REFUSED', code: 'concurrent-modification',
          message: 'The consignment was changed by someone else; re-read it and try again' };
      }
      Object.assign(row, item.changes);
      row.version += 1;
      return { consignmentId: row.id, outcome: 'AMENDED', version: row.version, fields: [] };
    }) });
  }));

  await page.route(/\/api\/v1\/consignments\/cancellations$/, (route) => answer(route, () => {
    const body = route.request().postDataJSON();
    stub.changes.push({ method: 'POST', path: '/api/v1/consignments/cancellations', body });
    return json({ items: body.consignmentIds.map((id: string) => {
      const row = stub.rows.find((r) => r.id === id);
      if (!row) {
        return { consignmentId: id, outcome: 'REFUSED', code: 'not-found', message: 'No such consignment' };
      }
      if (row.trackingRef === stub.onItsWay) {
        return { consignmentId: id, outcome: 'REFUSED', code: 'consignment-dispatched', message: 'It is already on its way' };
      }
      row.status = 'CANCELLED';
      row.version += 1;
      return { consignmentId: id, outcome: 'CANCELLED' };
    }) });
  }));
  return stub;
}
