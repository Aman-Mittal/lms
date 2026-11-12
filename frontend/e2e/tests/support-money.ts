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

import { expect, type APIRequestContext } from '@playwright/test';
import { API, type Session } from './support';

/*
 * Fixtures for the money desks: settings on the tenant's default profile, the approvals the specs
 * decide through the API, a freight bill approved for payment, and catalogue entries.
 */

function headers(session: Session): Record<string, string> {
  return { Authorization: `Bearer ${session.token}` };
}

/** Sets one setting on the tenant's default profile, making the profile if there is none; null removes it. */
export async function setOrgSetting(
  request: APIRequestContext, session: Session, key: string, value: number | null,
): Promise<void> {
  const found = await request.get(`${API}/api/v1/settings/profiles?scopeType=ORG_DEFAULT`, { headers: headers(session) });
  expect(found.ok(), `list profiles: ${found.status()}`).toBeTruthy();
  const existing = (await found.json()).items[0] as { id: string } | undefined;
  if (!existing) {
    if (value === null) {
      return;
    }
    const made = await request.post(`${API}/api/v1/settings/profiles`, {
      headers: headers(session), data: { scopeType: 'ORG_DEFAULT', values: { [key]: value } },
    });
    expect(made.ok(), `create default profile: ${made.status()} ${await made.text()}`).toBeTruthy();
    return;
  }
  const read = await request.get(`${API}/api/v1/settings/profiles/${existing.id}`, { headers: headers(session) });
  const patched = await request.patch(`${API}/api/v1/settings/profiles/${existing.id}`, {
    headers: { ...headers(session), 'If-Match': read.headers()['etag'] }, data: { values: { [key]: value } },
  });
  expect(patched.ok(), `set ${key}: ${patched.status()} ${await patched.text()}`).toBeTruthy();
}

/** The two approval policies this spec decides itself: the demo has one administrator, so self-approval is on. */
export async function allowSelfApproval(request: APIRequestContext, session: Session): Promise<void> {
  for (const type of ['MANUAL_CHARGE', 'VENDOR_ADVANCE']) {
    const response = await request.put(`${API}/api/v1/approvals/policies/${type}`, {
      headers: headers(session), data: { required: true, allowSelfApproval: true },
    });
    expect(response.ok(), `set policy ${type}: ${response.status()} ${await response.text()}`).toBeTruthy();
  }
}

export async function resetApprovalPolicies(request: APIRequestContext, session: Session): Promise<void> {
  for (const type of ['MANUAL_CHARGE', 'VENDOR_ADVANCE']) {
    await request.delete(`${API}/api/v1/approvals/policies/${type}`, { headers: headers(session) });
  }
}

/** Approves the open approval request of a subject, as the demo administrator. */
export async function approveSubject(
  request: APIRequestContext, session: Session, subjectType: string, subjectId: string,
): Promise<void> {
  const listed = await request.get(
    `${API}/api/v1/approvals?subjectType=${subjectType}&subjectId=${subjectId}&status=REQUESTED`, { headers: headers(session) });
  expect(listed.ok(), `list approvals: ${listed.status()} ${await listed.text()}`).toBeTruthy();
  const open = (await listed.json()).items[0] as { id: string; version: number } | undefined;
  expect(open, `an open ${subjectType} approval for ${subjectId}`).toBeTruthy();
  const decided = await request.post(`${API}/api/v1/approvals/${open!.id}/approve`, {
    headers: { ...headers(session), 'If-Match': `"${open!.version}"` }, data: { note: 'checked' },
  });
  expect(decided.ok(), `approve: ${decided.status()} ${await decided.text()}`).toBeTruthy();
}

/** A catalogue entry billable to the customer or payable to the vendor, per unit. */
export async function newChargeEntry(
  request: APIRequestContext, session: Session, code: string, rate: number,
): Promise<{ id: string; code: string; name: string }> {
  const name = `Spec charge ${code}`;
  const made = await request.post(`${API}/api/v1/charge-catalogue`, {
    headers: headers(session), data: { code, name, basis: 'PER_UNIT', defaultRate: rate, direction: 'BOTH' },
  });
  expect(made.ok(), `create charge: ${made.status()} ${await made.text()}`).toBeTruthy();
  return { id: (await made.json()).id as string, code, name };
}

/** The trip a freight bill pays for. */
export async function tripOfBill(request: APIRequestContext, session: Session, billId: string): Promise<string> {
  const bill = await request.get(`${API}/api/v1/freight-bills/${billId}`, { headers: headers(session) });
  expect(bill.ok(), `read bill: ${bill.status()}`).toBeTruthy();
  return (await bill.json()).tripId as string;
}

/** Matches a draft bill's claim to what was computed, which approves it for payment. */
export async function approveBill(
  request: APIRequestContext, session: Session, billId: string, claimed: number,
): Promise<void> {
  const matched = await request.post(`${API}/api/v1/freight-bills/${billId}/match`, {
    headers: headers(session), data: { claimedAmount: claimed },
  });
  expect(matched.ok(), `match bill: ${matched.status()} ${await matched.text()}`).toBeTruthy();
  expect((await matched.json()).status).toBe('APPROVED_FOR_PAYMENT');
}
