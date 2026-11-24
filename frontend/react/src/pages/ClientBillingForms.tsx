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

import { useEffect, useState, type FormEvent } from 'react';
import { ApiProblem } from '../gateway/lmsGateway';
import {
  HAND_RECORDED_SERVICES, type HandRecordedService, type MarginPage, type ServiceUsageDraft, type StorageMonthView,
} from '../gateway/clientBillingFormTypes';
import type { AccountRow } from '../gateway/userAdminTypes';
import { useSession } from '../session';
import { ErrorBanner } from './ErrorBanner';
import { Money } from './Figures';

interface Refusal { message: string; code: string }
const NONE: Refusal = { message: '', code: '' };
const refusalOf = (e: unknown): Refusal => ({ message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' });

/** Today as a `YYYY-MM-DD` day, in UTC like the API. */
function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/** The month before this one, as `YYYY-MM`: the latest a month can have ended. */
function lastMonth(): string {
  const d = new Date();
  d.setUTCDate(1);
  d.setUTCMonth(d.getUTCMonth() - 1);
  return d.toISOString().slice(0, 7);
}

const BLANK_USAGE: ServiceUsageDraft = {
  serviceType: 'HANDLING_IN', serviceCode: '', quantity: '', occurredOn: '', reference: '', sourceKey: '',
};

/**
 * The client desk's three client-scoped forms: letting a user into the client's portal, recording a service used by
 * hand, and computing a month's storage. Each one is shown only to someone who may do it.
 */
export function ClientBillingForms({ clientId, onChanged }: { clientId: string; onChanged: () => void }) {
  const { can } = useSession();
  return (
    <>
      {can('CLIENT_CONTRACT_MANAGE') && <PortalAccess clientId={clientId} />}
      {can('CLIENT_BILLING_RUN') && <ServiceUsage clientId={clientId} onRecorded={onChanged} />}
      <StorageMonth clientId={clientId} onComputed={onChanged} />
    </>
  );
}

function PortalAccess({ clientId }: { clientId: string }) {
  const { gateway } = useSession();
  const [userId, setUserId] = useState('');
  const [done, setDone] = useState('');
  const [error, setError] = useState(NONE);
  // The active users to pick from; null while loading or when the list could not be read, which falls back to an id.
  const [users, setUsers] = useState<AccountRow[] | null>(null);

  useEffect(() => {
    let live = true;
    gateway.accounts('', 'ACTIVE').then((found) => { if (live) { setUsers(found); } }, () => { if (live) { setUsers(null); } });
    return () => { live = false; };
  }, [gateway]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setDone('');
    setError(NONE);
    try {
      const granted = userId.trim();
      const who = users?.find((u) => u.id === granted);
      await gateway.grantPortalAccess(clientId, granted);
      setUserId('');
      setDone(who ? who.fullName : granted);
    } catch (e) {
      setError(refusalOf(e));
    }
  }

  return (
    <>
      <h3>Portal access</h3>
      <form className="inline" data-testid="cb-portal-form" onSubmit={(ev) => void submit(ev)}>
        {users ? (
          <select aria-label="Portal user" data-testid="cb-portal-user" required value={userId}
            onChange={(e) => setUserId(e.target.value)}>
            <option value="">Choose a user</option>
            {users.map((u) => <option key={u.id} value={u.id}>{u.fullName} ({u.email})</option>)}
          </select>
        ) : (
          <input placeholder="User id" aria-label="Portal user id" data-testid="cb-portal-user" required
            pattern="[0-9a-fA-F\-]{36}" title="The user's id, as shown on the users screen" value={userId}
            onChange={(e) => setUserId(e.target.value)} />
        )}
        <button type="submit" data-testid="cb-portal-submit">Grant portal access</button>
      </form>
      {done && <p className="ok" role="status" data-testid="cb-portal-done">Portal access granted to {done}.</p>}
      {error.message && <ErrorBanner message={error.message} code={error.code} testId="cb-portal-error" codeTestId="cb-portal-refusal" />}
    </>
  );
}

function ServiceUsage({ clientId, onRecorded }: { clientId: string; onRecorded: () => void }) {
  const { gateway } = useSession();
  const [draft, setDraft] = useState<ServiceUsageDraft>({ ...BLANK_USAGE, occurredOn: today() });
  const [done, setDone] = useState('');
  const [error, setError] = useState(NONE);
  const set = <K extends keyof ServiceUsageDraft>(key: K, value: ServiceUsageDraft[K]) => setDraft((d) => ({ ...d, [key]: value }));

  async function submit(event: FormEvent) {
    event.preventDefault();
    setDone('');
    setError(NONE);
    try {
      const key = draft.sourceKey.trim();
      await gateway.recordServiceUsage(clientId, draft);
      setDraft({ ...BLANK_USAGE, occurredOn: today() });
      setDone(key);
      onRecorded();
    } catch (e) {
      setError(refusalOf(e));
    }
  }

  return (
    <>
      <h3>Service usage</h3>
      <form className="inline" data-testid="cb-usage-form" onSubmit={(ev) => void submit(ev)}>
        <select aria-label="Service used" data-testid="cb-usage-type" value={draft.serviceType}
          onChange={(e) => set('serviceType', e.target.value as HandRecordedService)}>
          {HAND_RECORDED_SERVICES.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        {draft.serviceType === 'VALUE_ADDED' && (
          <input placeholder="Service code" aria-label="Usage service code" data-testid="cb-usage-code" required
            value={draft.serviceCode} onChange={(e) => set('serviceCode', e.target.value)} />
        )}
        <input placeholder="Quantity used" aria-label="Usage quantity" inputMode="decimal" data-testid="cb-usage-quantity"
          required value={draft.quantity} onChange={(e) => set('quantity', e.target.value)} />
        <input type="date" aria-label="Usage day" data-testid="cb-usage-day" required value={draft.occurredOn}
          onChange={(e) => set('occurredOn', e.target.value)} />
        <input placeholder="Reference (optional)" aria-label="Usage reference" data-testid="cb-usage-reference"
          value={draft.reference} onChange={(e) => set('reference', e.target.value)} />
        <input placeholder="Source key (your own id for this usage)" aria-label="Usage source key"
          data-testid="cb-usage-key" required value={draft.sourceKey} onChange={(e) => set('sourceKey', e.target.value)} />
        <button type="submit" data-testid="cb-usage-submit">Record service usage</button>
      </form>
      {done && <p className="ok" role="status" data-testid="cb-usage-done">Usage {done} recorded.</p>}
      {error.message && <ErrorBanner message={error.message} code={error.code} testId="cb-usage-error" codeTestId="cb-usage-refusal" />}
    </>
  );
}

function StorageMonth({ clientId, onComputed }: { clientId: string; onComputed: () => void }) {
  const { gateway, can } = useSession();
  const [month, setMonth] = useState(lastMonth());
  const [view, setView] = useState<StorageMonthView | null>(null);
  const [done, setDone] = useState('');
  const [error, setError] = useState(NONE);

  async function run(compute: boolean) {
    setDone('');
    setError(NONE);
    try {
      const period = `${month}-01`;
      setView(await (compute ? gateway.computeClientStorage(clientId, period) : gateway.clientStorage(clientId, period)));
      if (compute) {
        setDone(month);
        onComputed();
      }
    } catch (e) {
      setError(refusalOf(e));
    }
  }

  return (
    <>
      <h3>Storage by month</h3>
      <form className="inline" data-testid="cb-storage-form" onSubmit={(ev) => { ev.preventDefault(); void run(false); }}>
        <input type="month" aria-label="Storage month" data-testid="cb-storage-month" value={month} required
          onChange={(e) => setMonth(e.target.value)} />
        <button type="submit" data-testid="cb-storage-view">View storage</button>
        {can('CLIENT_BILLING_RUN') && (
          <button type="button" data-testid="cb-storage-compute" onClick={() => void run(true)}>Compute storage</button>
        )}
      </form>
      {done && <p className="ok" role="status" data-testid="cb-storage-done">Storage for {done} computed.</p>}
      {error.message && <ErrorBanner message={error.message} code={error.code} testId="cb-storage-error" codeTestId="cb-storage-refusal" />}
      {view && (
        <section data-testid="cb-storage-result" aria-label="Storage for the month">
          {view.computed ? (
            <dl>
              <div><dt>Unit-days</dt><dd data-testid="cb-storage-unit-days">{view.unitDays}</dd></div>
              <div><dt>Pallet-days</dt><dd data-testid="cb-storage-pallet-days">{view.palletDays}</dd></div>
            </dl>
          ) : (
            <p className="muted" data-testid="cb-storage-pending">This month has not been computed yet.</p>
          )}
          {view.days.length > 0 && (
            <div className="table-wrap"><table>
              <thead><tr><th>Day</th><th className="num">Units held</th><th className="num">Units / pallet</th><th className="num">Pallets</th><th>Charged</th></tr></thead>
              <tbody>
                {view.days.map((d) => (
                  <tr key={d.heldOn} data-testid="cb-storage-day">
                    <td className="nowrap">{d.heldOn}</td><td className="num">{d.unitsHeld}</td>
                    <td className="num">{d.unitsPerPallet}</td><td className="num">{d.pallets}</td><td>{d.charged ? 'Yes' : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table></div>
          )}
        </section>
      )}
    </>
  );
}

/** The margin of every client invoice of a billed month: what was billed, what the carriers cost, what is left. */
export function MarginReport() {
  const { gateway, can } = useSession();
  const [month, setMonth] = useState(lastMonth());
  const [page, setPage] = useState<MarginPage | null>(null);
  const [error, setError] = useState(NONE);

  if (!can('CLIENT_MARGIN_READ')) {
    return null;
  }

  async function show(more: boolean) {
    setError(NONE);
    try {
      const next = await gateway.clientMargins(`${month}-01`, more ? page?.nextCursor : undefined);
      setPage(more && page ? { rows: [...page.rows, ...next.rows], nextCursor: next.nextCursor } : next);
    } catch (e) {
      setError(refusalOf(e));
    }
  }

  return (
    <section data-testid="cb-margins" aria-label="Client margins">
      <h2>Margins</h2>
      <form className="inline" data-testid="cb-margin-form" onSubmit={(ev) => { ev.preventDefault(); void show(false); }}>
        <input type="month" aria-label="Margin month" data-testid="cb-margin-month" value={month} required
          onChange={(e) => setMonth(e.target.value)} />
        <button type="submit" data-testid="cb-margin-show">Show margins</button>
      </form>
      {error.message && <ErrorBanner message={error.message} code={error.code} testId="cb-margin-error" codeTestId="cb-margin-refusal" />}
      {page && page.rows.length === 0 && <p className="muted" data-testid="cb-margin-empty">No invoice was issued in this month.</p>}
      {page && page.rows.length > 0 && (
        <div className="table-wrap"><table>
          <thead>
            <tr>
              <th>Client</th><th>Invoice</th><th className="num">Billed</th><th className="num">Transport</th>
              <th className="num">Warehouse</th><th className="num">Credited</th><th className="num">Carrier cost</th>
              <th className="num">Margin</th><th className="num">Margin %</th><th className="num">Movements</th>
              <th className="num">Unpriced</th>
            </tr>
          </thead>
          <tbody>
            {page.rows.map((r, i) => (
              <tr key={`${r.invoiceNo}-${i}`} data-testid="cb-margin-row">
                <td>{r.clientCode} · {r.clientName}</td><td>{r.invoiceNo}</td>
                <td className="num"><Money value={r.receivable} currency={r.currency} /></td>
                <td className="num"><Money value={r.transportReceivable} currency={r.currency} /></td>
                <td className="num"><Money value={r.warehouseReceivable} currency={r.currency} /></td>
                <td className="num"><Money value={r.credited} currency={r.currency} /></td>
                <td className="num"><Money value={r.carrierPayable} currency={r.currency} /></td>
                <td className="num"><Money data-testid="cb-margin-amount" value={r.margin} currency={r.currency} /></td>
                <td className="num">{r.marginPct}</td><td className="num">{r.movements}</td>
                <td className="num">{r.unpricedMovements}</td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}
      {page?.nextCursor && <button type="button" data-testid="cb-margin-more" onClick={() => void show(true)}>Load more margins</button>}
    </section>
  );
}
