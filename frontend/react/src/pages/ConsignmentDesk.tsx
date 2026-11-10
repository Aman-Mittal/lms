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
import { ApiProblem, type ConsignmentCard, type ConsignmentParticularsDraft } from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { Qr } from './Qr';
import { compareWithWhole, splitByCapacity, type PlannedPart } from './splitPlan';

/**
 * The consignment desk: find a consignment by its reference, see where it is,
 * print its lorry receipt, split one too big for a single vehicle, correct what
 * is said about it while it waits for a load, and cancel it on its own.
 */
export function ConsignmentDesk() {
  const { gateway } = useSession();
  const [typed, setTyped] = useState('');
  // What is being looked at: a reference typed in, or a part or parent opened from the screen.
  const [target, setTarget] = useState<{ ref?: string; id?: string } | null>(null);
  const [reload, setReload] = useState(0);

  const card = useLoad(
    () => (target?.id ? gateway.consignment(target.id)
      : target?.ref ? gateway.consignmentByRef(target.ref) : Promise.resolve(null)),
    `${target?.id ?? ''}:${target?.ref ?? ''}:${reload}`,
  );

  function lookUp(event: FormEvent) {
    event.preventDefault();
    setTarget({ ref: typed.trim() });
    setReload((n) => n + 1);
  }

  return (
    <>
      <h1>Consignments</h1>
      <p className="muted">Look a consignment up by its lorry receipt or docket number.</p>
      <form className="inline" onSubmit={lookUp} data-testid="consignment-lookup">
        <input placeholder="Reference" aria-label="Reference" data-testid="consignment-ref"
          value={typed} onChange={(e) => setTyped(e.target.value)} required />
        <button type="submit">Look up</button>
      </form>
      {card.error && <p className="error" role="alert">{card.error}</p>}
      {card.data && !card.loading && (
        <Detail key={`${card.data.id}:${reload}`} consignment={card.data}
          onOpen={(id) => { setTarget({ id }); setReload((n) => n + 1); }}
          onChanged={() => setReload((n) => n + 1)} />
      )}
    </>
  );
}

interface DetailProps {
  consignment: ConsignmentCard;
  onOpen: (id: string) => void;
  onChanged: () => void;
}

