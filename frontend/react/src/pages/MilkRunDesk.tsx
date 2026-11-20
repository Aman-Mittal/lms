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
  ROUTE_DAYS, type CircuitRunCard, type CircuitStopCard, type CircuitStopStep, type RoutePlanDraft, type RouteStopDraft,
} from '../gateway/milkItineraryTypes';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { ErrorBanner } from './ErrorBanner';
import { PageHeader } from './PageHeader';
import { StatusBadge } from './StatusBadge';

interface Refusal {
  code: string;
  message: string;
}

const BLANK_STOP: RouteStopDraft = { terminalId: '', windowOpenMin: '', windowCloseMin: '', plannedDwellMin: '' };

function blankRoute(): RoutePlanDraft {
  return {
    code: '', name: '', plantTerminalId: '', effectiveFrom: '', days: ['MON', 'TUE', 'WED', 'THU', 'FRI'],
    departureTime: '', timeZone: '', runRate: '', perStopFee: '', currency: '', stops: [{ ...BLANK_STOP }],
  };
}

function problem(e: unknown): Refusal {
  return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
}

/**
 * The milk-run desk: publish a route, plan the runs from it, hand a run to a trip, record what happens at each stop
 * and complete the run. The server decides what each state allows; every refusal is shown by its code.
 */
