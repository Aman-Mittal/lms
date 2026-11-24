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
import type { ConsignmentCard } from '../gateway/lmsGateway';
import type { BulkConsignmentOutcome } from '../gateway/consignmentBulkTypes';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { StatusBadge } from './StatusBadge';
import { ErrorBanner } from './ErrorBanner';
import { useConfirm } from './ConfirmDialog';
import { DISMISS_LABEL } from '../../../shared/confirm';
import {
  BULK_CONSIGNMENT_PERMISSION, MAX_BULK_CONSIGNMENTS, PARTICULAR_FIELDS, PICK_LIMIT_MESSAGE, bulkChanges, bulkSummary,
  pickUpTo, type ParticularKey,
} from '../../../shared/consignment-bulk';

interface Picked {
  id: string;
  ref: string;
  /** The version last read for it, which an amendment sends back. */
  version: string;
}

interface ResultRow extends BulkConsignmentOutcome {
  ref: string;
}

const STATUSES = ['PLANNED', 'ASSIGNED_TO_LOAD', 'IN_TRANSIT', 'ARRIVED_AT_HUB', 'ASSIGNED_TO_DELIVERY', 'OUT_FOR_DELIVERY',
  'DELIVERED', 'CANCELLED', 'SPLIT'];

/**
 * The consignment register: every consignment by reference, a page at a time, with the means to pick several and amend or cancel
 * them together. The server judges each one on its own, so the answer is a table saying which went through and which was refused.
 */
