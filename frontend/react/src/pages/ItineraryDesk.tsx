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
import { ApiProblem, type MappedDetail, type Row } from '../gateway/lmsGateway';
import type { ItineraryLegDraft, ItineraryPlanDraft } from '../gateway/milkItineraryTypes';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { ErrorBanner } from './ErrorBanner';
import { PageHeader } from './PageHeader';
import { StatusBadge } from './StatusBadge';

/** The rail milestone each status is waiting for, in the order a railway reports them. */
const RAIL_NEXT: Record<string, string | undefined> = {
  PLANNED: 'PLACED', PLACED: 'LOADED', LOADED: 'DEPARTED', DEPARTED: 'ARRIVED', ARRIVED: 'RELEASED',
};

interface Refusal {
  code: string;
  message: string;
}

const BLANK_LEG: ItineraryLegDraft = {
  mode: 'ROAD', fromTerminalId: '', toTerminalId: '', plannedDepartureAt: '', plannedArrivalAt: '', carrierPartnerId: '',
  tripId: '', unitType: '', unitCount: '', bookingRef: '', trainNo: '', costAmount: '',
};

function blankPlan(): ItineraryPlanDraft {
  return { shipmentRef: '', currency: '', legs: [{ ...BLANK_LEG }] };
}

function problem(e: unknown): Refusal {
  return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
}

/**
 * The itinerary desk: plan a multimodal itinerary leg by leg and record a rail leg's milestones. Road legs move with
 * their trips and have no button here. The server decides what each state allows; every refusal is shown by its code.
 */