function Detail({ consignment: c, onOpen, onChanged }: DetailProps) {
  const { gateway, can } = useSession();
  const split = c.status === 'SPLIT';
  const parts = useLoad(() => (split ? gateway.consignmentParts(c.id) : Promise.resolve([])), `${c.id}:${c.status}`);
  const [slip, setSlip] = useState(false);
  const splittable = c.status === 'PLANNED' && c.freightMode === 'FTL' && !c.parentId && can('LOAD_CREATE');
  const amendable = c.status === 'PLANNED' && can('LOAD_CREATE');
  const cancellable = (c.status === 'PLANNED' || c.status === 'ASSIGNED_TO_LOAD') && !c.handlingUnitNo
    && !c.parentId && can('LOAD_CREATE');

  return (
    <section data-testid="consignment">
      <h2>{c.trackingRef}</h2>
      <dl>
        <div><dt>Status</dt><dd data-testid="consignment-status">{c.status}</dd></div>
        <div><dt>Proof of delivery</dt><dd data-testid="consignment-proof">
          {c.proofStatus === 'CONFIRMED' ? `Confirmed, ${c.proofCondition}` : c.proofStatus ? 'Code issued' : 'None yet'}
        </dd></div>
        {c.parentId && (
          <div><dt>Part of</dt><dd>
            <button className="link" data-testid="consignment-parent" onClick={() => onOpen(c.parentId)}>
              {c.parentTrackingRef}
            </button>
          </dd></div>
        )}
        <div><dt>Order</dt><dd>{c.orderNo || '—'}</dd></div>
        <div><dt>Consignee</dt><dd>{c.consigneeName}</dd></div>
        <div><dt>Destination</dt><dd>{c.destinationTerminalCode}</dd></div>
        <div><dt>Weight kg</dt><dd data-testid="consignment-weight">{c.deadWeightKg}</dd></div>
        <div><dt>Volume m³</dt><dd>{c.volumeM3}</dd></div>
        <div><dt>Chargeable kg</dt><dd>{c.chargeableWeightKg}</dd></div>
        <div><dt>Load</dt><dd>{c.loadNo || '—'}</dd></div>
        <div><dt>Client reference</dt><dd data-testid="consignment-client-ref">{c.clientReference || '—'}</dd></div>
        <div><dt>Consignee contact</dt><dd data-testid="consignment-contact">
          {c.contactName || '—'}{c.contactPhone ? `, ${c.contactPhone}` : ''}
        </dd></div>
        <div><dt>Consignee reference</dt><dd>{c.consigneeReference || '—'}</dd></div>
        <div><dt>Delivery window</dt><dd data-testid="consignment-window">
          {c.deliveryWindowFrom || '…'} to {c.deliveryWindowTo || '…'}
        </dd></div>
        <div><dt>Remarks</dt><dd data-testid="consignment-remarks">{c.remarks || '—'}</dd></div>
      </dl>

      {amendable && <ParticularsForm consignment={c} onSaved={onChanged} />}
      {cancellable && <CancelForm consignment={c} onCancelled={onChanged} />}

      {split && c.partCount !== null && (
        <div data-testid="split-progress">
          <p>
            Split across <strong data-testid="part-count">{c.partCount}</strong> vehicles:{' '}
            <strong data-testid="parts-delivered">{c.partsDelivered ?? 0}</strong> of {c.partCount} delivered
            {c.partsCancelled ? <>, {c.partsCancelled} cancelled</> : null}.
          </p>
          <progress max={c.partCount} value={c.partsDelivered ?? 0} aria-label="Parts delivered" />
        </div>
      )}
      {split && parts.error && <p className="error" role="alert">{parts.error}</p>}
      {split && parts.data && (
        <table data-testid="parts">
          <thead><tr><th>Part</th><th>Status</th><th>Weight kg</th><th>Load</th></tr></thead>
          <tbody>
            {parts.data.map((p) => (
              <tr key={p.id} data-testid="part-row">
                <td><button className="link" onClick={() => onOpen(p.id)}>{p.trackingRef}</button></td>
                <td>{p.status}</td><td>{p.deadWeightKg}</td><td>{p.loadNo || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {splittable && <SplitForm consignment={c} onSplit={onChanged} />}

      {!split && (
        <>
          <p><button className="link" data-testid="print-lr" onClick={() => setSlip(true)}>Print lorry receipt</button></p>
          {slip && (
            <section className="slip" data-testid="lr-slip" aria-label="Lorry receipt">
              <h3>Lorry receipt {c.trackingRef}</h3>
              <div className="slip-body">
                <dl>
                  {c.parentId && <div><dt>Part of</dt><dd>{c.parentTrackingRef}</dd></div>}
                  <div><dt>Order</dt><dd>{c.orderNo || '—'}</dd></div>
                  <div><dt>Consignee</dt><dd>{c.consigneeName}</dd></div>
                  <div><dt>Destination</dt><dd>{c.destinationTerminalCode}</dd></div>
                  <div><dt>Weight kg</dt><dd>{c.deadWeightKg}</dd></div>
                  <div><dt>Load</dt><dd>{c.loadNo || '—'}</dd></div>
                </dl>
                <Qr payload={`LMS:CONSIGNMENT:${c.id}`} />
              </div>
              <p className="actions no-print">
                <button data-testid="print-now" onClick={() => window.print()}>Print</button>
              </p>
            </section>
          )}
        </>
      )}
    </section>
  );
}

/** Plans the parts from a vehicle's capacity, lets the planner adjust them, and splits. */
function SplitForm({ consignment: c, onSplit }: { consignment: ConsignmentCard; onSplit: () => void }) {
  const { gateway } = useSession();
  const [capacity, setCapacity] = useState('');
  const [parts, setParts] = useState<PlannedPart[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const totals = compareWithWhole(parts, c.deadWeightKg, c.volumeM3);

  function plan(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      setParts(splitByCapacity(c.deadWeightKg, c.volumeM3, capacity));
    } catch (e) {
      setParts([]);
      setError((e as Error).message);
    }
  }

  function edit(index: number, change: Partial<PlannedPart>) {
    setParts((current) => current.map((p, i) => (i === index ? { ...p, ...change } : p)));
  }

  async function submit() {
    setError('');
    setBusy(true);
    try {
      await gateway.splitConsignment(c.id, parts);
      onSplit();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section data-testid="split-form">
      <h3>Split across vehicles</h3>
      <p className="muted">Too big for one vehicle? Plan equal parts that each fit one, adjust them if you need to, then split.</p>
      <form className="inline" onSubmit={plan}>
        <input placeholder="Vehicle capacity kg" aria-label="Vehicle capacity kg" data-testid="split-capacity"
          inputMode="decimal" value={capacity} onChange={(e) => setCapacity(e.target.value)} required />
        <button type="submit" data-testid="plan-parts">Plan parts</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      {parts.length > 0 && (
        <>
          <table data-testid="planned-parts">
            <thead><tr><th>Part</th><th>Weight kg</th><th>Volume m³</th></tr></thead>
            <tbody>
              {parts.map((p, i) => (
                <tr key={i}>
                  <td>{i + 1}</td>
                  <td><input aria-label={`Part ${i + 1} weight kg`} data-testid={`part-weight-${i + 1}`}
                    inputMode="decimal" value={p.deadWeightKg} onChange={(e) => edit(i, { deadWeightKg: e.target.value })} /></td>
                  <td><input aria-label={`Part ${i + 1} volume m³`} data-testid={`part-volume-${i + 1}`}
                    inputMode="decimal" value={p.volumeM3} onChange={(e) => edit(i, { volumeM3: e.target.value })} /></td>
                </tr>
              ))}
            </tbody>
          </table>
          <p data-testid="split-totals" className={totals.ready ? 'muted' : 'error'}>
            Parts total {totals.weightKg} kg and {totals.volumeM3} m³, against {c.deadWeightKg} kg and {c.volumeM3} m³
            {totals.ready ? ' — they match.' : ' — they must match exactly.'}
          </p>
          <p>
            <button data-testid="split-submit" disabled={!totals.ready || busy} onClick={submit}>
              Split into {parts.length} parts
            </button>
          </p>
        </>
      )}
    </section>
  );
}

const PARTICULAR_FIELDS: { key: keyof ConsignmentParticularsDraft; label: string }[] = [
  { key: 'contactName', label: 'Contact name' },
  { key: 'contactPhone', label: 'Contact phone' },
  { key: 'consigneeReference', label: 'Consignee reference' },
  { key: 'clientReference', label: 'Client reference' },
  { key: 'deliveryWindowFrom', label: 'Deliver from' },
  { key: 'deliveryWindowTo', label: 'Deliver by' },
  { key: 'remarks', label: 'Remarks' },
];

interface Refusal {
  code: string;
  message: string;
}

/** A refusal as the desk shows it: the problem's code, when there is one, and its message. */
function refusalOf(e: unknown): Refusal {
  return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
}

function RefusalNote({ refusal, testId }: { refusal: Refusal; testId: string }) {
  return (
    <p className="error" role="alert" data-testid={`${testId}-error`}>
      {refusal.code && <><code data-testid={`${testId}-refusal`}>{refusal.code}</code>{' '}</>}
      {refusal.message}
    </p>
  );
}

/** Corrects what is said about a planned consignment; what it carries stays as cut. */
function ParticularsForm({ consignment: c, onSaved }: { consignment: ConsignmentCard; onSaved: () => void }) {
  const { gateway } = useSession();
  const [draft, setDraft] = useState<ConsignmentParticularsDraft>({
    contactName: c.contactName, contactPhone: c.contactPhone, consigneeReference: c.consigneeReference,
    clientReference: c.clientReference, deliveryWindowFrom: c.deliveryWindowFrom,
    deliveryWindowTo: c.deliveryWindowTo, remarks: c.remarks,
  });
  const [refusal, setRefusal] = useState<Refusal | null>(null);
  const [busy, setBusy] = useState(false);

  async function save(event: FormEvent) {
    event.preventDefault();
    setRefusal(null);
    setBusy(true);
    try {
      await gateway.amendConsignment(c.id, c.version, draft);
      onSaved();
    } catch (e) {
      setRefusal(refusalOf(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section data-testid="particulars-form">
      <h3>Particulars</h3>
      <p className="muted">
        What it carries is fixed; who to call, the references, the delivery window and remarks can be corrected
        until it is on a load. Times are ISO-8601, such as 2026-12-01T06:00:00Z.
      </p>
      <form onSubmit={save}>
        {PARTICULAR_FIELDS.map((f) => (
          <label key={f.key}>{f.label}
            <input name={f.key} data-testid={`particular-${f.key}`} aria-label={f.label} value={draft[f.key]}
              onChange={(e) => setDraft((d) => ({ ...d, [f.key]: e.target.value }))} />
          </label>
        ))}
        <button type="submit" data-testid="save-particulars" disabled={busy}>Save particulars</button>
      </form>
      {refusal && <RefusalNote refusal={refusal} testId="particulars" />}
    </section>
  );
}

/** Cancels the consignment on its own, taking it off its load first. */
function CancelForm({ consignment: c, onCancelled }: { consignment: ConsignmentCard; onCancelled: () => void }) {
  const { gateway } = useSession();
  const [reason, setReason] = useState('');
  const [refusal, setRefusal] = useState<Refusal | null>(null);
  const [busy, setBusy] = useState(false);

  async function cancel(event: FormEvent) {
    event.preventDefault();
    setRefusal(null);
    setBusy(true);
    try {
      await gateway.cancelConsignment(c.id, reason);
      setReason('');
      onCancelled();
    } catch (e) {
      setRefusal(refusalOf(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section data-testid="cancel-form">
      <h3>Cancel this consignment</h3>
      <p className="muted">Takes it off its load first; its order lines go back to be planned again.</p>
      <form className="inline" onSubmit={cancel}>
        <input placeholder="Reason" aria-label="Reason for cancelling" data-testid="cancel-reason"
          value={reason} onChange={(e) => setReason(e.target.value)} />
        <button type="submit" data-testid="cancel-consignment" disabled={busy}>Cancel consignment</button>
      </form>
      {refusal && <RefusalNote refusal={refusal} testId="cancel" />}
    </section>
  );
}
