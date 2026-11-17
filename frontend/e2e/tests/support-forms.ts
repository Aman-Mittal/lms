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
 * Stubs for the forms and feedback spec: the API answers for the sign-in, the partner list, the typeahead behind the
 * pickers and the delivery lookup, and records what each console sent. A request the stub does not know is answered with an
 * empty page, except the sign-in and the session (`/api/v1/auth/` and `/api/v1/me`), which fall back to the routes
 * registered later or to the real backend, so the sign-in keeps working.
 */

export const unitId = '44444444-5555-4666-8777-888888888888';
export const consignmentId = '33333333-4444-4555-8666-777777777777';

const cors = { 'access-control-allow-origin': '*', 'access-control-expose-headers': 'ETag' };
const preflight = { status: 204, headers: { ...cors, 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } };
const json = (body: unknown, status = 200) => ({
  status, headers: cors, contentType: 'application/json', body: JSON.stringify(body),
});

export const FORM_PERMISSIONS = ['PARTNER_READ', 'PARTNER_CREATE', 'POD_READ', 'ORDER_READ'];

export interface FormsStub {
  /** Sign-in requests the console made. */
  logins: number;
  /** Partner registrations the console sent, and how long the stub holds its answer back. */
  registrations: Array<Record<string, unknown>>;
  registerDelayMs: number;
  /** The delivery URLs looked up, so a spec can check which consignment a picker handed over. */
  lookups: string[];
}

/** Signs in without a backend; used only when E2E_STUB_SESSION is set. */
export async function stubFormsSession(page: Page): Promise<void> {
  await page.route('**/api/v1/exceptions/summary', (route) => handle(route, () => json({
    openP1: 0, openP2: 0, openP3: 0, unassigned: 0, settlementHolds: 0, openWithoutTrip: 0 })));
  await page.route('**/api/v1/auth/login', (route) => handle(route, () => json({
    accessToken: 'stub-token', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshToken: 'stub-refresh',
    refreshTokenExpiresAt: '2099-01-01T00:00:00Z', tokenType: 'Bearer', orgUnitId: unitId, permissions: FORM_PERMISSIONS })));
}

async function handle(route: Route, answer: () => ReturnType<typeof json>): Promise<void> {
  if (route.request().method() === 'OPTIONS') {
    return route.fulfill(preflight);
  }
  return route.fulfill(answer());
}

export async function stubForms(page: Page): Promise<FormsStub> {
  const stub: FormsStub = { logins: 0, registrations: [], registerDelayMs: 0, lookups: [] };

  // Everything else the screens read on the way in is an empty page.
  await page.route('**/api/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (/^\/api\/v1\/(auth\/|me(\/|$))/.test(path)) {
      return route.fallback();
    }
    return handle(route, () => json({ items: [], hasMore: false }));
  });

  // The partner list and the customer typeahead both read /partners; the typeahead names a partnerType.
  await page.route(/\/api\/v1\/partners(\?.*)?$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    if (request.method() === 'POST') {
      stub.registrations.push(request.postDataJSON() as Record<string, unknown>);
      await new Promise((resolve) => setTimeout(resolve, stub.registerDelayMs));
      return route.fulfill(json({ title: 'Conflict', status: 409, code: 'concurrent-modification',
        detail: 'The register changed since it was read' }, 409));
    }
    const url = new URL(request.url());
    if (url.searchParams.get('partnerType') === 'CUSTOMER') {
      return route.fulfill(json({ items: [
        { id: unitId, code: 'ACME', legalName: 'Acme Traders', partnerType: 'CUSTOMER', status: 'ACTIVE', version: 0 },
      ], hasMore: false }));
    }
    return route.fulfill(json({ items: [], hasMore: false }));
  });

  // The typeahead behind the consignment picker, and the lookup it hands the chosen id to.
  await page.route(/\/api\/v1\/consignments(\?.*)?$/, (route) => handle(route, () => json({ items: [{
    id: consignmentId, trackingRef: 'TRK-0042', status: 'DRAFT', consigneeName: 'Acme Traders', destinationTerminalCode: 'DEL',
    totalDeadWeightKg: '100', materialClasses: [], containsHazmat: false, version: 0 }], hasMore: false })));
  await page.route(new RegExp(`/api/v1/deliveries/${consignmentId}`), (route) => {
    stub.lookups.push(route.request().url());
    return handle(route, () => json({ title: 'Not found', status: 404, code: 'not-found', detail: 'No proof' }, 404));
  });
  await page.route('**/api/v1/auth/login', (route) => {
    if (route.request().method() === 'POST') {
      stub.logins++;
    }
    return route.fallback();
  });
  return stub;
}