export function ItineraryDesk() {
  const { gateway, can } = useSession();
  const [draft, setDraft] = useState<ItineraryPlanDraft>(blankPlan);
  const [planError, setPlanError] = useState<Refusal | null>(null);
  const [list, setList] = useState<Row[]>([]);
  const [listError, setListError] = useState('');
  const [detail, setDetail] = useState<MappedDetail | null>(null);
  const [done, setDone] = useState('');
  const [refusal, setRefusal] = useState<Refusal | null>(null);

  const setLeg = (i: number, key: keyof ItineraryLegDraft, value: string) =>
    setDraft((d) => ({ ...d, legs: d.legs.map((l, at) => (at === i ? { ...l, [key]: value } : l)) }));

  async function loadList() {
    setListError('');
    try {
      setList(await gateway.itineraries());
    } catch (e) {
      setListError((e as Error).message);
    }
  }

  useEffect(() => {
    void loadList();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function refresh(id: string) {
    try {
      setDetail(await gateway.itinerary(id));
    } catch (e) {
      setDetail(null);
      setListError((e as Error).message);
    }
  }

  async function select(id: string) {
    if (id !== detail?.header['id']) {
      setDetail(null);
      setDone('');
      setRefusal(null);
    }
    await refresh(id);
  }

  async function plan(event: FormEvent) {
    event.preventDefault();
    setPlanError(null);
    try {
      const id = await gateway.planItinerary(draft);
      setDraft(blankPlan());
      await loadList();
      await select(id);
      setDone('The itinerary is planned.');
    } catch (e) {
      setPlanError(problem(e));
    }
  }

  async function milestone(d: MappedDetail, leg: Row, next: string) {
    const id = d.header['id'];
    setDone('');
    setRefusal(null);
    try {
      await gateway.recordRailMilestone(id, Number(leg['seq']), next);
      setDone(`Leg ${leg['seq']} is ${next.toLowerCase()}.`);
    } catch (e) {
      setRefusal(problem(e));
    }
    await refresh(id);
    await loadList();
  }

  return (
    <>
      <PageHeader title="Itinerary desk" />
      <p className="muted">Plan a road, rail and road movement leg by leg, and report each rail milestone as the railway does.</p>

      {can('MULTIMODAL_PLAN') && (
        <>
          <form data-testid="it-plan-form" onSubmit={(ev) => void plan(ev)}>
            <h2>Plan an itinerary</h2>
            <div className="inline">
              <input name="shipmentRef" placeholder="Shipment reference" aria-label="Shipment reference" data-testid="it-shipment"
                required value={draft.shipmentRef} onChange={(ev) => setDraft((d) => ({ ...d, shipmentRef: ev.target.value }))} />
              <input name="currency" placeholder="Currency (optional)" aria-label="Itinerary currency" data-testid="it-currency"
                value={draft.currency} onChange={(ev) => setDraft((d) => ({ ...d, currency: ev.target.value }))} />
            </div>
            {draft.legs.map((l, i) => (
              // eslint-disable-next-line react/no-array-index-key
              <fieldset key={i} data-testid="it-leg-row">
                <legend>Leg {i + 1}</legend>
                <div className="inline">
                  <select aria-label={`Mode of leg ${i + 1}`} data-testid="it-leg-mode" value={l.mode}
                    onChange={(ev) => setLeg(i, 'mode', ev.target.value)}>
                    <option value="ROAD">ROAD</option>
                    <option value="RAIL">RAIL</option>
                  </select>
                  <EntityPicker kind="terminal" placeholder="From terminal" ariaLabel={`From terminal of leg ${i + 1}`}
                    testId="it-leg-from" required value={l.fromTerminalId} onChange={(id) => setLeg(i, 'fromTerminalId', id)} />
                  <EntityPicker kind="terminal" placeholder="To terminal" ariaLabel={`To terminal of leg ${i + 1}`}
                    testId="it-leg-to" required value={l.toTerminalId} onChange={(id) => setLeg(i, 'toTerminalId', id)} />
                  <EntityPicker kind="partner" placeholder="Carrier" ariaLabel={`Carrier of leg ${i + 1}`}
                    testId="it-leg-carrier" required value={l.carrierPartnerId} onChange={(id) => setLeg(i, 'carrierPartnerId', id)} />
                </div>
                <div className="inline">
                  <input type="datetime-local" aria-label={`Planned departure of leg ${i + 1}`} data-testid="it-leg-departs"
                    required value={l.plannedDepartureAt} onChange={(ev) => setLeg(i, 'plannedDepartureAt', ev.target.value)} />
                  <input type="datetime-local" aria-label={`Planned arrival of leg ${i + 1}`} data-testid="it-leg-arrives"
                    required value={l.plannedArrivalAt} onChange={(ev) => setLeg(i, 'plannedArrivalAt', ev.target.value)} />
                  <input type="number" min="0" step="any" placeholder="Cost (optional)" aria-label={`Cost of leg ${i + 1}`}
                    data-testid="it-leg-cost" value={l.costAmount} onChange={(ev) => setLeg(i, 'costAmount', ev.target.value)} />
                  {l.mode === 'ROAD' ? (
                    <EntityPicker kind="trip" placeholder="Trip (optional)" ariaLabel={`Trip of leg ${i + 1}`}
                      testId="it-leg-trip" value={l.tripId} onChange={(id) => setLeg(i, 'tripId', id)} />
                  ) : (
                    <>
                      <select aria-label={`Unit type of leg ${i + 1}`} data-testid="it-leg-unit-type" required value={l.unitType}
                        onChange={(ev) => setLeg(i, 'unitType', ev.target.value)}>
                        <option value="">Unit type</option>
                        <option value="WAGON">WAGON</option>
                        <option value="CONTAINER">CONTAINER</option>
                      </select>
                      <input type="number" min="1" placeholder="Units" aria-label={`Unit count of leg ${i + 1}`}
                        data-testid="it-leg-unit-count" required value={l.unitCount}
                        onChange={(ev) => setLeg(i, 'unitCount', ev.target.value)} />
                      <input placeholder="Booking reference" aria-label={`Booking reference of leg ${i + 1}`}
                        data-testid="it-leg-booking" required value={l.bookingRef}
                        onChange={(ev) => setLeg(i, 'bookingRef', ev.target.value)} />
                      <input placeholder="Train number (optional)" aria-label={`Train number of leg ${i + 1}`}
                        data-testid="it-leg-train" value={l.trainNo} onChange={(ev) => setLeg(i, 'trainNo', ev.target.value)} />
                    </>
                  )}
                  {draft.legs.length > 1 && (
                    <button type="button" className="link" data-testid="it-leg-remove" aria-label={`Remove leg ${i + 1}`}
                      onClick={() => setDraft((d) => ({ ...d, legs: d.legs.filter((_, at) => at !== i) }))}>Remove</button>
                  )}
                </div>
              </fieldset>
            ))}
            <p className="actions">
              <button type="button" data-testid="it-leg-add"
                onClick={() => setDraft((d) => ({ ...d, legs: [...d.legs, { ...BLANK_LEG }] }))}>Add leg</button>
              <button type="submit" data-testid="it-plan">Plan itinerary</button>
            </p>
          </form>
          {planError && (
            <ErrorBanner message={planError.message} code={planError.code} testId="it-plan-error" codeTestId="it-plan-refusal" />
          )}
        </>
      )}

      <h2>Itineraries</h2>
      {listError && <ErrorBanner message={listError} testId="it-list-error" />}
      {list.length === 0 ? (
        <p className="muted" data-testid="it-list-empty">No itineraries yet.</p>
      ) : (
        <div className="table-wrap"><table data-testid="it-list">
          <thead><tr><th>Itinerary</th><th>Shipment</th><th>Status</th><th>Legs</th><th /></tr></thead>
          <tbody>
            {list.map((r) => (
              <tr key={r['id']} data-testid="it-item" data-id={r['id']}>
                <td>{r['itineraryNo']}</td><td>{r['shipmentRef']}</td>
                <td><StatusBadge value={r['status']} /></td><td>{r['legCount']}</td>
                <td><button type="button" className="link" data-testid="it-select"
                  aria-label={`Select itinerary ${r['itineraryNo']}`} onClick={() => void select(r['id'])}>Select</button></td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}

      {detail && (
        <section data-testid="it-panel" data-id={detail.header['id']}>
          <h2>Itinerary <span data-testid="it-no">{detail.header['itineraryNo']}</span></h2>
          <dl className="facts">
            <div><dt>Status</dt><dd><StatusBadge data-testid="it-status" value={detail.header['status']} /></dd></div>
            <div><dt>Shipment</dt><dd data-testid="it-shipment-ref">{detail.header['shipmentRef']}</dd></div>
            <div><dt>Total cost</dt><dd data-testid="it-cost">{detail.header['totalCost'] || '—'}</dd></div>
          </dl>
          {done && <p className="ok" role="status" data-testid="it-done">{done}</p>}
          {refusal && <ErrorBanner message={refusal.message} code={refusal.code} testId="it-error" codeTestId="it-refusal" />}
          <div className="table-wrap"><table data-testid="it-legs">
            <thead><tr><th>#</th><th>Mode</th><th>From</th><th>To</th><th>Status</th><th>Reference</th><th /></tr></thead>
            <tbody>
              {detail.parts.map((p) => {
                const next = p['mode'] === 'RAIL' ? RAIL_NEXT[p['status']] : undefined;
                return (
                  <tr key={p['id']} data-testid="it-leg" data-seq={p['seq']}>
                    <td>{p['seq']}</td><td>{p['mode']}</td><td>{p['from']}</td><td>{p['to']}</td>
                    <td><StatusBadge data-testid="it-leg-status" value={p['status']} /></td>
                    <td>{p['reference'] || '—'}</td>
                    <td>
                      {can('MULTIMODAL_TRACK') && next && (
                        <button type="button" className="link" data-testid="it-leg-next"
                          aria-label={`Record ${next} on leg ${p['seq']}`} onClick={() => void milestone(detail, p, next)}>{next}</button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table></div>
        </section>
      )}
    </>
  );
}