export function ConsignmentRegister() {
  const { gateway, can } = useSession();
  const confirm = useConfirm();
  const allowed = can(BULK_CONSIGNMENT_PERMISSION);
  const [status, setStatus] = useState('');
  const [typed, setTyped] = useState('');
  const [q, setQ] = useState('');
  // The cursor each page was read with; the first has none.
  const [cursors, setCursors] = useState<string[]>(['']);
  const [reload, setReload] = useState(0);
  const [picked, setPicked] = useState<Picked[]>([]);
  const [limitHit, setLimitHit] = useState(false);
  const [amending, setAmending] = useState(false);
  const [values, setValues] = useState<Partial<Record<ParticularKey, string>>>({});
  const [cleared, setCleared] = useState<Partial<Record<ParticularKey, boolean>>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [results, setResults] = useState<ResultRow[] | null>(null);
  const [summary, setSummary] = useState('');

  const page = cursors.length - 1;
  const list = useLoad(() => gateway.consignmentList(status, q, cursors[page] || undefined),
    `${status}|${q}|${cursors.join(',')}|${reload}`);
  const rows: ConsignmentCard[] = list.data?.rows ?? [];
  const nextCursor = list.data?.nextCursor ?? '';

  // Keep each picked consignment's version as fresh as the page that shows it.
  useEffect(() => {
    if (!list.data) { return; }
    setPicked((current) => current.map((p) => {
      const row = list.data?.rows.find((r) => r.id === p.id);
      return row && row.version !== p.version ? { ...p, version: row.version } : p;
    }));
  }, [list.data]);

  const isPicked = (id: string) => picked.some((p) => p.id === id);
  const allOnPage = rows.length > 0 && rows.every((r) => isPicked(r.id));

  function pick(rowsToPick: ConsignmentCard[]) {
    const { picked: ids, truncated } = pickUpTo(picked.map((p) => p.id), rowsToPick.map((r) => r.id));
    const known = [...picked, ...rowsToPick.map((r) => ({ id: r.id, ref: r.trackingRef, version: r.version }))];
    setPicked(ids.map((id) => known.find((k) => k.id === id) as Picked));
    setLimitHit(truncated);
  }

  function toggle(row: ConsignmentCard) {
    if (isPicked(row.id)) {
      setPicked((current) => current.filter((p) => p.id !== row.id));
      setLimitHit(false);
    } else {
      pick([row]);
    }
  }

  function togglePage() {
    if (allOnPage) {
      setPicked((current) => current.filter((p) => !rows.some((r) => r.id === p.id)));
      setLimitHit(false);
    } else {
      pick(rows);
    }
  }

  function search(event: FormEvent) {
    event.preventDefault();
    setQ(typed.trim());
    setCursors(['']);
  }

  async function run(done: 'amended' | 'cancelled', call: () => Promise<BulkConsignmentOutcome[]>) {
    setError('');
    setBusy(true);
    const refs = new Map(picked.map((p) => [p.id, p.ref]));
    try {
      const outcomes = await call();
      setResults(outcomes.map((o) => ({ ...o, ref: refs.get(o.consignmentId) ?? o.consignmentId })));
      setSummary(bulkSummary(outcomes, done));
      const succeeded = new Set(outcomes.filter((o) => o.ok).map((o) => o.consignmentId));
      setPicked((current) => current.filter((p) => !succeeded.has(p.id)));
      setReload((n) => n + 1);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  function askCancel() {
    const ids = picked.map((p) => p.id);
    confirm.ask({
      wording: {
        title: `Cancel ${ids.length} picked consignments?`, confirmLabel: 'Cancel picked', dismissLabel: DISMISS_LABEL,
        body: 'Each is taken off its load first; one that cannot be cancelled is refused and the rest go through.',
      },
      reason: { label: 'Reason for cancelling', testId: 'cons-bulk-cancel-reason', placeholder: 'Reason' },
      run: (reason) => run('cancelled', () => gateway.bulkCancelConsignments(ids, reason)),
    });
  }

  const changes = bulkChanges(values, cleared);

  async function apply(event: FormEvent) {
    event.preventDefault();
    const items = picked.map((p) => ({ consignmentId: p.id, version: p.version, changes }));
    setAmending(false);
    await run('amended', () => gateway.bulkAmendConsignments(items));
    setValues({});
    setCleared({});
  }

  return (
    <section data-testid="consignment-register">
      <h2>Consignment register</h2>
      <form className="inline" onSubmit={search} data-testid="cons-list-filter">
        <input placeholder="Search by reference" aria-label="Search consignments" data-testid="cons-list-q"
          value={typed} onChange={(e) => setTyped(e.target.value)} />
        <select aria-label="Show consignments in status" data-testid="cons-list-status" value={status}
          onChange={(e) => { setStatus(e.target.value); setCursors(['']); }}>
          <option value="">Any status</option>
          {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <button type="submit">Search</button>
      </form>
      {list.error && <ErrorBanner message={list.error} />}

      {allowed && (
        <p className="actions" data-testid="cons-bulk-bar">
          <span data-testid="cons-picked-count">{picked.length} picked</span>
          <button type="button" data-testid="cons-bulk-amend" disabled={picked.length === 0 || busy}
            onClick={() => setAmending(true)}>Amend picked</button>
          <button type="button" data-testid="cons-bulk-cancel" disabled={picked.length === 0 || busy}
            onClick={askCancel}>Cancel picked</button>
          {picked.length > 0 && (
            <button type="button" className="link" data-testid="cons-pick-none" onClick={() => { setPicked([]); setLimitHit(false); }}>
              Clear picks
            </button>
          )}
        </p>
      )}
      {limitHit && <p className="error" role="alert" data-testid="cons-pick-limit">{PICK_LIMIT_MESSAGE}</p>}
      {confirm.dialog}

      {amending && allowed && (
        <section data-testid="cons-bulk-amend-form" aria-label="Amend picked consignments">
          <h3>Amend {picked.length} picked</h3>
          <p className="muted">
            What you fill in is set on every picked consignment; tick Clear to empty a field on all of them; a field left alone is
            kept as it is. Times are ISO-8601, such as 2026-12-01T06:00:00Z.
          </p>
          <form onSubmit={apply}>
            {PARTICULAR_FIELDS.map((f) => (
              <div key={f.key}>
                <label>{f.label}
                  <input name={f.key} data-testid={`bulk-particular-${f.key}`} aria-label={`${f.label} for picked`}
                    value={values[f.key] ?? ''} disabled={!!cleared[f.key]}
                    onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))} />
                </label>
                <label>
                  <input type="checkbox" data-testid={`bulk-clear-${f.key}`} aria-label={`Clear ${f.label} on picked`}
                    checked={!!cleared[f.key]} onChange={(e) => setCleared((c) => ({ ...c, [f.key]: e.target.checked }))} />
                  Clear
                </label>
              </div>
            ))}
            <p className="actions">
              <button type="submit" data-testid="cons-bulk-apply" disabled={busy || Object.keys(changes).length === 0}>
                Apply to picked
              </button>
              <button type="button" data-testid="cons-bulk-amend-close" onClick={() => setAmending(false)}>Close form</button>
            </p>
          </form>
        </section>
      )}

      {error && <ErrorBanner message={error} testId="cons-bulk-error" />}
      {summary && <p className="ok" role="status" data-testid="cons-bulk-done">{summary}</p>}
      {results && results.length > 0 && (
        <div className="table-wrap"><table data-testid="cons-bulk-results">
          <thead><tr><th>Consignment</th><th>Result</th><th>Why</th></tr></thead>
          <tbody>
            {results.map((r) => (
              <tr key={r.consignmentId} data-testid="cons-bulk-result" data-outcome={r.ok ? 'ok' : 'refused'}>
                <td>{r.ref}</td>
                <td>{r.ok ? 'OK' : <code data-testid="cons-bulk-result-code">{r.code || 'refused'}</code>}</td>
                <td>{r.ok ? '' : [r.message, ...r.fieldErrors].filter(Boolean).join('; ')}</td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}

      <div className="table-wrap"><table data-testid="cons-list">
        <thead>
          <tr>
            {allowed && (
              <th>
                <input type="checkbox" data-testid="cons-pick-all" aria-label="Pick all on this page" checked={allOnPage}
                  disabled={rows.length === 0} onChange={togglePage} />
              </th>
            )}
            <th>Reference</th><th>Status</th><th>Consignee</th><th>Destination</th><th>Weight kg</th><th>Load</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id} data-testid="cons-row">
              {allowed && (
                <td>
                  <input type="checkbox" data-testid="cons-pick" aria-label={`Pick consignment ${r.trackingRef}`}
                    checked={isPicked(r.id)} onChange={() => toggle(r)} />
                </td>
              )}
              <td>{r.trackingRef}</td><td><StatusBadge value={r.status} /></td><td>{r.consigneeName}</td>
              <td>{r.destinationTerminalCode}</td><td className="num">{r.deadWeightKg}</td><td>{r.loadNo || '—'}</td>
            </tr>
          ))}
        </tbody>
      </table></div>
      {!list.loading && rows.length === 0 && !list.error && <p className="muted" data-testid="cons-list-empty">No consignments match.</p>}
      <p className="actions">
        <button type="button" data-testid="cons-page-prev" disabled={page === 0 || list.loading}
          onClick={() => setCursors((c) => c.slice(0, -1))}>Previous page</button>
        <button type="button" data-testid="cons-page-next" disabled={!nextCursor || list.loading}
          onClick={() => setCursors((c) => [...c, nextCursor])}>Next page</button>
        <span className="muted">Up to {MAX_BULK_CONSIGNMENTS} can be picked at once, across pages.</span>
      </p>
    </section>
  );
}
