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
 * Stubs for the enquiry desk and the order upload specs: the API answers from a small in-memory model, so
 * the same assertions run against both consoles whatever the demo tenant holds, and the requests each
 * console sends (the version in If-Match, the filter, the file) are recorded for the spec to check.
 */

export const customer = '11111111-2222-4333-8444-555555555555';
export const originId = '22222222-3333-4444-8555-666666666666';
export const destinationId = '33333333-4444-4555-8666-777777777777';
export const unitId = '44444444-5555-4666-8777-888888888888';

const cors = { 'access-control-allow-origin': '*', 'access-control-expose-headers': 'ETag' };
const preflight = { status: 204, headers: { ...cors, 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } };

const json = (body: unknown, status = 200) => ({ status, headers: cors, contentType: 'application/json', body: JSON.stringify(body) });
const problem = (status: number, code: string, detail: string) => json({ title: 'Refused', status, code, detail }, status);

/** The permissions a desk holder has; a spec drops some to see what the screen no longer offers. */
export const DESK_PERMISSIONS = ['ORDER_READ', 'ORDER_CREATE', 'ORDER_UPDATE', 'PARTNER_READ', 'TERMINAL_READ'];

/**
 * Signs in without a backend and answers the control tower, which is the first thing the console reads.
 * Used only when E2E_STUB_SESSION is set; against a real stack the demo login is the real one.
 */
