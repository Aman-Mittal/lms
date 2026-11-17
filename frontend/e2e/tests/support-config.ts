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
 * Stubs for the configuration CRUD spec: the API answers for allocation rules, organisational units, the charge
 * catalogue, webhooks, custom fields, warehouse bins and SKUs, routing-guide vendors, vendor invoices and access
 * policies from a small in-memory model that behaves as the backend does where the spec cares (the default list is the
 * active records, a stale If-Match is a 409 concurrent-modification, a unit with active children, a bin with stock and a
 * vendor with an open offer refuse to switch off, an invoice with a payment is not voidable). It records what each
 * console sent, so the same assertions hold for both.
 */

export const unitId = '44444444-5555-4666-8777-888888888888';
export const warehouseId = '55555555-6666-4777-8888-999999999999';
export const guideId = '66666666-7777-4888-8999-aaaaaaaaaaaa';

const cors = { 'access-control-allow-origin': '*', 'access-control-expose-headers': 'ETag' };
const preflight = { status: 204, headers: { ...cors, 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } };
const json = (body: unknown, status = 200, extra: Record<string, string> = {}) => ({
  status, headers: { ...cors, ...extra }, contentType: 'application/json', body: JSON.stringify(body),
});
const problem = (status: number, code: string, detail: string) => json({ title: 'Refused', status, code, detail }, status);

export const CONFIG_PERMISSIONS = [
  'ALLOCATION_RULE_READ', 'ALLOCATION_RULE_MANAGE', 'ORG_UNIT_MANAGE', 'CHARGE_CATALOGUE_MANAGE', 'WEBHOOK_READ',
  'WEBHOOK_MANAGE', 'CUSTOM_FIELD_READ', 'CUSTOM_FIELD_MANAGE', 'WAREHOUSE_READ', 'WAREHOUSE_SETUP', 'ROUTING_GUIDE_MANAGE',
  'LOAD_ALLOCATE', 'PAYABLES_MANAGE', 'ACCESS_POLICY_MANAGE', 'TARIFF_READ', 'TARIFF_MANAGE', 'INVOICE_READ',
];

/** Signs in without a backend; used only when E2E_STUB_SESSION is set. */
export async function stubConfigSession(page: Page): Promise<void> {
  await page.route('**/api/v1/exceptions/summary', (route) => handle(route, () => json({
    openP1: 0, openP2: 0, openP3: 0, unassigned: 0, settlementHolds: 0, openWithoutTrip: 0 })));
  await page.route('**/api/v1/auth/login', (route) => handle(route, () => json({
    accessToken: 'stub-token', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshToken: 'stub-refresh',
    refreshTokenExpiresAt: '2099-01-01T00:00:00Z', tokenType: 'Bearer', orgUnitId: unitId, permissions: CONFIG_PERMISSIONS })));
}

async function handle(route: Route, answer: () => ReturnType<typeof json>): Promise<void> {
  if (route.request().method() === 'OPTIONS') {
    return route.fulfill(preflight);
  }
  return route.fulfill(answer());
}

export type Rec = Record<string, unknown> & { id: string; version: number };
export type Kind = 'allocationRules' | 'orgUnits' | 'chargeCatalogue' | 'webhooks' | 'customFields' | 'bins' | 'skus'
  | 'guideVendors' | 'vendorInvoices' | 'accessPolicies';

export interface ConfigStub {
  records: Record<Kind, Rec[]>;
  /** The query string of every list request, per kind. */
  listQueries: Record<Kind, string[]>;
  /** What each change sent: method, path, If-Match and body. */
  changes: Array<{ method: string; path: string; ifMatch: string | null; body: Record<string, unknown> }>;
  /** Moves a record's version on, as another person's change would. */
  touch(kind: Kind, id: string): void;
  /** The ids a switch-off is refused for, with the problem code the backend would give. */
  refuse: Map<string, { code: string; detail: string }>;
}

