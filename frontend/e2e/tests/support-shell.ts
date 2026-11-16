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

import type { Page } from '@playwright/test';
import { NAV_GROUPS } from '../../shared/nav';

const cors = { 'access-control-allow-origin': '*', 'access-control-expose-headers': 'ETag' };
const preflight = { status: 204, headers: { ...cors, 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } };
const json = (body: unknown) => ({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify(body) });

/** Every permission the sidebar gates on, taken from the shared definition so it cannot go stale. */
export const ALL_NAV_PERMISSIONS = [...new Set(NAV_GROUPS.flatMap((g) => g.sections.flatMap((s) => s.items.flatMap((i) => i.any ?? []))))];

/** Signs in without a backend, holding every right the sidebar knows, and answers every read with an empty page. */
export async function stubShellApi(page: Page, permissions: string[] = ALL_NAV_PERMISSIONS): Promise<void> {
  await page.route('**/api/v1/**', (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    if (url.pathname.endsWith('/auth/login')) {
      return route.fulfill(json({
        accessToken: 'stub-token', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshToken: 'stub-refresh',
        refreshTokenExpiresAt: '2099-01-01T00:00:00Z', tokenType: 'Bearer', orgUnitId: '44444444-5555-4666-8777-888888888888',
        permissions,
      }));
    }
    if (url.pathname.endsWith('/exceptions/summary')) {
      return route.fulfill(json({ openP1: 0, openP2: 0, openP3: 0, unassigned: 0, settlementHolds: 0, openWithoutTrip: 0 }));
    }
    return route.fulfill(json(request.method() === 'GET' ? { items: [], total: 0 } : {}));
  });
}
