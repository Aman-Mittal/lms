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

import { useState, type FormEvent } from 'react';
import { CLAIM_TYPES, type ClaimTarget, type ClaimType } from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { Qr } from './Qr';
import { RESOURCES } from './resources';

const resource = RESOURCES.find((r) => r.key === 'claims')!;

/** Claims: the list, a form to raise one, and a panel to decide and print it. */
export function ClaimsPage() {
  const { gateway, can } = useSession();
  const [reload, setReload] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const { data, error, loading } = useLoad(() => gateway.list('claims'), reload);

  const rows = data ?? [];
  const selected = rows.find((row) => row.id === selectedId) ?? null;

  return (
    <div className={selected ? 'with-panel' : undefined}>
      <div className="list">
        <h1>Claims</h1>
        {error && <p className="error" role="alert">{error}</p>}
        {loading ? (
          <p>Loading…</p>
        ) : rows.length === 0 && !error ? (
          <p className="muted">Nothing here yet.</p>
        ) : (
          rows.length > 0 && (
            <table>
              <thead>
                <tr>{resource.columns.map((c) => <th key={c.field}>{c.header}</th>)}</tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr
                    key={row.id} data-testid="row" tabIndex={0}
                    className={row.id === selectedId ? 'selected' : undefined}
                    onClick={() => setSelectedId(row.id)}
                    onKeyDown={(e) => { if (e.key === 'Enter') { setSelectedId(row.id); } }}
                  >
                    {resource.columns.map((c) => <td key={c.field}>{row[c.field]}</td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          )
        )}
        {can('CLAIM_RAISE') && <RaiseClaim onDone={() => setReload((n) => n + 1)} />}
      </div>
      {selected && (
        <ClaimPanel key={selected.id} row={selected} onClose={() => setSelectedId(null)}
          onChanged={() => setReload((n) => n + 1)} />
      )}
    </div>
  );
}

function RaiseClaim({ onDone }: { onDone: () => void }) {
  const { gateway } = useSession();
  const [consignmentId, setConsignmentId] = useState('');
  const [claimType, setClaimType] = useState<ClaimType>('LOSS');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      await gateway.raiseClaim({
        consignmentId: consignmentId.trim(), claimType, amount: Number(amount), description,
      });
      setConsignmentId('');
      setAmount('');
      setDescription('');
      onDone();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <h2>Raise a claim</h2>
      <form className="inline" onSubmit={submit} data-testid="raise-claim">
        <input placeholder="Consignment id" aria-label="Consignment id" value={consignmentId}
          onChange={(e) => setConsignmentId(e.target.value)} required />
        <select aria-label="Claim type" value={claimType}
          onChange={(e) => setClaimType(e.target.value as ClaimType)}>
          {CLAIM_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
        <input placeholder="Amount" aria-label="Amount" inputMode="decimal" value={amount}
          onChange={(e) => setAmount(e.target.value)} required />
        <input placeholder="What happened" aria-label="Description" value={description}
          onChange={(e) => setDescription(e.target.value)} required />
        <button type="submit">Raise claim</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
    </>
  );
}

interface PanelProps {
  row: Record<string, string>;
  onClose: () => void;
  onChanged: () => void;
}

function ClaimPanel({ row, onClose, onChanged }: PanelProps) {
  const { gateway, can } = useSession();
  const [approved, setApproved] = useState('');
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const [slip, setSlip] = useState(false);
  const decide = can('CLAIM_DECIDE');

  async function move(target: ClaimTarget) {
    setError('');
    try {
      await gateway.transitionClaim(row.id, {
        target,
        approvedAmount: target === 'APPROVED' ? Number(approved) : undefined,
        resolutionNote: target === 'REJECTED' ? note : undefined,
      });
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <aside className="panel" data-testid="claim-panel" aria-label="Claim details">
      <header>
        <h2>{row.claimNo}</h2>
        <button className="link" onClick={onClose} aria-label="Close panel">Close</button>
      </header>
      <dl>
        <div><dt>Status</dt><dd data-testid="claim-status">{row.status}</dd></div>
        <div><dt>Type</dt><dd>{row.claimType}</dd></div>
        <div><dt>Claimed</dt><dd>{row.amount}</dd></div>
        <div><dt>Approved</dt><dd data-testid="claim-approved">{row.approvedAmount || '—'}</dd></div>
        <div><dt>Note</dt><dd>{row.resolutionNote || '—'}</dd></div>
      </dl>
      {error && <p className="error" role="alert">{error}</p>}
      {decide && row.status === 'OPEN' && (
        <p><button data-testid="act-review" onClick={() => move('UNDER_REVIEW')}>Take under review</button></p>
      )}
      {decide && row.status === 'UNDER_REVIEW' && (
        <>
          <label>
            Approved amount
            <input data-testid="approved-amount" inputMode="decimal" value={approved}
              onChange={(e) => setApproved(e.target.value)} />
          </label>
          <p><button data-testid="act-approve" onClick={() => move('APPROVED')}>Approve</button></p>
          <label>
            Reason for rejecting
            <input data-testid="reject-note" value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
          <p><button data-testid="act-reject" onClick={() => move('REJECTED')}>Reject</button></p>
        </>
      )}
      {decide && row.status === 'APPROVED' && (
        <p><button data-testid="act-settle" onClick={() => move('SETTLED')}>Settle</button></p>
      )}
      <p><button className="link" data-testid="print-claim" onClick={() => setSlip(true)}>Print slip</button></p>
      {slip && (
        <section className="slip" data-testid="claim-slip" aria-label="Claim slip">
          <h3>Claim slip</h3>
          <div className="slip-body">
            <dl>
              <div><dt>Claim</dt><dd>{row.claimNo}</dd></div>
              <div><dt>Type</dt><dd>{row.claimType}</dd></div>
              <div><dt>Claimed</dt><dd>{row.amount}</dd></div>
              <div><dt>Status</dt><dd>{row.status}</dd></div>
              <div><dt>Consignment</dt><dd>{row.consignmentId}</dd></div>
              <div><dt>Description</dt><dd>{row.description}</dd></div>
            </dl>
            <Qr payload={`LMS:CLAIM:${row.claimNo}`} />
          </div>
          <p className="actions no-print">
            <button data-testid="print-now" onClick={() => window.print()}>Print</button>
          </p>
        </section>
      )}
    </aside>
  );
}