let sequence = 0;
const uuid = () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`;
const now = '2026-01-01T00:00:00Z';

/** Where each kind lives, and what its switch endpoints are called. */
const ROUTES: Array<{ kind: Kind; pattern: RegExp }> = [
  { kind: 'allocationRules', pattern: /^\/api\/v1\/allocation-rules(\/[^/]+)?(\/[a-z]+)?$/ },
  { kind: 'orgUnits', pattern: /^\/api\/v1\/org-units(\/[^/]+)?(\/[a-z]+)?$/ },
  { kind: 'chargeCatalogue', pattern: /^\/api\/v1\/charge-catalogue(\/[^/]+)?(\/[a-z]+)?$/ },
  { kind: 'webhooks', pattern: /^\/api\/v1\/webhooks\/subscriptions(\/[^/]+)?(\/[a-z]+)?$/ },
  { kind: 'customFields', pattern: /^\/api\/v1\/custom-fields\/definitions(\/[^/]+)?(\/[a-z]+)?$/ },
  { kind: 'skus', pattern: /^\/api\/v1\/skus(\/[^/]+)?(\/[a-z]+)?$/ },
  { kind: 'vendorInvoices', pattern: /^\/api\/v1\/vendor-invoices(\/[^/]+)?(\/[a-z]+)?$/ },
  { kind: 'accessPolicies', pattern: /^\/api\/v1\/access-policies(\/[^/]+)?(\/[a-z]+)?$/ },
];

export function seed(): Record<Kind, Rec[]> {
  const base = { version: 0, createdAt: now, updatedAt: now };
  const vendor = { vendorPartnerId: uuid(), vendorName: 'Swift Carriers' };
  return {
    allocationRules: [
      { id: uuid(), name: 'Prefer Swift', priority: 10, active: true, action: 'PREFER', vendors: [vendor], ...base },
      { id: uuid(), name: 'Old seasonal rule', priority: 20, active: false, action: 'EXCLUDE', vendors: [vendor], ...base },
    ],
    orgUnits: [
      { id: unitId, slug: 'acme', path: '/acme/', name: 'Acme HQ', unitType: 'HQ', level: 0, active: true, version: 0 },
      { id: uuid(), parentId: unitId, slug: 'north', path: '/acme/north/', name: 'North zone', unitType: 'ZONE', level: 3, active: true, version: 0 },
      { id: uuid(), parentId: unitId, slug: 'gurgaon', path: '/acme/gurgaon/', name: 'Gurgaon branch', unitType: 'BRANCH', level: 4, active: true, version: 0 },
      { id: uuid(), parentId: unitId, slug: 'closed', path: '/acme/closed/', name: 'Closed hub', unitType: 'HUB', level: 5, active: false, version: 0 },
    ],
    chargeCatalogue: [
      { id: uuid(), code: 'LOADING', name: 'Loading', basis: 'FLAT', defaultRate: 500, currency: 'INR', direction: 'BOTH', active: true, ...base },
      { id: uuid(), code: 'TOLL', name: 'Toll', basis: 'FLAT', defaultRate: 250, currency: 'INR', direction: 'PAYABLE', active: false, ...base },
    ],
    webhooks: [
      { id: uuid(), name: 'ERP feed', url: 'https://example.invalid/erp', eventTypes: ['TRIP_COMPLETED'], active: true, consecutiveFailures: 0, ...base },
      { id: uuid(), name: 'Old feed', url: 'https://example.invalid/old', eventTypes: ['CLAIM_RAISED'], active: false, consecutiveFailures: 0, ...base },
    ],
    customFields: [
      { id: uuid(), entityType: 'PARTNER', key: 'credit_note', label: 'Credit note', fieldType: 'TEXT', required: false, options: [], displayOrder: 0, active: true, version: 0 },
      { id: uuid(), entityType: 'PARTNER', key: 'old_tier', label: 'Old tier', fieldType: 'TEXT', required: false, options: [], displayOrder: 1, active: false, version: 0 },
    ],
    bins: [
      { id: uuid(), warehouseId, code: 'A-01', capacityUnits: 50, occupiedUnits: 10, active: true, version: 0 },
      { id: uuid(), warehouseId, code: 'A-02', capacityUnits: 50, occupiedUnits: 0, active: true, version: 0 },
      { id: uuid(), warehouseId, code: 'B-OLD', capacityUnits: 20, occupiedUnits: 0, active: false, version: 0 },
    ],
    skus: [
      { id: uuid(), code: 'WIDGET', description: 'Widget', uom: 'EA', active: true, version: 0 },
      { id: uuid(), code: 'GADGET', description: 'Gadget', uom: 'EA', active: false, version: 0 },
    ],
    guideVendors: [
      { id: uuid(), rankNo: 1, vendorPartnerId: uuid(), vendorName: 'Swift Carriers', responseSlaMinutes: 60, agreedRate: 20000, active: true, version: 0 },
      { id: uuid(), rankNo: 2, vendorPartnerId: uuid(), vendorName: 'Steady Haulage', responseSlaMinutes: 60, agreedRate: 21000, active: true, version: 0 },
      { id: uuid(), rankNo: 3, vendorPartnerId: uuid(), vendorName: 'Lapsed Lines', responseSlaMinutes: 60, agreedRate: 22000, active: false, version: 0 },
    ],
    vendorInvoices: [
      invoice('VI-OPEN', 'OPEN', 0), invoice('VI-PART', 'PARTIALLY_PAID', 4000),
    ],
    accessPolicies: [
      { id: uuid(), code: 'no-sunday-approvals', action: 'INVOICE_APPROVE', effect: 'DENY', description: 'No Sunday approvals',
        condition: { attr: 'env.dayOfWeek', op: 'eq', value: 'SUNDAY' }, enabled: true, ...base },
    ],
  };
}

function invoice(invoiceNo: string, status: string, paid: number): Rec {
  return {
    id: uuid(), invoiceNo, vendorInvoiceNo: `V-${invoiceNo}`, vendorName: 'Swift Carriers', vendorPartnerId: uuid(),
    invoiceDate: '2026-01-01', dueDate: '2026-03-01', paymentTermsDays: 30, amount: 10000, taxAmount: 0, totalAmount: 10000,
    currency: 'INR', approvedAmount: 10000, varianceAmount: 0, advancesDeducted: 0, netPayable: 10000, paidAmount: paid,
    outstanding: 10000 - paid, status, overdue: false, daysOverdue: 0, billCount: 1, version: 0, createdAt: now,
  };
}

/** What each kind's default list leaves out: the records that are off. */
const isOff = (kind: Kind, r: Rec) => (kind === 'vendorInvoices' || kind === 'accessPolicies' ? false : r.active === false);

export async function stubConfig(page: Page): Promise<ConfigStub> {
  const stub: ConfigStub = {
    records: seed(),
    listQueries: {
      allocationRules: [], orgUnits: [], chargeCatalogue: [], webhooks: [], customFields: [], bins: [], skus: [],
      guideVendors: [], vendorInvoices: [], accessPolicies: [],
    },
    changes: [],
    touch: (kind, id) => { stub.records[kind].find((r) => r.id === id)!.version += 1; },
    refuse: new Map(),
  };

  // Everything else the screens read on the way in is an empty page, except the sign-in and the session: those go
  // to the real backend (or to stubConfigSession's routes, registered later and so matched first), or the console
  // signs in with no permissions and hides the screens these specs open.
  await page.route('**/api/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (/^\/api\/v1\/(auth\/|me(\/|$))/.test(path)) {
      return route.fallback();
    }
    return handle(route, () => json({ items: [], hasMore: false }));
  });

  const entries = () => stub.records.guideVendors;

  for (const { kind, pattern } of ROUTES) {
    await page.route(new RegExp(pattern.source.replace('^', '').replace('$', '(\\?.*)?$')), async (route) => {
      const request = route.request();
      if (request.method() === 'OPTIONS') {
        return route.fulfill(preflight);
      }
      const url = new URL(request.url());
      const match = pattern.exec(url.pathname);
      if (!match) {
        return route.fallback();
      }
      const id = match[1]?.slice(1);
      const action = match[2]?.slice(1);
      const method = request.method();
      const body = request.postData() ? (request.postDataJSON() as Record<string, unknown>) : {};
      const ifMatch = request.headers()['if-match'] ?? null;
      const rows = stub.records[kind];

      if (!id && method === 'GET') {
        stub.listQueries[kind].push(url.search);
        const status = url.searchParams.get('status');
        const active = url.searchParams.get('active');
        const items = rows.filter((r) => status === 'ALL' ? true
          : status === 'INACTIVE' ? isOff(kind, r)
            : active === 'false' ? isOff(kind, r) : !isOff(kind, r));
        return route.fulfill(json({ items, hasMore: false }));
      }
      const record = rows.find((r) => r.id === id);
      if (!record) {
        return route.fulfill(problem(404, 'not-found', 'No such record'));
      }
      if (method === 'GET' && !action) {
        return route.fulfill(json(record, 200, { etag: `"${record.version}"` }));
      }
      if (method === 'GET' && action === 'bills') {
        return route.fulfill(json([]));
      }
      if (method === 'GET') {
        return route.fulfill(json({ items: [], hasMore: false }));
      }
      stub.changes.push({ method, path: url.pathname, ifMatch: ifMatch === null ? null : ifMatch.replace(/"/g, ''), body });
      const stale = ifMatch !== null && Number(ifMatch.replace(/[^0-9]/g, '')) !== record.version;
      const conflict = () => route.fulfill(problem(409, 'concurrent-modification',
        'The resource was changed by someone else at the same time; re-read it and try again.'));

      if (method === 'PUT') {
        if (stale || ifMatch === null) {
          return stale ? conflict() : route.fulfill(problem(400, 'invalid-argument', 'An If-Match header is required'));
        }
        Object.assign(record, body);
        record.version += 1;
        return route.fulfill({ status: 204, headers: cors });
      }
      if (method === 'POST' && (action === 'deactivate' || action === 'reactivate' || action === 'void')) {
        if (stale) {
          return conflict();
        }
        if (!String(body.reason ?? '').trim()) {
          return route.fulfill(problem(400, 'validation-failed', 'A reason is required'));
        }
        const refused = stub.refuse.get(record.id);
        if (refused && action === 'deactivate') {
          return route.fulfill(problem(409, refused.code, refused.detail));
        }
        if (action === 'void') {
          if (record.status !== 'OPEN') {
            return route.fulfill(problem(409, 'vendor-invoice-not-voidable', `Invoice ${record.invoiceNo} has a payment against it`));
          }
          record.status = 'VOID';
          record.voidReason = String(body.reason);
        } else {
          record.active = action === 'reactivate';
        }
        record.version += 1;
        return route.fulfill({ status: 204, headers: cors });
      }
      return route.fulfill(problem(404, 'not-found', 'No such route'));
    });
  }

  // Bins live under their warehouse; a vendor entry under its guide.
  await page.route(/\/api\/v1\/warehouses\/[^/]+\/bins(\/[^/]+\/(deactivate|reactivate))?(\?.*)?$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const url = new URL(request.url());
    const parts = url.pathname.split('/').filter(Boolean);
    return changeNested(route, url, 'bins', parts[5], parts[6], stub);
  });
  await page.route(/\/api\/v1\/routing-guides\/[^/]+(\/vendors\/[^/]+\/(deactivate|reactivate))?(\?.*)?$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const url = new URL(request.url());
    const parts = url.pathname.split('/').filter(Boolean);
    if (parts.length === 4 && request.method() === 'GET') {
      stub.listQueries.guideVendors.push(url.search);
      const status = url.searchParams.get('status');
      const shown = entries().filter((e) => status === 'ALL' || (status === 'INACTIVE' ? e.active === false : e.active !== false));
      return route.fulfill(json({
        id: guideId, originTerminalCode: 'PNQ-DC', destinationTerminalCode: 'BOM-HUB', vehicleType: 'RIGID',
        strategy: 'CONTRACTUAL', active: true, entries: shown }));
    }
    return changeNested(route, url, 'guideVendors', parts[5], parts[6], stub);
  });
  return stub;
}

async function changeNested(route: Route, url: URL, kind: Kind, id: string | undefined, action: string | undefined,
  stub: ConfigStub): Promise<void> {
  const request = route.request();
  const method = request.method();
  const rows = stub.records[kind];
  if (method === 'GET' && !id) {
    stub.listQueries[kind].push(url.search);
    const status = url.searchParams.get('status');
    return route.fulfill(json({
      items: rows.filter((r) => status === 'ALL' || (status === 'INACTIVE' ? r.active === false : r.active !== false)),
      hasMore: false }));
  }
  const ifMatch = request.headers()['if-match'] ?? null;
  const body = request.postData() ? (request.postDataJSON() as Record<string, unknown>) : {};
  stub.changes.push({ method, path: url.pathname, ifMatch: ifMatch === null ? null : ifMatch.replace(/"/g, ''), body });
  const record = rows.find((r) => r.id === id);
  if (!record) {
    return route.fulfill(problem(404, 'not-found', 'No such record'));
  }
  if (ifMatch !== null && Number(ifMatch.replace(/[^0-9]/g, '')) !== record.version) {
    return route.fulfill(problem(409, 'concurrent-modification', 'The resource was changed by someone else at the same time.'));
  }
  if (!String(body.reason ?? '').trim()) {
    return route.fulfill(problem(400, 'validation-failed', 'A reason is required'));
  }
  const refused = stub.refuse.get(record.id);
  if (refused && action === 'deactivate') {
    return route.fulfill(problem(409, refused.code, refused.detail));
  }
  record.active = action === 'reactivate';
  record.version += 1;
  return route.fulfill({ status: 204, headers: cors });
}

/** The testids this spec relies on, by what they are. */
export const TESTIDS = {
  shared: ['status-filter', 'deactivate-record', 'reactivate-record', 'confirm-panel', 'reason-input', 'reason-error',
    'confirm-action', 'cancel-action', 'action-error', 'conflict-notice', 'void-record'],
  lists: ['rule-row', 'rule-status', 'org-unit-row', 'cc-row', 'webhook-detail', 'bin-row', 'sku-row',
    'bin-status-filter', 'sku-status-filter', 'guide-vendor', 'guide-panel', 'inv-row', 'inv-detail', 'detail-status',
    'detail-void-reason', 'save-webhook', 'policy-detail', 'save-policy'],
};
