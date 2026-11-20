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
 * Stubs for the small-operations spec: approval policies, number series revision, order lines and close settings,
 * recording an e-way bill and route corridors. The API answers from a small in-memory model that keeps the server's
 * rules (a mandatory policy cannot be switched off, an edit under If-Match needs the current version, a series that
 * issued keeps its format, only a draft takes a line, the last live line cannot be cancelled, a bill number is
 * recorded once), so the same assertions run against both consoles whatever the demo tenant holds. The requests each
 * console sends are recorded for the spec. Only these routes are answered; anything else reaches the real API.
 */

export const unitId = '44444444-5555-4666-8777-888888888888';
export const draftOrderId = '11111111-2222-4333-8444-555555555555';
export const validatedOrderId = '22222222-3333-4444-8555-666666666666';
export const consignmentId = '33333333-4444-4555-8666-777777777777';
export const terminalId = '66666666-7777-4888-8999-aaaaaaaaaaaa';
export const otherTerminalId = '77777777-8888-4999-8aaa-bbbbbbbbbbbb';
export const seriesId = '99999999-aaaa-4bbb-8ccc-dddddddddddd';

/** What the holder of every screen here may do; a spec drops some to see what the screens no longer offer. */
export const SMALL_OPS_PERMISSIONS = ['APPROVAL_READ', 'APPROVAL_POLICY_MANAGE', 'NUMBER_SERIES_MANAGE', 'ORDER_READ',
  'ORDER_UPDATE', 'ORDER_FORCE_CLOSE', 'EWAY_BILL_MANAGE', 'EXCEPTION_READ', 'TELEMATICS_INGEST'];

const cors = { 'access-control-allow-origin': '*', 'access-control-expose-headers': 'ETag' };
const preflight = { status: 204, headers: { ...cors, 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } };
const json = (body: unknown, status = 200, headers: Record<string, string> = {}) => ({
  status, headers: { ...cors, ...headers }, contentType: 'application/json', body: JSON.stringify(body) });
const problem = (status: number, code: string, detail: string) => json({ title: 'Refused', status, code, detail }, status);
const none = () => ({ status: 204, headers: cors });
const slice = (items: unknown[]) => json({ items, hasMore: false });

