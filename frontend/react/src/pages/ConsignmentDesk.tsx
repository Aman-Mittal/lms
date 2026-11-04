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
import type { ConsignmentCard } from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { Qr } from './Qr';
import { compareWithWhole, splitByCapacity, type PlannedPart } from './splitPlan';

/**
 * The consignment desk: find a consignment by its reference, see where it is,
 * print its lorry receipt, and split one too big for a single vehicle.
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
      </dl>

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