export async function stubSession(page: Page, dropped: string[] = []): Promise<void> {
  const permissions = DESK_PERMISSIONS.filter((p) => !dropped.includes(p));
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

interface Quote { id: string; lineId: string; revision: number; amount: number; currency: string; validUntil: string; status: string; note?: string }
interface Line {
  id: string; lineNo: number; originTerminalId: string; originCode: string; destinationTerminalId: string; destinationCode: string;
  quantity: number; uom: string; weightKg?: number; status: string; wonQuantity?: number; lostReason?: string; currency: string;
  suggestedLaneCode?: string; suggestedContractCode?: string; suggestedRateBasis?: string; suggestedRate?: number; suggestedAmount?: number;
}
interface Enquiry {
  id: string; enquiryNo: string; status: string; orgUnitId: string; customerPartnerId: string; customerName: string; reference?: string;
  deadlineAt: string; cancelReason?: string; orderId?: string; orderNo?: string; version: number; createdAt: string; lines: Line[]; quotes: Quote[];
}

export interface EnquiryStub {
  enquiries: Enquiry[];
  /** The query string of every list request, in order. */
  listQueries: string[];
  /** What each change sent: its path and its If-Match. */
  changes: Array<{ method: string; path: string; ifMatch: string | null; body: unknown }>;
  /** How many orders conversion has made. */
  ordersMade: number;
  /** Moves an enquiry's version on, as another person's change would. */
  touch(id: string): void;
}

let sequence = 0;
const uuid = () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`;

function view(e: Enquiry, withLines: boolean) {
  const won = e.lines.filter((l) => l.status === 'WON' || l.status === 'PARTIAL').length;
  return {
    id: e.id, enquiryNo: e.enquiryNo, status: e.status, orgUnitId: e.orgUnitId, customerPartnerId: e.customerPartnerId,
    customerName: e.customerName, reference: e.reference, deadlineAt: e.deadlineAt, cancelReason: e.cancelReason,
    orderId: e.orderId, orderNo: e.orderNo, lineCount: e.lines.length, wonLineCount: won, version: e.version,
    createdAt: e.createdAt, lines: withLines ? e.lines.map((l) => ({ ...l, quotes: e.quotes.filter((q) => q.lineId === l.id) })) : [],
  };
}

/** The status the lines give an enquiry once none is open. */
function settle(e: Enquiry) {
  if (e.lines.some((l) => l.status === 'OPEN')) {
    return;
  }
  const won = e.lines.filter((l) => l.status === 'WON' || l.status === 'PARTIAL');
  e.status = won.length === 0 ? 'LOST' : won.length === e.lines.length && won.every((l) => l.status === 'WON') ? 'WON' : 'PARTIAL';
}

export async function stubEnquiries(page: Page): Promise<EnquiryStub> {
  const stub: EnquiryStub = {
    enquiries: [], listQueries: [], changes: [], ordersMade: 0,
    touch: (id) => { stub.enquiries.find((e) => e.id === id)!.version += 1; },
  };
  await page.route(/\/api\/v1\/enquiries(\/.*)?(\?.*)?$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const url = new URL(request.url());
    const parts = url.pathname.split('/').filter(Boolean).slice(3); // after api/v1/enquiries
    const method = request.method();
    const body = request.postData() ? (request.postDataJSON() as Record<string, unknown>) : {};
    if (parts.length === 0 && method === 'GET') {
      stub.listQueries.push(url.search);
      const status = url.searchParams.get('status');
      const q = (url.searchParams.get('q') ?? '').toLowerCase();
      const items = stub.enquiries.filter((e) => (!status || e.status === status)
        && (!q || e.enquiryNo.toLowerCase().includes(q) || (e.reference ?? '').toLowerCase().includes(q)));
      return route.fulfill(json({ items: items.map((e) => view(e, false)), hasMore: false }));
    }
    if (parts.length === 0 && method === 'POST') {
      stub.changes.push({ method, path: url.pathname, ifMatch: null, body });
      const lines = (body.lines as Array<Record<string, unknown>>).map((l, i): Line => {
        const matches = l.originTerminalId === originId && l.destinationTerminalId === destinationId;
        return {
          id: uuid(), lineNo: i + 1, originTerminalId: String(l.originTerminalId), originCode: 'MUM', destinationTerminalId: String(l.destinationTerminalId),
          destinationCode: 'DEL', quantity: Number(l.quantity), uom: String(l.uom), weightKg: l.weightKg as number | undefined, status: 'OPEN', currency: 'INR',
          ...(matches ? { suggestedLaneCode: 'MUM-DEL', suggestedContractCode: 'ACME-1', suggestedRateBasis: 'PER_KG', suggestedRate: 5,
            suggestedAmount: 5 * Number(l.weightKg ?? l.quantity) } : {}),
        };
      });
      const e: Enquiry = {
        id: uuid(), enquiryNo: `ENQ-2026-${String(stub.enquiries.length + 1).padStart(6, '0')}`, status: 'OPEN', orgUnitId: String(body.orgUnitId),
        customerPartnerId: String(body.customerPartnerId), customerName: 'Acme Retail', reference: body.reference as string | undefined,
        deadlineAt: '2099-01-04T10:00:00Z', version: 0, createdAt: new Date().toISOString(), lines, quotes: [],
      };
      stub.enquiries.unshift(e);
      return route.fulfill(json({ id: e.id }, 201));
    }
    const e = stub.enquiries.find((x) => x.id === parts[0]);
    if (!e) {
      return route.fulfill(problem(404, 'not-found', 'No such enquiry'));
    }
    if (parts.length === 1 && method === 'GET') {
      return route.fulfill({ ...json(view(e, true)), headers: { ...cors, etag: `"${e.version}"` } });
    }
    if (parts[1] === 'convert') {
      stub.changes.push({ method, path: url.pathname, ifMatch: request.headers()['if-match'] ?? null, body });
      if (e.orderId) {
        return route.fulfill(json({ orderId: e.orderId, alreadyConverted: true }));
      }
      if (e.status !== 'WON' && e.status !== 'PARTIAL') {
        return route.fulfill(problem(409, 'enquiry-not-decided', 'Not decided'));
      }
      e.orderId = uuid();
      e.orderNo = 'ORD-2026-000042';
      e.status = 'CONVERTED';
      e.version += 1;
      stub.ordersMade += 1;
      return route.fulfill(json({ orderId: e.orderId, alreadyConverted: false }));
    }
    const ifMatch = request.headers()['if-match'] ?? null;
    stub.changes.push({ method, path: url.pathname, ifMatch, body });
    if (ifMatch === null) {
      return route.fulfill(problem(400, 'malformed-request', 'If-Match is required'));
    }
    if (Number(ifMatch.replace(/"/g, '')) !== e.version) {
      return route.fulfill(problem(409, 'concurrent-modification', 'The enquiry changed since it was read'));
    }
    const line = e.lines.find((l) => l.id === parts[2]);
    const quotes = line ? e.quotes.filter((q) => q.lineId === line.id) : [];
    const issued = quotes.find((q) => q.status === 'ISSUED');
    let made = '';
    if (parts[1] === 'cancel') {
      if (!String(body.reason ?? '').trim()) {
        return route.fulfill(problem(400, 'validation-failed', 'A reason is required'));
      }
      e.lines.forEach((l) => { if (l.status === 'OPEN') { l.status = 'CANCELLED'; } });
      e.status = 'CANCELLED';
      e.cancelReason = String(body.reason);
    } else if (parts[1] === 'rate-discovery') {
      // nothing moves but the version
    } else if (line && parts[3] === 'quote' && parts[4] === 'revocation') {
      if (!issued) {
        return route.fulfill(problem(409, 'enquiry-quote-missing', 'No quote to revoke'));
      }
      issued.status = 'REVOKED';
    } else if (line && parts[3] === 'quote') {
      if (method === 'POST' && issued) {
        return route.fulfill(problem(409, 'enquiry-quote-exists', 'The line already holds a quote'));
      }
      if (method === 'PUT' && !issued) {
        return route.fulfill(problem(409, 'enquiry-quote-missing', 'No quote to revise'));
      }
      if (issued) {
        issued.status = 'SUPERSEDED';
      }
      made = uuid();
      e.quotes.push({ id: made, lineId: line.id, revision: quotes.length + 1, amount: Number(body.amount), currency: 'INR',
        validUntil: '2099-01-08T10:00:00Z', status: 'ISSUED', note: body.note as string | undefined });
    } else if (line && parts[3] === 'outcome') {
      if (body.outcome === 'LOST' && !body.reason) {
        return route.fulfill(problem(409, 'enquiry-lost-reason-required', 'A line that is lost says why'));
      }
      if (body.outcome === 'PARTIAL' && !body.wonQuantity) {
        return route.fulfill(problem(409, 'enquiry-partial-quantity-invalid', 'A partly won line states a quantity'));
      }
      line.status = String(body.outcome);
      line.wonQuantity = body.outcome === 'WON' ? line.quantity : body.outcome === 'PARTIAL' ? Number(body.wonQuantity) : undefined;
      line.lostReason = body.outcome === 'LOST' ? String(body.reason) : undefined;
      settle(e);
    } else {
      return route.fulfill(problem(404, 'not-found', 'No such route'));
    }
    e.version += 1;
    return route.fulfill(made ? json({ id: made }, method === 'POST' ? 201 : 200) : { status: 204, headers: cors });
  });
  return stub;
}

export interface UploadStub {
  /** The multipart body of each upload, as text, for the spec to read the fields and the file from. */
  uploads: string[];
  /** The answer for the next upload. */
  next: { status: number; body: unknown };
}

export async function stubUpload(page: Page): Promise<UploadStub> {
  const stub: UploadStub = {
    uploads: [],
    next: {
      status: 200,
      body: {
        created: 1, duplicates: 0, failed: 1,
        rows: [
          { row: 2, orderRef: 'A1', status: 'CREATED', orderId: '55555555-6666-4777-8888-999999999999', reasons: [] },
          { row: 3, orderRef: 'A1', status: 'CREATED', orderId: '55555555-6666-4777-8888-999999999999', reasons: [] },
          { row: 4, orderRef: 'B1', status: 'FAILED', reasons: ['destination terminal "NOWHERE" is not a terminal code'] },
        ],
      },
    },
  };
  await page.route(/\/api\/v1\/order-intake\/files$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    stub.uploads.push(request.postDataBuffer()?.toString('utf8') ?? '');
    return route.fulfill(json(stub.next.body, stub.next.status));
  });
  return stub;
}