/** Signs in without a backend; used only when E2E_STUB_SESSION is set. */
export async function stubSmallOpsSession(page: Page, dropped: string[] = []): Promise<void> {
  const permissions = SMALL_OPS_PERMISSIONS.filter((p) => !dropped.includes(p));
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

interface Policy {
  subjectType: string; required: boolean; mandatory: boolean; decidePermissions: string[]; ordered: boolean;
  allowSelfApproval: boolean; dueMinutes?: number; onExpiry: string;
}

interface Series {
  id: string; code: string; documentType: string; prefix: string; yearCode: string; separator: string;
  counterLength: number; yearStartMonth: number; timeZone: string; reuseReleased: boolean; active: boolean;
  description: string; financialYear: number; issuedThisYear: number; releasedAvailable: number; sample: string;
  version: number;
}

interface Line {
  id: string; lineNo: number; materialCode: string; materialDescription: string; materialClass: string; quantity: number;
  uom: string; deadWeightKg: number; destinationTerminalId: string; destinationTerminalCode: string; planned: boolean;
  cancelled: boolean;
}

interface Order {
  id: string; orderNo: string; status: string; version: number; lines: Line[];
}

interface Bill {
  id: string; billNo: string; consignmentId: string; validUntil: string; vehicleNo?: string; distanceKm?: number;
}

interface Corridor {
  id: string; name: string; originTerminalId: string; originTerminalCode: string; destinationTerminalId: string;
  destinationTerminalCode: string; pointCount: number; widthMetres?: number;
}

export interface Change {
  method: string;
  path: string;
  body: unknown;
  ifMatch: string | null;
}

export interface SmallOpsStub {
  policies: Policy[];
  series: Series;
  orders: Order[];
  closeSettings: { tolerancePercent: number; autoFulfil: boolean; customised: boolean };
  bills: Bill[];
  corridors: Corridor[];
  /** What each change sent. */
  changes: Change[];
  /** Somebody else revises the series: its version moves on under the open screen. */
  reviseElsewhere(): void;
  /** The series is read as having issued numbers this year, which locks its format. */
  issued(count: number): void;
}

let sequence = 0;
const uuid = () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`;

const DEFAULTS: Policy[] = [
  { subjectType: 'ORDER_CANCELLATION', required: false, mandatory: false, decidePermissions: ['ORDER_APPROVE'],
    ordered: false, allowSelfApproval: false, dueMinutes: 1440, onExpiry: 'EXPIRE' },
  { subjectType: 'PROOF_REVERSAL', required: true, mandatory: true, decidePermissions: ['POD_APPROVE'],
    ordered: false, allowSelfApproval: false, dueMinutes: 1440, onExpiry: 'EXPIRE' },
];

function lineOf(lineNo: number, code: string): Line {
  return { id: uuid(), lineNo, materialCode: code, materialDescription: `${code} goods`, materialClass: 'GENERAL',
    quantity: 1, uom: 'EA', deadWeightKg: 100, destinationTerminalId: terminalId, destinationTerminalCode: 'DST',
    planned: false, cancelled: false };
}

const orderView = (o: Order) => ({
  id: o.id, orderNo: o.orderNo, status: o.status, customerPartnerId: uuid(), customerName: 'Customer',
  originTerminalCode: 'ORG', originTerminalId: terminalId, freightMode: 'FTL', plannedLineCount: 0,
  totalDeadWeightKg: 100 * o.lines.length, lineCount: o.lines.length, containsHazmat: false, version: o.version,
  createdAt: new Date().toISOString() });

const billView = (b: Bill) => ({
  id: b.id, billNo: b.billNo, status: 'ACTIVE', consignmentId: b.consignmentId, trackingRef: 'LR-100', orgUnitId: unitId,
  generatedAt: new Date().toISOString(), validUntil: b.validUntil, vehicleNo: b.vehicleNo, distanceKm: b.distanceKm,
  source: 'RECORDED', extensionCount: 0, expiringSoon: false, version: 0, createdAt: new Date().toISOString() });

const corridorView = (c: Corridor) => ({ ...c, version: 0, updatedAt: new Date().toISOString() });

export async function stubSmallOps(page: Page): Promise<SmallOpsStub> {
  const stub: SmallOpsStub = {
    policies: DEFAULTS.map((p) => ({ ...p, decidePermissions: [...p.decidePermissions] })),
    series: {
      id: seriesId, code: 'LR-MAIN', documentType: 'CONSIGNMENT_NOTE', prefix: 'LR', yearCode: 'START_YEAR', separator: '-',
      counterLength: 5, yearStartMonth: 4, timeZone: 'UTC', reuseReleased: false, active: true, description: 'Main series',
      financialYear: 2026, issuedThisYear: 0, releasedAvailable: 0, sample: 'LR-2026-00001', version: 3,
    },
    orders: [
      { id: draftOrderId, orderNo: 'ORD-DRAFT', status: 'DRAFT', version: 1, lines: [lineOf(1, 'STEEL')] },
      { id: validatedOrderId, orderNo: 'ORD-VALID', status: 'VALIDATED', version: 4, lines: [lineOf(1, 'COIL'), lineOf(2, 'SHEET')] },
    ],
    closeSettings: { tolerancePercent: 2, autoFulfil: false, customised: false },
    bills: [],
    corridors: [{
      id: uuid(), name: 'Old route', originTerminalId: terminalId, originTerminalCode: 'ORG',
      destinationTerminalId: otherTerminalId, destinationTerminalCode: 'DST', pointCount: 40, widthMetres: 500,
    }],
    changes: [],
    reviseElsewhere: () => { stub.series.version += 1; },
    issued: (count) => { stub.series.issuedThisYear = count; },
  };
  const note = (route: Route, body: unknown) => {
    const request = route.request();
    stub.changes.push({
      method: request.method(), path: new URL(request.url()).pathname, body, ifMatch: request.headers()['if-match'] ?? null,
    });
  };
  const versionOf = (ifMatch: string | null): number | null => (ifMatch === null ? null : Number(ifMatch.replace(/"/g, '')));
  /** Runs a handler for a route, answering the browser's preflight itself; the path comes as parts after `after`. */
  const serve = async (pattern: RegExp, after: string, handler: (route: Route, parts: string[], method: string) => Promise<void> | void) =>
    page.route(pattern, async (route) => {
      const request = route.request();
      if (request.method() === 'OPTIONS') {
        return route.fulfill(preflight);
      }
      const parts = new URL(request.url()).pathname.split('/').filter(Boolean).slice(after.split('/').filter(Boolean).length);
      return handler(route, parts, request.method());
    });

  await serve(/\/api\/v1\/approvals(\/.*)?(\?.*)?$/, 'api/v1/approvals', async (route, parts, method) => {
    if (parts.length === 0) {
      return route.fulfill(slice([]));
    }
    if (parts[0] !== 'policies') {
      return route.fulfill(problem(404, 'not-found', 'No such route'));
    }
    if (parts.length === 1) {
      return route.fulfill(json(stub.policies));
    }
    const policy = stub.policies.find((p) => p.subjectType === parts[1]);
    if (!policy) {
      return route.fulfill(problem(404, 'not-found', 'No such subject type'));
    }
    const base = DEFAULTS.find((p) => p.subjectType === parts[1])!;
    if (method === 'DELETE') {
      note(route, undefined);
      Object.assign(policy, { ...base, decidePermissions: [...base.decidePermissions] });
      return route.fulfill(json(policy));
    }
    const body = route.request().postDataJSON() as Partial<Policy>;
    note(route, body);
    if (policy.mandatory && body.required === false) {
      return route.fulfill(problem(409, 'approval-mandatory', 'That happens only through an approval'));
    }
    Object.assign(policy, {
      required: body.required ?? base.required, decidePermissions: body.decidePermissions ?? base.decidePermissions,
      ordered: body.ordered ?? base.ordered, allowSelfApproval: body.allowSelfApproval ?? base.allowSelfApproval,
      dueMinutes: body.dueMinutes ?? base.dueMinutes, onExpiry: body.onExpiry ?? base.onExpiry,
    });
    return route.fulfill(json(policy));
  });

  await serve(/\/api\/v1\/number-series(\/.*)?(\?.*)?$/, 'api/v1/number-series', async (route, parts, method) => {
    const s = stub.series;
    if (parts.length === 0) {
      return route.fulfill(slice([s]));
    }
    if (parts[0] === 'scopes') {
      return route.fulfill(slice([]));
    }
    if (parts[0] !== s.id) {
      return route.fulfill(problem(404, 'not-found', 'No such series'));
    }
    if (parts.length === 1 && method === 'GET') {
      return route.fulfill(json(s, 200, { ETag: `"${s.version}"` }));
    }
    if (parts[1] === 'preview') {
      return route.fulfill(json({ seriesId: s.id, number: 'LR-2026-00001', financialYear: 2026, fromPool: false }));
    }
    if (parts[1] === 'releases') {
      return route.fulfill(slice([]));
    }
    if (parts.length === 1 && method === 'PUT') {
      const body = route.request().postDataJSON() as Partial<Series>;
      note(route, body);
      const version = versionOf(route.request().headers()['if-match'] ?? null);
      if (version === null) {
        return route.fulfill(problem(400, 'if-match-required', 'Send the series version in If-Match'));
      }
      if (version !== s.version) {
        return route.fulfill(problem(409, 'concurrent-modification', 'The series changed since it was read'));
      }
      const formatChanged = body.prefix !== s.prefix || body.yearCode !== s.yearCode || body.separator !== s.separator
        || body.counterLength !== s.counterLength || body.yearStartMonth !== s.yearStartMonth;
      if (formatChanged && s.issuedThisYear > 0) {
        return route.fulfill(problem(409, 'number-series-in-use', 'The series has issued numbers'));
      }
      Object.assign(s, {
        prefix: body.prefix, yearCode: body.yearCode, separator: body.separator ?? '', counterLength: body.counterLength,
        yearStartMonth: body.yearStartMonth, timeZone: body.timeZone ?? '', reuseReleased: body.reuseReleased,
        description: body.description ?? '', sample: `${body.prefix}-2026-00001`, version: s.version + 1,
      });
      return route.fulfill(none());
    }
    return route.fulfill(problem(404, 'not-found', 'No such route'));
  });

  await serve(/\/api\/v1\/orders\/(close-settings|[0-9a-f-]{36}(\/lines(\/[0-9a-f-]{36}\/cancellation)?)?)(\?.*)?$/, 'api/v1/orders',
    async (route, parts, method) => {
      if (parts[0] === 'close-settings') {
        if (method === 'PUT') {
          const body = route.request().postDataJSON() as { tolerancePercent: number; autoFulfil: boolean };
          note(route, body);
          stub.closeSettings = { tolerancePercent: body.tolerancePercent, autoFulfil: body.autoFulfil, customised: true };
        }
        return route.fulfill(json(stub.closeSettings));
      }
      const order = stub.orders.find((o) => o.id === parts[0]);
      if (!order) {
        return route.fulfill(problem(404, 'not-found', 'No such order'));
      }
      if (parts.length === 1) {
        return route.fulfill(json(orderView(order), 200, { ETag: `"${order.version}"` }));
      }
      if (parts.length === 2 && method === 'GET') {
        return route.fulfill(json(order.lines));
      }
      if (parts.length === 2 && method === 'POST') {
        const body = route.request().postDataJSON() as Record<string, unknown>;
        note(route, body);
        if (order.status !== 'DRAFT') {
          return route.fulfill(problem(409, 'order-not-editable', `Order ${order.orderNo} can no longer be amended`));
        }
        const line = { ...lineOf(Number(body.lineNo), String(body.materialCode)), materialDescription: String(body.materialDescription),
          quantity: Number(body.quantity), uom: String(body.uom), deadWeightKg: Number(body.deadWeightKg),
          destinationTerminalId: String(body.destinationTerminalId) };
        order.lines.push(line);
        return route.fulfill(json({ id: line.id }, 201));
      }
      // POST /orders/{id}/lines/{lineId}/cancellation
      const body = route.request().postDataJSON() as { reason?: string } | null;
      note(route, body);
      if (versionOf(route.request().headers()['if-match'] ?? null) !== order.version) {
        return route.fulfill(problem(409, 'concurrent-modification', 'The order changed since it was read'));
      }
      if (order.status === 'DRAFT') {
        return route.fulfill(problem(409, 'order-not-validated', 'Remove the line of a draft instead'));
      }
      const line = order.lines.find((l) => l.id === parts[2]);
      if (!line) {
        return route.fulfill(problem(404, 'not-found', 'No such line'));
      }
      if (order.lines.filter((l) => !l.cancelled).length === 1) {
        return route.fulfill(problem(409, 'order-line-last', 'That is the only live line'));
      }
      line.cancelled = true;
      order.version += 1;
      return route.fulfill(json({ lineId: line.id, cancelledConsignmentIds: [], releasedLineIds: [] }));
    });

  await serve(/\/api\/v1\/eway-bills(\/expiring)?(\?.*)?$/, 'api/v1', (route) => route.fulfill(slice(stub.bills.map(billView))));
  await serve(/\/api\/v1\/consignments\/[^/]+\/eway-bills\/record$/, 'api/v1/consignments', (route) => {
    const body = route.request().postDataJSON() as { billNo: string; validUntil: string; vehicleNo?: string; distanceKm?: number };
    note(route, body);
    if (stub.bills.some((b) => b.billNo === body.billNo)) {
      return route.fulfill(problem(409, 'eway-bill-number-taken', 'That e-way bill number is already recorded'));
    }
    const bill: Bill = {
      id: uuid(), billNo: body.billNo, consignmentId: new URL(route.request().url()).pathname.split('/').filter(Boolean)[3],
      validUntil: body.validUntil, vehicleNo: body.vehicleNo, distanceKm: body.distanceKm,
    };
    stub.bills.unshift(bill);
    return route.fulfill(json({ id: bill.id }, 201));
  });

  await serve(/\/api\/v1\/telematics\/corridors(\/[0-9a-f-]{36})?(\?.*)?$/, 'api/v1/telematics/corridors', (route, parts, method) => {
    if (method === 'GET') {
      return route.fulfill(slice(stub.corridors.map(corridorView)));
    }
    if (method === 'DELETE') {
      note(route, undefined);
      stub.corridors = stub.corridors.filter((c) => c.id !== parts[0]);
      return route.fulfill(none());
    }
    const body = route.request().postDataJSON() as {
      originTerminalId: string; destinationTerminalId: string; name: string; points: number[][]; widthMetres?: number;
    };
    note(route, body);
    // A lane has one corridor: importing replaces it.
    stub.corridors = stub.corridors.filter((c) => c.originTerminalId !== body.originTerminalId
      || c.destinationTerminalId !== body.destinationTerminalId);
    const corridor: Corridor = {
      id: uuid(), name: body.name, originTerminalId: body.originTerminalId, originTerminalCode: 'ORG',
      destinationTerminalId: body.destinationTerminalId, destinationTerminalCode: 'DST', pointCount: body.points.length,
      widthMetres: body.widthMetres,
    };
    stub.corridors.push(corridor);
    return route.fulfill(json({ id: corridor.id }));
  });
  return stub;
}