export function MilkRunDesk() {
  const { gateway, can } = useSession();
  const [draft, setDraft] = useState<RoutePlanDraft>(blankRoute);
  const [publishDone, setPublishDone] = useState('');
  const [publishError, setPublishError] = useState<Refusal | null>(null);
  const [genFrom, setGenFrom] = useState('');
  const [genDays, setGenDays] = useState('');
  const [generateDone, setGenerateDone] = useState('');
  const [generateError, setGenerateError] = useState<Refusal | null>(null);
  const [routeFilter, setRouteFilter] = useState('');
  const [runs, setRuns] = useState<CircuitRunCard[]>([]);
  const [runsError, setRunsError] = useState('');
  const [run, setRun] = useState<CircuitRunCard | null>(null);
  const [stops, setStops] = useState<CircuitStopCard[]>([]);
  const [tripId, setTripId] = useState('');
  const [runDone, setRunDone] = useState('');
  const [runRefusal, setRunRefusal] = useState<Refusal | null>(null);
  const [stopError, setStopError] = useState('');
  const [entries, setEntries] = useState<Record<number, string>>({});
  const plan = can('MILKRUN_PLAN');
  const execute = can('MILKRUN_EXECUTE');

  const set = (key: keyof RoutePlanDraft, value: string) => setDraft((d) => ({ ...d, [key]: value }));
  const setStop = (i: number, key: keyof RouteStopDraft, value: string) =>
    setDraft((d) => ({ ...d, stops: d.stops.map((s, at) => (at === i ? { ...s, [key]: value } : s)) }));
  const toggleDay = (day: string) =>
    setDraft((d) => ({ ...d, days: d.days.includes(day) ? d.days.filter((x) => x !== day) : [...d.days, day] }));
  const setEntry = (sequence: number, value: string) => setEntries((e) => ({ ...e, [sequence]: value }));

  async function loadRuns(code: string = routeFilter) {
    setRunsError('');
    try {
      setRuns(await gateway.circuitRunCards(code));
    } catch (e) {
      setRuns([]);
      setRunsError((e as Error).message);
    }
  }

  useEffect(() => {
    void loadRuns();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function refresh(id: string) {
    try {
      setRun(await gateway.circuitRunCard(id));
      setStops(await gateway.circuitRunStops(id));
    } catch (e) {
      setRun(null);
      setRunsError((e as Error).message);
    }
  }

  async function select(id: string) {
    if (id !== run?.id) {
      setRun(null);
      setStops([]);
      setRunDone('');
      setRunRefusal(null);
      setStopError('');
      setTripId('');
      setEntries({});
    }
    await refresh(id);
  }

  async function publish(event: FormEvent) {
    event.preventDefault();
    setPublishDone('');
    setPublishError(null);
    try {
      const code = draft.code.trim();
      await gateway.publishMilkRoute(draft);
      setDraft(blankRoute());
      setPublishDone(`The route ${code} is published.`);
      setRouteFilter(code);
    } catch (e) {
      setPublishError(problem(e));
    }
  }

  async function generate(event: FormEvent) {
    event.preventDefault();
    setGenerateDone('');
    setGenerateError(null);
    try {
      const made = await gateway.generateCircuitRuns(genFrom, genDays);
      setGenerateDone(`${made} ${made === 1 ? 'run' : 'runs'} planned.`);
      await loadRuns();
    } catch (e) {
      setGenerateError(problem(e));
    }
  }

  /** Makes a change, then reads the run again: the server decides what state it is in. */
  async function act(r: CircuitRunCard, outcome: string, change: () => Promise<void>) {
    setRunDone('');
    setRunRefusal(null);
    try {
      await change();
      setRunDone(outcome);
    } catch (e) {
      setRunRefusal(problem(e));
    }
    await refresh(r.id);
    await loadRuns();
  }

  async function stop(r: CircuitRunCard, s: CircuitStopCard, step: CircuitStopStep) {
    const value = step === 'pickup' || step === 'skip' ? (entries[s.sequence] ?? '').trim() : '';
    setStopError('');
    if (step === 'pickup' && !value) {
      setStopError('Enter the quantity picked up.');
      return;
    }
    if (step === 'skip' && !value) {
      setStopError('Say why the stop is skipped.');
      return;
    }
    try {
      await gateway.recordCircuitStop(r.id, s.sequence, step, value);
      setEntry(s.sequence, '');
    } catch (e) {
      setStopError((e as Error).message);
    }
    await refresh(r.id);
  }

  return (
    <>
      <PageHeader title="Milk run desk" />
      <p className="muted">Publish a pickup circuit, plan its runs, and follow each run stop by stop.</p>

      {plan && (
        <>
          <form data-testid="mr-publish-form" onSubmit={(ev) => void publish(ev)}>
            <h2>Publish a route</h2>
            <div className="inline">
              <input name="code" placeholder="Route code" aria-label="Route code" data-testid="mr-code" required
                value={draft.code} onChange={(ev) => set('code', ev.target.value)} />
              <input name="name" placeholder="Route name (optional)" aria-label="Route name" data-testid="mr-name"
                value={draft.name} onChange={(ev) => set('name', ev.target.value)} />
              <EntityPicker kind="terminal" placeholder="Plant" ariaLabel="Plant terminal" testId="mr-plant" required
                value={draft.plantTerminalId} onChange={(id) => set('plantTerminalId', id)} />
              <input name="effectiveFrom" type="date" aria-label="Effective from" data-testid="mr-from" required
                value={draft.effectiveFrom} onChange={(ev) => set('effectiveFrom', ev.target.value)} />
              <input name="departureTime" type="time" aria-label="Departure time" data-testid="mr-departs" required
                value={draft.departureTime} onChange={(ev) => set('departureTime', ev.target.value)} />
            </div>
            <div className="inline">
              {ROUTE_DAYS.map((d) => (
                <label key={d}>
                  <input type="checkbox" data-testid={`mr-day-${d}`} checked={draft.days.includes(d)}
                    onChange={() => toggleDay(d)} /> {d}
                </label>
              ))}
            </div>
            <div className="inline">
              <input name="runRate" type="number" min="0" step="any" placeholder="Rate per run" aria-label="Rate per run"
                data-testid="mr-rate" required value={draft.runRate} onChange={(ev) => set('runRate', ev.target.value)} />
              <input name="perStopFee" type="number" min="0" step="any" placeholder="Fee per stop" aria-label="Fee per stop"
                data-testid="mr-fee" required value={draft.perStopFee} onChange={(ev) => set('perStopFee', ev.target.value)} />
              <input name="currency" placeholder="Currency (optional)" aria-label="Route currency" data-testid="mr-currency"
                value={draft.currency} onChange={(ev) => set('currency', ev.target.value)} />
            </div>
            <h3>Supplier stops, in circuit order</h3>
            {draft.stops.map((s, i) => (
              // eslint-disable-next-line react/no-array-index-key
              <div className="inline" key={i} data-testid="mr-stop-row">
                <EntityPicker kind="terminal" placeholder="Supplier" ariaLabel={`Supplier ${i + 1}`} testId="mr-stop-terminal"
                  required value={s.terminalId} onChange={(id) => setStop(i, 'terminalId', id)} />
                <input type="number" min="0" placeholder="Window opens (min)" aria-label={`Window opens, supplier ${i + 1}`}
                  data-testid="mr-stop-open" required value={s.windowOpenMin}
                  onChange={(ev) => setStop(i, 'windowOpenMin', ev.target.value)} />
                <input type="number" min="0" placeholder="Window closes (min)" aria-label={`Window closes, supplier ${i + 1}`}
                  data-testid="mr-stop-close" required value={s.windowCloseMin}
                  onChange={(ev) => setStop(i, 'windowCloseMin', ev.target.value)} />
                <input type="number" min="0" placeholder="Dwell (min)" aria-label={`Dwell, supplier ${i + 1}`}
                  data-testid="mr-stop-dwell" required value={s.plannedDwellMin}
                  onChange={(ev) => setStop(i, 'plannedDwellMin', ev.target.value)} />
                {draft.stops.length > 1 && (
                  <button type="button" className="link" data-testid="mr-stop-remove" aria-label={`Remove supplier ${i + 1}`}
                    onClick={() => setDraft((d) => ({ ...d, stops: d.stops.filter((_, at) => at !== i) }))}>Remove</button>
                )}
              </div>
            ))}
            <p className="actions">
              <button type="button" data-testid="mr-stop-add"
                onClick={() => setDraft((d) => ({ ...d, stops: [...d.stops, { ...BLANK_STOP }] }))}>Add supplier stop</button>
              <button type="submit" data-testid="mr-publish">Publish route</button>
            </p>
          </form>
          {publishDone && <p className="ok" role="status" data-testid="mr-publish-done">{publishDone}</p>}
          {publishError && (
            <ErrorBanner message={publishError.message} code={publishError.code} testId="mr-publish-error"
              codeTestId="mr-publish-refusal" />
          )}

          <form className="inline" data-testid="mr-generate-form" onSubmit={(ev) => void generate(ev)}>
            <input name="genFrom" type="date" aria-label="Plan runs from" data-testid="mr-gen-from"
              value={genFrom} onChange={(ev) => setGenFrom(ev.target.value)} />
            <input name="genDays" type="number" min="1" max="31" placeholder="Days (1 to 31)" aria-label="Days to plan"
              data-testid="mr-gen-days" value={genDays} onChange={(ev) => setGenDays(ev.target.value)} />
            <button type="submit" data-testid="mr-generate">Generate runs</button>
          </form>
          {generateDone && <p className="ok" role="status" data-testid="mr-generate-done">{generateDone}</p>}
          {generateError && (
            <ErrorBanner message={generateError.message} code={generateError.code} testId="mr-generate-error"
              codeTestId="mr-generate-refusal" />
          )}
        </>
      )}

      <h2>Runs</h2>
      <form className="inline" data-testid="mr-runs-form" onSubmit={(ev) => { ev.preventDefault(); void loadRuns(); }}>
        <input name="routeFilter" placeholder="Route code (optional)" aria-label="Show runs of route"
          data-testid="mr-route-filter" value={routeFilter} onChange={(ev) => setRouteFilter(ev.target.value)} />
        <button type="submit" data-testid="mr-runs-show">Show runs</button>
      </form>
      {runsError && <ErrorBanner message={runsError} testId="mr-runs-error" />}
      {runs.length === 0 ? (
        <p className="muted" data-testid="mr-runs-empty">No runs to show.</p>
      ) : (
        <div className="table-wrap"><table data-testid="mr-runs">
          <thead><tr><th>Route</th><th>Date</th><th>Status</th><th>Trip</th><th /></tr></thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id} data-testid="mr-run" data-id={r.id}>
                <td>{r.routeCode}</td><td>{r.runDate}</td>
                <td><StatusBadge value={r.status} /></td><td>{r.tripNo || '—'}</td>
                <td><button type="button" className="link" data-testid="mr-run-select"
                  aria-label={`Select run ${r.routeCode} ${r.runDate}`} onClick={() => void select(r.id)}>Select</button></td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}

      {run && (
        <section data-testid="mr-run-panel" data-id={run.id}>
          <h2>Run <span data-testid="mr-run-route">{run.routeCode}</span> on <span data-testid="mr-run-date">{run.runDate}</span></h2>
          <dl className="facts">
            <div><dt>Status</dt><dd><StatusBadge data-testid="mr-run-status" value={run.status} /></dd></div>
            <div><dt>Trip</dt><dd data-testid="mr-run-trip">{run.tripNo || '—'}</dd></div>
            <div><dt>Started</dt><dd>{run.startedAt || '—'}</dd></div>
            <div><dt>Completed</dt><dd data-testid="mr-run-completed">{run.completedAt || '—'}</dd></div>
            <div><dt>Visited</dt><dd data-testid="mr-run-visited">{run.stopsVisited || '0'}</dd></div>
            <div><dt>Skipped</dt><dd data-testid="mr-run-skipped">{run.stopsSkipped || '0'}</dd></div>
            <div><dt>Picked up (kg)</dt><dd data-testid="mr-run-picked">{run.totalPickedUpKg || '0'}</dd></div>
            <div><dt>Charge</dt><dd data-testid="mr-run-charge">{run.chargeAmount || '—'}</dd></div>
          </dl>
          {runDone && <p className="ok" role="status" data-testid="mr-run-done">{runDone}</p>}
          {runRefusal && (
            <ErrorBanner message={runRefusal.message} code={runRefusal.code} testId="mr-run-error" codeTestId="mr-run-refusal" />
          )}
          {execute && run.status !== 'COMPLETED' && (
            <>
              <form className="inline" data-testid="mr-trip-form" onSubmit={(ev) => {
                ev.preventDefault();
                void act(run, 'The trip is assigned to the run.', async () => {
                  await gateway.assignCircuitTrip(run.id, tripId);
                  setTripId('');
                });
              }}>
                <EntityPicker kind="trip" placeholder="Trip" ariaLabel="Trip for the run" testId="mr-trip-id" required
                  value={tripId} onChange={setTripId} />
                <button type="submit" data-testid="mr-assign-trip">Assign trip</button>
              </form>
              {run.status === 'IN_PROGRESS' && (
                <p className="actions">
                  <button type="button" data-testid="mr-complete"
                    onClick={() => void act(run, 'The run is complete.', () => gateway.completeCircuitRun(run.id))}>Complete run</button>
                </p>
              )}
            </>
          )}

          <h3>Stops</h3>
          {run.status === 'PLANNED' && <p className="muted" data-testid="mr-stops-hint">Stops are recorded once the run is under way.</p>}
          {stopError && <ErrorBanner message={stopError} testId="mr-stop-error" />}
          <div className="table-wrap"><table data-testid="mr-stops">
            <thead><tr><th>#</th><th>Supplier</th><th>Status</th><th>Arrived</th><th>Departed</th><th>Picked up (kg)</th><th /></tr></thead>
            <tbody>
              {stops.map((s) => (
                <tr key={s.sequence} data-testid="mr-stop" data-seq={s.sequence}>
                  <td>{s.sequence}</td><td>{s.terminalCode}</td>
                  <td><StatusBadge data-testid="mr-stop-status" value={s.status} /></td>
                  <td>{s.arrivedAt || '—'}</td><td>{s.departedAt || '—'}</td>
                  <td data-testid="mr-stop-picked">{s.pickedUpKg || '—'}</td>
                  <td>
                    {execute && run.status === 'IN_PROGRESS' && s.status === 'PENDING' && (
                      <>
                        <button type="button" className="link" data-testid="mr-stop-arrive"
                          aria-label={`Arrive at ${s.terminalCode}`} onClick={() => void stop(run, s, 'arrival')}>Arrive</button>
                        <input placeholder="Reason" aria-label={`Reason to skip ${s.terminalCode}`} data-testid="mr-stop-skip-reason"
                          value={entries[s.sequence] ?? ''} onChange={(ev) => setEntry(s.sequence, ev.target.value)} />
                        <button type="button" className="link" data-testid="mr-stop-skip"
                          aria-label={`Skip ${s.terminalCode}`} onClick={() => void stop(run, s, 'skip')}>Skip</button>
                      </>
                    )}
                    {execute && run.status === 'IN_PROGRESS' && s.status === 'ARRIVED' && (
                      <>
                        <input type="number" min="0" step="any" placeholder="kg" aria-label={`Quantity picked up at ${s.terminalCode}`}
                          data-testid="mr-stop-qty" value={entries[s.sequence] ?? ''}
                          onChange={(ev) => setEntry(s.sequence, ev.target.value)} />
                        <button type="button" className="link" data-testid="mr-stop-pickup"
                          aria-label={`Record pickup at ${s.terminalCode}`} onClick={() => void stop(run, s, 'pickup')}>Pickup</button>
                        <button type="button" className="link" data-testid="mr-stop-depart"
                          aria-label={`Depart from ${s.terminalCode}`} onClick={() => void stop(run, s, 'departure')}>Depart</button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table></div>
        </section>
      )}
    </>
  );
}
