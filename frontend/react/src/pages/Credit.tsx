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

import { useEffect, useMemo, useState, type FormEvent } from 'react';
import {
  ApiProblem, CREDIT_CHANGE_STATUSES, type CreditChangeStatus, type CreditSubjectType, type Row,
} from '../gateway/lmsGateway';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { RowTable, type RowColumn } from './RowTable';

/** The problem a hard credit limit refuses an action with. */
export const CREDIT_LIMIT_EXCEEDED = 'credit-limit-exceeded';

/** A typed amount, or nothing when it was left blank. */
function amountOrAbsent(value: string): number | undefined {
  const trimmed = value.trim();
  return trimmed ? Number(trimmed) : undefined;
}

/**
 * A customer's credit position: its limits, what it owes or is committed to, and the
 * headroom left. Read again whenever `refreshKey` changes, so a desk can pass whatever it
 * re-reads after an action.
 */
export function CreditPosition({ partnerId, refreshKey }: { partnerId: string; refreshKey?: unknown }) {
  const { gateway } = useSession();
  // One key object per (partner, refresh) pair: a fresh array each render would read forever.
  const key = useMemo(() => ({ partnerId, refreshKey }), [partnerId, refreshKey]);
  const position = useLoad(
    () => (partnerId ? gateway.creditPosition(partnerId) : Promise.resolve(null)),
    key,
  );
  const p = position.data;
  return (
    <section className="credit" data-testid="credit-position" aria-label="Credit position">
      <h3>Credit position</h3>
      {position.error && <p className="error" role="alert" data-testid="credit-position-error">{position.error}</p>}
      {p && (
        <>
          <dl>
            <div><dt>Customer</dt><dd data-testid="credit-partner">{p.partnerCode} · {p.partnerStatus}</dd></div>
            <div><dt>State</dt><dd data-testid="credit-state">{p.limitState}</dd></div>
            <div><dt>Credit limit</dt><dd data-testid="credit-limit">{p.creditLimit || 'No limit'}</dd></div>
            <div><dt>Soft limit</dt><dd data-testid="credit-soft-limit">{p.softLimit || '—'}</dd></div>
            <div><dt>Open orders</dt><dd data-testid="credit-open-orders">{p.openOrders} ({p.openOrderCount})</dd></div>
            <div><dt>Not yet invoiced</dt><dd data-testid="credit-unbilled">{p.unbilledCharges}</dd></div>
            <div><dt>Unpaid invoices</dt><dd data-testid="credit-outstanding">{p.outstandingInvoices}</dd></div>
            <div><dt>Exposure</dt><dd data-testid="credit-exposure">{p.exposure} {p.currency}</dd></div>
            <div><dt>Headroom</dt><dd data-testid="credit-headroom">{p.headroom || '—'}</dd></div>
          </dl>
          {!p.trading && (
            <p className="error" data-testid="credit-not-trading">
              {p.partnerCode} is {p.partnerStatus}: its orders cannot be validated, amended or given new lines
              (<code>customer-not-trading</code>).
            </p>
          )}
          {p.limitState === 'SOFT_EXCEEDED' && (
            <p className="muted" data-testid="credit-soft-warning">
              Exposure is above the soft limit; the hard limit still has room.
            </p>
          )}
          {p.unpricedOpenOrderCount && p.unpricedOpenOrderCount !== '0' && (
            <p className="muted" data-testid="credit-unpriced">
              {p.unpricedOpenOrderCount} open orders are counted at nothing: no client contract prices them.
            </p>
          )}
          {p.pendingChangeId && (
            <p className="muted" data-testid="credit-change-pending">A credit limit change is waiting for approval.</p>
          )}
        </>
      )}
    </section>
  );
}

/**
 * What a hard credit limit refusal says in figures, and, for a caller holding
 * CREDIT_OVERRIDE, a one-time override with a reason. `onGranted` runs once the override is
 * on record, so the desk can try the refused action again.
 */
