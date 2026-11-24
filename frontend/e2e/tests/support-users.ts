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
 * Stubs for the user and role administration spec: the API answers from a small in-memory model that keeps the
 * server's rules (a password needs twelve characters, letters and digits; a login id is used once; the signed-in
 * administrator cannot switch themselves off; the last administrator keeps the role; the Administrator role is fixed; a
 * held role is not deleted; edits need If-Match and a stale one is a 409), so the same assertions run against both
 * consoles whatever the demo tenant holds. The requests each console sends are recorded for the spec.
 */

export const unitId = '44444444-5555-4666-8777-888888888888';
export const selfId = '99999999-0000-4000-8000-000000000001';
export const depotUnitId = '44444444-5555-4666-8777-999999999999';

/** The active units the stub offers: the signed-in administrator's own and one below it. */
export const STUB_UNITS = [
  { id: unitId, parentId: '', slug: 'head-office', path: 'head-office', name: 'Head Office', unitType: 'COMPANY', level: 0,
    version: '0', active: true },
  { id: depotUnitId, parentId: unitId, slug: 'north-depot', path: 'head-office.north-depot', name: 'North Depot', unitType: 'DEPOT',
    level: 1, version: '0', active: true },
];

const cors = { 'access-control-allow-origin': '*', 'access-control-expose-headers': 'ETag' };
const preflight = { status: 204, headers: { ...cors, 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } };
const json = (body: unknown, status = 200, headers: Record<string, string> = {}) => ({
  status, headers: { ...cors, ...headers }, contentType: 'application/json', body: JSON.stringify(body) });
const problem = (status: number, code: string, detail: string) => json({ title: 'Refused', status, code, detail }, status);
const none = () => ({ status: 204, headers: cors });

/** What an administrator holds; a spec drops some to see what the screens no longer offer. */
export const USER_ADMIN_PERMISSIONS = ['USER_MANAGE', 'ROLE_MANAGE', 'ROLE_READ'];

/** Signs in without a backend; used only when E2E_STUB_SESSION is set. */
export async function stubUsersSession(page: Page, dropped: string[] = []): Promise<void> {
  const permissions = USER_ADMIN_PERMISSIONS.filter((p) => !dropped.includes(p));
  await page.route('**/api/v1/exceptions/summary', (route) => handle(route, () => json({
    openP1: 0, openP2: 0, openP3: 0, unassigned: 0, settlementHolds: 0, openWithoutTrip: 0 })));
  await page.route('**/api/v1/auth/login', (route) => handle(route, () => json({
    accessToken: 'stub-token', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshToken: 'stub-refresh',
    refreshTokenExpiresAt: '2099-01-01T00:00:00Z', tokenType: 'Bearer', userId: selfId, orgUnitId: unitId,
    permissions })));
}

async function handle(route: Route, answer: () => ReturnType<typeof json>): Promise<void> {
  if (route.request().method() === 'OPTIONS') {
    return route.fulfill(preflight);
  }
  return route.fulfill(answer());
}

export interface StubUser {
  id: string; email: string; fullName: string; status: string; orgName: string; version: number; roles: string[];
}

export interface StubRole {
  id: string; code: string; name: string; standard: boolean; version: number; permissions: string[];
}

export interface UsersStub {
  users: StubUser[];
  roles: StubRole[];
  /** What each change sent: its method, path, If-Match and body. */
  changes: Array<{ method: string; path: string; ifMatch: string | null; body: unknown }>;
}

export const PERMISSIONS = [
  { code: 'ORDER_READ', resource: 'ORDER', action: 'READ' },
  { code: 'ORDER_CREATE', resource: 'ORDER', action: 'CREATE' },
  { code: 'TRIP_READ', resource: 'TRIP', action: 'READ' },
  { code: 'TRIP_EXECUTE', resource: 'TRIP', action: 'EXECUTE' },
  { code: 'INVOICE_READ', resource: 'INVOICE', action: 'READ' },
];

