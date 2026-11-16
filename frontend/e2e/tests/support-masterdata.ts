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
 * Stubs for the master-data CRUD spec: the API answers for partners, vehicles, drivers and terminals from a
 * small in-memory model that behaves as the backend does where the spec cares (the default list is the
 * active records, a stale If-Match is a 409 concurrent-modification, an inactive vehicle or driver is
 * switched back on with a reason, a terminal with open work refuses to switch off). It records what each
 * console sent, so the same assertions hold for both.
 */

export const unitId = '44444444-5555-4666-8777-888888888888';

const cors = { 'access-control-allow-origin': '*', 'access-control-expose-headers': 'ETag' };
const preflight = { status: 204, headers: { ...cors, 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } };
const json = (body: unknown, status = 200, extra: Record<string, string> = {}) => ({
  status, headers: { ...cors, ...extra }, contentType: 'application/json', body: JSON.stringify(body),
});
const problem = (status: number, code: string, detail: string) => json({ title: 'Refused', status, code, detail }, status);

export const MASTER_PERMISSIONS = [
  'PARTNER_READ', 'PARTNER_CREATE', 'PARTNER_UPDATE', 'VEHICLE_READ', 'VEHICLE_CREATE', 'VEHICLE_UPDATE',
  'DRIVER_READ', 'DRIVER_CREATE', 'DRIVER_UPDATE', 'TERMINAL_READ', 'TERMINAL_CREATE', 'TERMINAL_UPDATE',
  'TERMINAL_DELETE', 'CUSTOM_FIELD_READ',
];

/** Signs in without a backend; used only when E2E_STUB_SESSION is set. */
export async function stubSession(page: Page): Promise<void> {
  await page.route('**/api/v1/exceptions/summary', (route) => handle(route, () => json({
    openP1: 0, openP2: 0, openP3: 0, unassigned: 0, settlementHolds: 0, openWithoutTrip: 0 })));
  await page.route('**/api/v1/custom-fields/**', (route) => handle(route, () => json({ items: [], hasMore: false })));
  await page.route('**/api/v1/auth/login', (route) => handle(route, () => json({
    accessToken: 'stub-token', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshToken: 'stub-refresh',
    refreshTokenExpiresAt: '2099-01-01T00:00:00Z', tokenType: 'Bearer', orgUnitId: unitId, permissions: MASTER_PERMISSIONS })));
}

async function handle(route: Route, answer: () => ReturnType<typeof json>): Promise<void> {
  if (route.request().method() === 'OPTIONS') {
    return route.fulfill(preflight);
  }
  return route.fulfill(answer());
}

type Rec = Record<string, unknown> & { id: string; status: string; version: number };
type Kind = 'partners' | 'vehicles' | 'drivers' | 'terminals';

export interface MasterStub {
  records: Record<Kind, Rec[]>;
  /** The query string of every list request, per kind. */
  listQueries: Record<Kind, string[]>;
  /** What each change sent: method, path, If-Match and body. */
  changes: Array<{ method: string; path: string; ifMatch: string | null; body: Record<string, unknown> }>;
  /** Moves a record's version on, as another person's change would. */
  touch(kind: Kind, id: string): void;
  /** Terminals the stub says open work still points at. */
  busyTerminals: Set<string>;
}