export function CreditOverride({ partnerId, subjectType, orderId, period, errors, onGranted }: {
  partnerId: string;
  subjectType: CreditSubjectType;
  orderId?: string;
  /** For a billing run: any `YYYY-MM-DD` day of the month. */
  period?: string;
  /** The refusal's `errors`, which name the exposure and the limit. */
  errors: Record<string, string>;
  onGranted: (id: string) => void;
}) {
  const { gateway, can } = useSession();
  const [reason, setReason] = useState('');
  const [upTo, setUpTo] = useState(errors.exposure ?? '');
  const [failure, setFailure] = useState<{ code: string; message: string } | null>(null);

  useEffect(() => { setUpTo(errors.exposure ?? ''); }, [errors]);

  async function grant(e: FormEvent) {
    e.preventDefault();
    setFailure(null);
    try {
      const id = await gateway.grantCreditOverride(partnerId, {
        subjectType, orderId: orderId || undefined, period: period || undefined, upTo: Number(upTo.trim()), reason,
      });
      setReason('');
      onGranted(id);
    } catch (err) {
      setFailure({ code: err instanceof ApiProblem ? err.code : '', message: (err as Error).message });
    }
  }

  return (
    <section className="credit" data-testid="credit-refusal">
      <p data-testid="credit-refusal-figures">
        Exposure {errors.exposure || '?'} against a credit limit of {errors.creditLimit || '?'}.
      </p>
      {can('CREDIT_OVERRIDE') ? (
        <form className="inline" data-testid="credit-override" onSubmit={grant}>
          <input aria-label="Override reason" placeholder="Why it may go through" value={reason}
            onChange={(e) => setReason(e.target.value)} required />
          <input aria-label="Override up to" placeholder="Exposure allowed" inputMode="decimal" value={upTo}
            onChange={(e) => setUpTo(e.target.value)} required />
          <button type="submit" data-testid="credit-override-submit">Override and retry</button>
          {failure && (
            <span className="error" role="alert" data-testid="credit-override-error">
              {failure.code && <><code>{failure.code}</code>{' '}</>}
              {failure.message}
            </span>
          )}
        </form>
      ) : (
        <p className="muted" data-testid="credit-override-unavailable">
          Only a holder of CREDIT_OVERRIDE can let this through.
        </p>
      )}
    </section>
  );
}

const CHANGE_COLUMNS: RowColumn[] = [
  { header: 'Partner', field: 'partnerCode' }, { header: 'Status', field: 'status' },
  { header: 'From', field: 'previousLimit' }, { header: 'To', field: 'requestedLimit' },
  { header: 'Soft limit', field: 'requestedSoftLimit' }, { header: 'Reason', field: 'reason' },
  { header: 'Asked by', field: 'requestedBy' }, { header: 'Asked at', field: 'requestedAt' },
  { header: 'Decided by', field: 'decidedBy' }, { header: 'Note', field: 'decisionNote' },
];

/**
 * Credit limits, maker and checker: one person asks for a customer's new limit, another
 * approves or rejects it, and only an approval changes the limit. Every request stays as
 * the customer's change log.
 */