let sequence = 0;
const uuid = () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`;

export async function stubUsers(page: Page): Promise<UsersStub> {
  const role = (code: string, name: string, permissions: string[], standard = true): StubRole =>
    ({ id: uuid(), code, name, standard, version: 0, permissions });
  const stub: UsersStub = {
    users: [], changes: [],
    roles: [
      role('ADMIN', 'Administrator', PERMISSIONS.map((p) => p.code)),
      role('PLANNER', 'Planner', ['ORDER_READ', 'ORDER_CREATE']),
      role('VIEWER', 'Viewer', ['ORDER_READ', 'TRIP_READ', 'INVOICE_READ']),
    ],
  };
  stub.users.push({ id: selfId, email: 'admin@stub.test', fullName: 'Stub Administrator', status: 'ACTIVE', orgName: 'Head Office',
    version: 0, roles: ['ADMIN'] });
  const note = (route: Route, body: unknown) => {
    const request = route.request();
    stub.changes.push({ method: request.method(), path: new URL(request.url()).pathname,
      ifMatch: request.headers()['if-match'] ?? null, body });
  };
  const roleView = (r: StubRole) => ({ ...r, userCount: stub.users.filter((u) => u.roles.includes(r.code)).length });
  const staleOrMissing = (version: number, ifMatch: string | undefined) => {
    if (!ifMatch) {
      return problem(400, 'bad-request', 'An If-Match header is required');
    }
    return Number(ifMatch.replaceAll('"', '')) === version ? null : problem(409, 'concurrent-modification', 'Changed');
  };
  const otherAdmins = (except: string) =>
    stub.users.filter((u) => u.id !== except && u.status === 'ACTIVE' && u.roles.includes('ADMIN')).length;

  await page.route(/\/api\/v1\/org-units(\?.*)?$/, (route) => handle(route, () => json({ items: STUB_UNITS, nextCursor: null })));

  await page.route(/\/api\/v1\/permissions(\?.*)?$/, (route) => handle(route, () => json(PERMISSIONS)));

  await page.route(/\/api\/v1\/roles(\/.*)?(\?.*)?$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const id = new URL(request.url()).pathname.split('/')[4];
    const method = request.method();
    if (!id && method === 'GET') {
      return route.fulfill(json(stub.roles.map(roleView)));
    }
    if (!id && method === 'POST') {
      const body = request.postDataJSON() as { code: string; name: string; permissions: string[] };
      note(route, body);
      const code = body.code.toUpperCase();
      if (stub.roles.some((r) => r.code === code)) {
        return route.fulfill(problem(409, 'role-code-taken', `Role code ${code} is already in use`));
      }
      const created = role(code, body.name, body.permissions, false);
      stub.roles.push(created);
      return route.fulfill(json({ id: created.id }, 201));
    }
    const found = stub.roles.find((r) => r.id === id);
    if (!found) {
      return route.fulfill(problem(404, 'not-found', 'No such role'));
    }
    if (method === 'GET') {
      return route.fulfill(json(roleView(found), 200, { etag: `"${found.version}"` }));
    }
    if (method === 'PUT') {
      const body = request.postDataJSON() as { name: string; permissions: string[] };
      note(route, body);
      const refused = staleOrMissing(found.version, request.headers()['if-match']);
      if (refused) {
        return route.fulfill(refused);
      }
      if (found.code === 'ADMIN') {
        return route.fulfill(problem(409, 'administrator-role-fixed', 'The Administrator role is fixed'));
      }
      Object.assign(found, { name: body.name, permissions: body.permissions, version: found.version + 1 });
      return route.fulfill(none());
    }
    if (method === 'DELETE') {
      note(route, null);
      if (found.code === 'ADMIN') {
        return route.fulfill(problem(409, 'administrator-role-fixed', 'The Administrator role is fixed'));
      }
      if (stub.users.some((u) => u.roles.includes(found.code))) {
        return route.fulfill(problem(409, 'role-in-use', `Role ${found.code} is held by a user`));
      }
      stub.roles.splice(stub.roles.indexOf(found), 1);
      return route.fulfill(none());
    }
    return route.fulfill(none());
  });

  await page.route(/\/api\/v1\/users(\/.*)?(\?.*)?$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const url = new URL(request.url());
    const parts = url.pathname.split('/').filter(Boolean).slice(3); // after api/v1/users
    const method = request.method();
    if (parts.length === 0 && method === 'GET') {
      const q = (url.searchParams.get('q') ?? '').toLowerCase();
      const status = url.searchParams.get('status');
      return route.fulfill(json(stub.users.filter((u) => (!q || `${u.fullName} ${u.email}`.toLowerCase().includes(q))
        && (!status || u.status === status))));
    }
    if (parts.length === 0 && method === 'POST') {
      const body = request.postDataJSON() as { email: string; password: string; fullName: string; roles?: string[];
        orgUnitId?: string };
      note(route, { ...body, password: '(hidden)' });
      if (body.password.length < 12 || !/[A-Za-z]/.test(body.password) || !/[0-9]/.test(body.password)) {
        return route.fulfill(problem(409, 'password-policy', 'The password must be at least 12 characters long'));
      }
      if (stub.users.some((u) => u.email === body.email.toLowerCase())) {
        return route.fulfill(problem(409, 'login-id-taken', `Login id ${body.email} is already in use`));
      }
      const created: StubUser = { id: uuid(), email: body.email.toLowerCase(), fullName: body.fullName, status: 'ACTIVE',
        orgName: STUB_UNITS.find((u) => u.id === body.orgUnitId)?.name ?? 'Head Office', version: 0, roles: body.roles ?? [] };
      stub.users.push(created);
      return route.fulfill(json({ id: created.id }, 201));
    }
    const user = stub.users.find((u) => u.id === parts[0]);
    if (!user) {
      return route.fulfill(problem(404, 'not-found', 'No such user'));
    }
    const ifMatch = request.headers()['if-match'];
    if (parts.length === 1 && method === 'GET') {
      return route.fulfill(json(user, 200, { etag: `"${user.version}"` }));
    }
    if (parts.length === 1 && method === 'PUT') {
      const body = request.postDataJSON() as { fullName: string; roles?: string[] };
      note(route, body);
      const refused = staleOrMissing(user.version, ifMatch);
      if (refused) {
        return route.fulfill(refused);
      }
      const roles = body.roles ?? [];
      if (user.roles.includes('ADMIN') && !roles.includes('ADMIN') && otherAdmins(user.id) === 0) {
        return route.fulfill(problem(409, 'last-administrator', 'You cannot remove the Administrator role from the last active Administrator'));
      }
      Object.assign(user, { fullName: body.fullName, roles, version: user.version + 1 });
      return route.fulfill(none());
    }
    if (parts[1] === 'deactivate' && method === 'POST') {
      note(route, request.postDataJSON());
      if (ifMatch && Number(ifMatch.replaceAll('"', '')) !== user.version) {
        return route.fulfill(problem(409, 'concurrent-modification', 'Changed'));
      }
      if (user.id === selfId) {
        return route.fulfill(problem(409, 'self-deactivation', 'You cannot deactivate your own account'));
      }
      if (user.roles.includes('ADMIN') && otherAdmins(user.id) === 0) {
        return route.fulfill(problem(409, 'last-administrator', 'You cannot deactivate the last active Administrator'));
      }
      Object.assign(user, { status: 'SUSPENDED', version: user.version + 1 });
      return route.fulfill(none());
    }
    if (parts[1] === 'reactivate' && method === 'POST') {
      note(route, request.postDataJSON());
      Object.assign(user, { status: 'ACTIVE', version: user.version + 1 });
      return route.fulfill(none());
    }
    if (parts[1] === 'password' && method === 'PUT') {
      const body = request.postDataJSON() as { password: string };
      note(route, { ...body, password: '(hidden)' });
      if (body.password.length < 12 || !/[A-Za-z]/.test(body.password) || !/[0-9]/.test(body.password)) {
        return route.fulfill(problem(409, 'password-policy', 'The password must be at least 12 characters long'));
      }
      user.version += 1;
      return route.fulfill(none());
    }
    return route.fulfill(none());
  });

  return stub;
}