let sequence = 0;
const uuid = () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`;
const now = '2026-01-01T00:00:00Z';

const IDENTITY: Record<Kind, string> = { partners: 'code', vehicles: 'registrationNo', drivers: 'licenceNo', terminals: 'code' };
/** What each kind's default list leaves out. */
const OFF: Record<Kind, string[]> = {
  partners: ['SUSPENDED', 'BLACKLISTED'], vehicles: ['INACTIVE', 'RETIRED'], drivers: ['INACTIVE'], terminals: ['INACTIVE'],
};
const DEACTIVATED: Record<Kind, string> = { partners: 'SUSPENDED', vehicles: 'INACTIVE', drivers: 'INACTIVE', terminals: 'INACTIVE' };
const REACTIVATED: Record<Kind, string> = { partners: 'ACTIVE', vehicles: 'AVAILABLE', drivers: 'AVAILABLE', terminals: 'ACTIVE' };

export function seed(): Record<Kind, Rec[]> {
  const partner = (code: string, legalName: string, status: string): Rec => ({
    id: uuid(), code, legalName, partnerType: 'CUSTOMER', status, taxId: null, paymentTerms: null, billingAddress: null,
    creditLimit: null, onTimePct: null, claimsRatio: null, orgUnitId: unitId, expiredDocumentCount: 0, version: 0, createdAt: now,
  });
  const vehicle = (registrationNo: string, status: string): Rec => ({
    id: uuid(), registrationNo, category: 'TRUCK', vehicleType: 'TRUCK_20FT', axleConfig: '2-axle', status,
    grossWeightKg: 18000, tareWeightKg: 6000, payloadCapacityKg: 12000, maxVolumeM3: 30, hazmatCertified: false,
    reeferCapable: false, orgUnitId: unitId, expiredDocumentCount: 0, version: 0, createdAt: now,
  });
  const driver = (fullName: string, licenceNo: string, status: string): Rec => ({
    id: uuid(), fullName, phone: '9000000000', licenceNo, licenceClass: 'HMV', licenceExpiresOn: '2030-01-01',
    licenceExpired: false, hazmatEndorsed: false, status, hosMinutesToday: 0, licenceAuthority: 'RTO', orgUnitId: unitId,
    version: 0, createdAt: now,
  });
  const terminal = (code: string, name: string, status: string): Rec => ({
    id: uuid(), code, name, functionalCategory: 'WAREHOUSE', geofenceType: 'POINT_RADIUS', polygon: null,
    centreLat: 19.0, centreLon: 72.8, radiusM: 300, dockCount: 4, opensAt: '06:00:00', closesAt: '22:00:00',
    avgDwellMinutes: 45, status, orgUnitId: unitId, version: 0, createdAt: now,
  });
  return {
    partners: [partner('ACME', 'Acme Retail', 'ACTIVE'), partner('OLD-1', 'Old Customer', 'SUSPENDED')],
    vehicles: [vehicle('MH-01-AA-0001', 'AVAILABLE'), vehicle('MH-01-AA-0002', 'INACTIVE')],
    drivers: [driver('Asha Rao', 'DL-0001', 'AVAILABLE'), driver('Ravi Kumar', 'DL-0002', 'INACTIVE')],
    terminals: [terminal('WH-1', 'Main warehouse', 'ACTIVE'), terminal('WH-OLD', 'Closed depot', 'INACTIVE')],
  };
}

export async function stubMasterData(page: Page): Promise<MasterStub> {
  const stub: MasterStub = {
    records: seed(), busyTerminals: new Set(),
    listQueries: { partners: [], vehicles: [], drivers: [], terminals: [] }, changes: [],
    touch: (kind, id) => { stub.records[kind].find((r) => r.id === id)!.version += 1; },
  };

  await page.route(/\/api\/v1\/(partners|vehicles|drivers|terminals)(\/.*)?(\?.*)?$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const url = new URL(request.url());
    const [kind, ...rest] = url.pathname.split('/').filter(Boolean).slice(2) as [Kind, ...string[]];
    const method = request.method();
    const body = request.postData() ? (request.postDataJSON() as Record<string, unknown>) : {};
    const ifMatch = request.headers()['if-match'] ?? null;
    const rows = stub.records[kind];
    const send = (status: number, payload?: unknown) => route.fulfill(payload === undefined
      ? { status, headers: cors } : json(payload, status));

    if (rest.length === 0 && method === 'GET') {
      stub.listQueries[kind].push(url.search);
      const status = url.searchParams.get('status');
      const q = (url.searchParams.get('q') ?? '').toLowerCase();
      const items = rows.filter((r) => (status === 'ALL' || (status ? r.status === status : !OFF[kind].includes(r.status)))
        && (!q || JSON.stringify(Object.values(r)).toLowerCase().includes(q)));
      return route.fulfill(json({ items, hasMore: false }));
    }

    const created = (extra: Record<string, unknown>) => {
      const record = { id: uuid(), version: 0, orgUnitId: unitId, createdAt: now, ...extra } as Rec;
      rows.push(record);
      return record;
    };

    if (method === 'POST' && (rest.length === 0 || rest[0] === 'point-radius' || rest[0] === 'polygon')) {
      stub.changes.push({ method, path: url.pathname, ifMatch: ifMatch === null ? null : ifMatch.replace(/"/g, ''), body });
      const code = String(body[IDENTITY[kind]] ?? '');
      if (rows.some((r) => r[IDENTITY[kind]] === code)) {
        return route.fulfill(problem(409, `${kind.slice(0, -1)}-code-taken`, `${code} already exists`));
      }
      const record = kind === 'partners' ? created({ ...body, status: 'DRAFT' })
        : kind === 'vehicles' ? created({ ...body, status: 'AVAILABLE', payloadCapacityKg: Number(body.grossWeightKg) - Number(body.tareWeightKg) })
          : kind === 'drivers' ? created({ ...body, status: 'AVAILABLE', hosMinutesToday: 0, licenceExpired: false })
            : created({
              ...body, functionalCategory: body.category, status: 'ACTIVE',
              geofenceType: rest[0] === 'polygon' ? 'POLYGON' : 'POINT_RADIUS',
              centreLat: (body.centre as { lat: number } | undefined)?.lat, centreLon: (body.centre as { lon: number } | undefined)?.lon,
              radiusM: body.radiusMetres, polygon: rest[0] === 'polygon' ? JSON.stringify(body.ring) : null,
            });
      return route.fulfill(json({ id: record.id }, 201));
    }

    const record = rows.find((r) => r.id === rest[0]);
    if (!record) {
      return route.fulfill(problem(404, 'not-found', 'No such record'));
    }
    if (method === 'GET' && rest.length === 1) {
      return route.fulfill(json(record, 200, { etag: `"${record.version}"` }));
    }

    stub.changes.push({ method, path: url.pathname, ifMatch: ifMatch === null ? null : ifMatch.replace(/"/g, ''), body });
    const stale = () => {
      const sent = ifMatch ?? (body.version === undefined ? null : String(body.version));
      return sent !== null && Number(sent.replace(/[^0-9]/g, '')) !== record.version;
    };
    const conflict = () => route.fulfill(problem(409, 'concurrent-modification',
      'The record changed since it was read; read it again and retry.'));

    if (method === 'PUT' && rest.length === 1) {
      if (stale()) {
        return conflict();
      }
      Object.assign(record, body, kind === 'terminals' && body.centre
        ? { centreLat: (body.centre as { lat: number }).lat, centreLon: (body.centre as { lon: number }).lon, radiusM: body.radiusMetres } : {});
      if (kind === 'vehicles') {
        record.payloadCapacityKg = Number(record.grossWeightKg) - Number(record.tareWeightKg);
      }
      record.version += 1;
      return send(204);
    }

    const action = kind === 'partners' && rest[1] === 'transitions' ? 'transition' : rest[1];
    if (method === 'POST' && action) {
      if (stale()) {
        return conflict();
      }
      const reason = String(body.reason ?? '').trim();
      if (action === 'transition') {
        const target = String(body.target);
        if ((target === 'SUSPENDED' || target === 'BLACKLISTED' || (target === 'ACTIVE' && record.status === 'SUSPENDED')) && !reason) {
          return route.fulfill(problem(400, 'validation-failed', 'A reason is required'));
        }
        if (record.status === 'BLACKLISTED') {
          return route.fulfill(problem(409, 'partner-illegal-transition', 'Blacklisting is terminal'));
        }
        record.status = target;
        record.version += 1;
        return send(204);
      }
      if (!reason) {
        return route.fulfill(problem(400, 'validation-failed', 'A reason is required'));
      }
      if (action === 'deactivate') {
        if (kind === 'terminals' && stub.busyTerminals.has(record.id)) {
          return route.fulfill(problem(409, 'terminal-in-use', `Terminal ${record.code} cannot be deactivated: it is still used by open trips`));
        }
        record.status = DEACTIVATED[kind];
      } else if (action === 'reactivate') {
        record.status = REACTIVATED[kind];
      }
      record.version += 1;
      return send(204);
    }
    return send(404);
  });
  return stub;
}

/** The testids this spec relies on, by what they are. */
export const TESTIDS = {
  list: ['row', 'status-filter', 'new-record'],
  panel: ['record-panel', 'edit-record', 'deactivate-record', 'reactivate-record', 'blacklist-record'],
  form: ['record-form', 'save-record', 'cancel-form', 'form-error', 'conflict-notice'],
  confirm: ['confirm-panel', 'reason-input', 'reason-error', 'confirm-action', 'cancel-action', 'action-error'],
  fields: [
    'field-code', 'field-legalName', 'field-partnerType', 'field-taxId', 'field-paymentTerms', 'field-billingAddress',
    'field-registrationNo', 'field-category', 'field-vehicleType', 'field-axleConfig', 'field-grossWeightKg',
    'field-tareWeightKg', 'field-maxVolumeM3', 'field-ownerPartnerId', 'field-hazmatCertified', 'field-reeferCapable',
    'field-fullName', 'field-phone', 'field-licenceNo', 'field-licenceClass', 'field-licenceAuthority',
    'field-licenceExpiresOn', 'field-employerPartnerId', 'field-hazmatEndorsed',
    'field-name', 'field-functionalCategory', 'field-geofenceType', 'field-centreLat', 'field-centreLon',
    'field-radiusMetres', 'field-ring', 'field-dockCount', 'field-opensAt', 'field-closesAt', 'field-avgDwellMinutes',
  ],
  errors: ['error-code', 'error-legalName', 'error-registrationNo', 'error-fullName', 'error-name'],
};