export function CreditLimits() {
  const { gateway, can } = useSession();
  const [typed, setTyped] = useState('');
  const [partnerId, setPartnerId] = useState('');
  const [status, setStatus] = useState<CreditChangeStatus>('PENDING');
  const [reload, setReload] = useState(0);
  const [limit, setLimit] = useState('');
  const [softLimit, setSoftLimit] = useState('');
  const [reason, setReason] = useState('');
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [refusal, setRefusal] = useState<{ code: string; message: string } | null>(null);
  const [message, setMessage] = useState('');

  const queue = useLoad(() => gateway.creditLimitChanges(status), `${status}:${reload}`);
  const history = useLoad(
    () => (partnerId ? gateway.partnerCreditLimitChanges(partnerId) : Promise.resolve([] as Row[])),
    `${partnerId}:${reload}`,
  );

  async function attempt(outcome: string, action: () => Promise<unknown>) {
    setRefusal(null);
    setMessage('');
    try {
      await action();
      setMessage(outcome);
    } catch (e) {
      setRefusal({ code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message });
    }
    setReload((n) => n + 1);
  }

  function request(e: FormEvent) {
    e.preventDefault();
    void attempt('Change asked for; it waits for approval.', async () => {
      await gateway.requestCreditLimitChange(partnerId, {
        creditLimit: Number(limit.trim()), softLimit: amountOrAbsent(softLimit), reason,
      });
      setLimit('');
      setSoftLimit('');
      setReason('');
    });
  }

  function decide(id: string, approve: boolean) {
    const note = notes[id] ?? '';
    void attempt(approve ? 'Approved; the new limit applies.' : 'Rejected; the limit stays as it was.', () =>
      approve ? gateway.approveCreditLimitChange(id, note) : gateway.rejectCreditLimitChange(id, note));
  }

  const rows = queue.data ?? [];
  const log = history.data ?? [];
  const loadError = queue.error || history.error;

  return (
    <>
      <h1>Credit limits</h1>
      <p className="muted">
        A new credit limit applies only once someone other than the person who asked for it approves it.
      </p>
      {refusal && (
        <p className="error" role="alert" data-testid="credit-admin-error">
          {refusal.code && <><code data-testid="credit-admin-refusal">{refusal.code}</code>{' '}</>}
          {refusal.message}
        </p>
      )}
      {!refusal && loadError && <p className="error" role="alert" data-testid="credit-admin-error">{loadError}</p>}
      {message && <p className="ok" role="status" data-testid="credit-admin-message">{message}</p>}

      <form className="inline" data-testid="credit-partner-form"
        onSubmit={(e) => { e.preventDefault(); setPartnerId(typed.trim().toLowerCase()); }}>
        <EntityPicker kind="customer" placeholder="Customer" ariaLabel="Credit customer id" testId="credit-customer"
          required value={typed} onChange={setTyped} />
        <button type="submit">Open</button>
      </form>

      {partnerId && (
        <>
          <CreditPosition partnerId={partnerId} refreshKey={reload} />
          {can('CREDIT_LIMIT_REQUEST') && (
            <form className="inline" data-testid="credit-change-request" onSubmit={request}>
              <h2>Ask for a new limit</h2>
              <input aria-label="New credit limit" placeholder="Credit limit" inputMode="decimal" value={limit}
                onChange={(e) => setLimit(e.target.value)} required />
              <input aria-label="New soft limit" placeholder="Soft limit (optional)" inputMode="decimal" value={softLimit}
                onChange={(e) => setSoftLimit(e.target.value)} />
              <input aria-label="Change reason" placeholder="Why" value={reason}
                onChange={(e) => setReason(e.target.value)} required />
              <button type="submit" data-testid="credit-change-submit">Ask for approval</button>
            </form>
          )}
          <h2>Change log</h2>
          {log.length === 0
            ? <p className="muted" data-testid="credit-history-empty">No change has been asked for.</p>
            : <RowTable testId="credit-history" rows={log} columns={CHANGE_COLUMNS} />}
        </>
      )}

      <h2>Waiting for approval</h2>
      <label>
        Show{' '}
        <select aria-label="Change status" data-testid="credit-change-status" value={status}
          onChange={(e) => setStatus(e.target.value as CreditChangeStatus)}>
          {CREDIT_CHANGE_STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
      </label>
      {rows.length === 0 ? (
        <p className="muted" data-testid="credit-queue-empty">Nothing here.</p>
      ) : (
        <table data-testid="credit-queue">
          <thead>
            <tr><th>Partner</th><th>From</th><th>To</th><th>Soft limit</th><th>Reason</th><th>Asked by</th><th>Status</th><th /></tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.id} data-testid="credit-queue-row" data-id={c.id}>
                <td>{c.partnerCode}</td><td>{c.previousLimit || '—'}</td><td>{c.requestedLimit}</td>
                <td>{c.requestedSoftLimit || '—'}</td><td>{c.reason}</td><td>{c.requestedBy}</td><td>{c.status}</td>
                <td>
                  {c.status === 'PENDING' && can('CREDIT_LIMIT_APPROVE') && (
                    <>
                      <input aria-label={`Decision note for ${c.partnerCode}`} placeholder="Note" value={notes[c.id] ?? ''}
                        onChange={(e) => setNotes((n) => ({ ...n, [c.id]: e.target.value }))} />{' '}
                      <button type="button" data-testid="credit-approve" onClick={() => decide(c.id, true)}>Approve</button>{' '}
                      <button type="button" className="link" data-testid="credit-reject"
                        onClick={() => decide(c.id, false)}>Reject</button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
