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

import { createHmac } from 'node:crypto';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { apiSession, openScreen, signIn, tag, withoutPermissions, type Session } from './support';

/*
 * Webhooks against the real backend, delivered for real: each test starts a
 * small HTTP receiver on this machine, subscribes it, and checks what the
 * backend's own delivery timer sends. The CI backend allows plain http to a
 * private address for exactly this (see .github/workflows/e2e.yml); a backend
 * with production address rules refuses the subscription instead.
 */

const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

interface Received {
  headers: Record<string, string>;
  body: string;
}

/** An HTTP receiver that answers 204 and remembers every request, body as raw text. */
async function receiver(): Promise<{ url: string; got: Received[]; close: () => Promise<void> }> {
  const got: Received[] = [];
  const server: Server = createServer((req, res) => {
    let body = '';
    req.setEncoding('utf8');
    req.on('data', (chunk: string) => { body += chunk; });
    req.on('end', () => {
      got.push({ headers: req.headers as Record<string, string>, body });
      res.statusCode = 204;
      res.end();
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;
  return {
    url: `http://127.0.0.1:${port}/hook`,
    got,
    close: () => new Promise<void>((resolve) => server.close(() => resolve())),
  };
}

/** True when the request carries a valid signature for this secret. */
function signedWith(r: Received, secret: string): boolean {
  const expected = createHmac('sha256', secret).update(`${r.headers['x-lms-timestamp']}.${r.body}`).digest('hex');
  return r.headers['x-lms-signature'] === `v1=${expected}`;
}

async function subscribeByApi(
  request: APIRequestContext, session: Session, name: string, url: string,
): Promise<{ id: string; secret: string }> {
  const response = await request.post(`${API}/api/v1/webhooks/subscriptions`, {
    headers: { Authorization: `Bearer ${session.token}` },
    data: { name, url, eventTypes: ['TRIP_DISPATCHED'] },
  });
  expect(response.status(), await response.text()).toBe(201);
  return response.json();
}

/**
 * Deactivates every subscription this run made, so the delivery timer stops
 * trying a receiver that is gone. The demo tenant is shared by every spec.
 */
async function deactivate(request: APIRequestContext, salt: string): Promise<void> {
  const session = await apiSession(request);
  const headers = { Authorization: `Bearer ${session.token}` };
  const listed = await request.get(`${API}/api/v1/webhooks/subscriptions?limit=200`, { headers });
  for (const w of (await listed.json()).items as Array<{ id: string; name: string; url: string; eventTypes: string[]; active: boolean }>) {
    if (w.name.includes(salt) && w.active) {
      await request.put(`${API}/api/v1/webhooks/subscriptions/${w.id}`, {
        headers, data: { name: w.name, url: w.url, eventTypes: w.eventTypes, active: false },
      });
    }
  }
}

async function openWebhooks(page: Page): Promise<void> {
  await openScreen(page, 'nav-webhooks', 'Webhooks');
}

async function pick(page: Page, name: string): Promise<void> {
  await page.getByTestId('webhooks-row').filter({ hasText: name }).getByRole('button').click();
  await expect(page.getByTestId('webhook-detail')).toBeVisible();
}

/** Clicks Refresh until a delivery row shows the status. The delivery timer runs every few seconds. */
async function deliveryShows(page: Page, status: string): Promise<void> {
  await expect.poll(async () => {
    await page.getByTestId('refresh-deliveries').click();
    return page.getByTestId('deliveries-row').filter({ hasText: status }).count();
  }, { timeout: 45_000, intervals: [2_000] }).toBeGreaterThan(0);
}

test.describe('webhooks', () => {
  test('a subscription shows its secret once, and a test ping arrives signed with it', async ({ page, request }, info) => {
    const salt = tag(info);
    const hook = await receiver();
    try {
      await signIn(page);
      await openWebhooks(page);

      const form = page.getByTestId('subscribe-form');
      await form.getByTestId('webhook-name').fill(`Receiver ${salt}`);
      await form.getByTestId('webhook-url').fill(hook.url);
      await form.getByTestId('type-TRIP_DISPATCHED').check();
      await form.getByTestId('subscribe').click();

      await expect(page.getByTestId('webhook-secret')).toBeVisible();
      const secret = (await page.getByTestId('secret-value').textContent())?.trim() ?? '';
      expect(secret.length).toBeGreaterThan(20);
      await expect(page.getByTestId('webhooks-row').filter({ hasText: `Receiver ${salt}` })).toContainText('Active');

      // Once stored, the secret is gone from the page and nothing on the screen shows it again.
      await page.getByTestId('secret-done').click();
      await expect(page.getByTestId('webhook-secret')).toHaveCount(0);
      await expect(page.getByText(secret)).toHaveCount(0);

      await page.getByTestId('ping').click();
      await expect(page.getByTestId('webhook-message')).toHaveText('Test ping queued.');
      await expect.poll(() => hook.got.length, { timeout: 45_000 }).toBeGreaterThan(0);
      const ping = hook.got[0];
      expect(ping.headers['x-lms-event']).toBe('PING');
      expect(signedWith(ping, secret), 'the signature verifies with the secret shown').toBe(true);
      expect(ping.body).not.toContain('tenant');

      await deliveryShows(page, 'DELIVERED');
      await expect(page.getByTestId('deliveries-row').filter({ hasText: 'PING' })).toContainText('204');
    } finally {
      await deactivate(request, salt);
      await hook.close();
    }
  });

  test('a rotated secret signs from then on, and a redelivery keeps its id', async ({ page, request }, info) => {
    const salt = tag(info);
    const hook = await receiver();
    try {
      const session = await apiSession(request);
      const issued = await subscribeByApi(request, session, `Rotate ${salt}`, hook.url);
      await signIn(page);
      await openWebhooks(page);
      await pick(page, `Rotate ${salt}`);

      await page.getByTestId('rotate-secret').click();
      await expect(page.getByTestId('webhook-secret')).toBeVisible();
      const rotated = (await page.getByTestId('secret-value').textContent())?.trim() ?? '';
      expect(rotated).not.toBe(issued.secret);
      await page.getByTestId('secret-done').click();

      await page.getByTestId('ping').click();
      await expect.poll(() => hook.got.length, { timeout: 45_000 }).toBe(1);
      expect(signedWith(hook.got[0], rotated), 'signed with the new secret').toBe(true);
      expect(signedWith(hook.got[0], issued.secret), 'not with the old one').toBe(false);
      await deliveryShows(page, 'DELIVERED');

      await page.getByTestId('deliveries-row').first().getByRole('button').click();
      await page.getByTestId('redeliver').click();
      await expect.poll(() => hook.got.length, { timeout: 45_000 }).toBe(2);
      expect(hook.got[1].headers['x-lms-delivery']).toBe(hook.got[0].headers['x-lms-delivery']);
      expect(signedWith(hook.got[1], rotated)).toBe(true);
    } finally {
      await deactivate(request, salt);
      await hook.close();
    }
  });

  test('a subscription is edited and switched off, and a bad address is refused with its reason', async ({ page, request }, info) => {
    const salt = tag(info);
    try {
      const session = await apiSession(request);
      await subscribeByApi(request, session, `Edit ${salt}`, 'http://127.0.0.1:9/hook');
      await signIn(page);
      await openWebhooks(page);
      await pick(page, `Edit ${salt}`);

      await page.getByTestId('edit-name').fill(`Edited ${salt}`);
      await page.getByTestId('webhook-detail').getByTestId('type-CLAIM_RAISED').check();
      await page.getByTestId('edit-active').uncheck();
      await page.getByTestId('save-webhook').click();
      await expect(page.getByTestId('webhook-message')).toHaveText('Saved.');
      const row = page.getByTestId('webhooks-row').filter({ hasText: `Edited ${salt}` });
      await expect(row).toContainText('Inactive');
      await expect(row).toContainText('CLAIM_RAISED');

      await page.getByRole('button', { name: 'Back to subscribing' }).click();
      const form = page.getByTestId('subscribe-form');
      await form.getByTestId('webhook-name').fill(`Bad ${salt}`);
      await form.getByTestId('webhook-url').fill('not an address');
      await form.getByTestId('type-TRIP_DISPATCHED').check();
      await form.getByTestId('subscribe').click();
      await expect(page.getByRole('alert')).toBeVisible();
      await expect(page.getByTestId('webhook-secret')).toHaveCount(0);
    } finally {
      await deactivate(request, salt);
    }
  });

  test('someone who may only read sees subscriptions and deliveries but no controls', async ({ page, request }, info) => {
    const salt = tag(info);
    try {
      const session = await apiSession(request);
      await subscribeByApi(request, session, `Read ${salt}`, 'http://127.0.0.1:9/hook');
      await withoutPermissions(page, ['WEBHOOK_MANAGE']);
      await signIn(page);
      await openWebhooks(page);
      await expect(page.getByTestId('subscribe-form')).toHaveCount(0);
      await pick(page, `Read ${salt}`);
      await expect(page.getByTestId('webhook-deliveries')).toBeVisible();
      await expect(page.getByTestId('ping')).toHaveCount(0);
      await expect(page.getByTestId('rotate-secret')).toHaveCount(0);
      await expect(page.getByTestId('revise-form')).toHaveCount(0);
    } finally {
      await deactivate(request, salt);
    }
  });

  test('the screen is offered only to someone who may read webhooks', async ({ page }) => {
    await withoutPermissions(page, ['WEBHOOK_READ', 'WEBHOOK_MANAGE']);
    await signIn(page);
    await expect(page.getByTestId('nav-webhooks')).toHaveCount(0);
  });
});
