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
import { useSearchParams } from 'react-router-dom';
import { ApiProblem, type TripStopView } from '../gateway/lmsGateway';
import type { TripCard, TripRaiseDraft, TripStep } from '../gateway/tripDeskTypes';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { ErrorBanner } from './ErrorBanner';
import { useConfirm } from './ConfirmDialog';
import { confirmWording } from '../../../shared/confirm';
import { PageHeader } from './PageHeader';
import { StatusBadge } from './StatusBadge';

/** The states in which a trip has not left the yard, and so may still be cancelled. */
const CANCELLABLE = ['PLANNED', 'ASSIGNED', 'AT_ORIGIN', 'LOADED'];

interface Next {
  step: TripStep;
  testId: string;
  label: string;
  done: string;
}

/** The one milestone each state is waiting for, with its button's words and what is said when it is done. */
const NEXT: Record<string, Next | undefined> = {
  AT_ORIGIN: { step: 'loaded', testId: 'trip-loaded', label: 'Mark loaded', done: 'The trip is marked loaded.' },
  LOADED: { step: 'dispatch', testId: 'trip-dispatch', label: 'Dispatch', done: 'The trip is dispatched.' },
  DISPATCHED: { step: 'in-transit', testId: 'trip-in-transit', label: 'Start transit', done: 'The trip is on the road.' },
  IN_TRANSIT: { step: 'arrival', testId: 'trip-arrival', label: 'Record arrival', done: 'Arrival is recorded.' },
  AT_DESTINATION: { step: 'completion', testId: 'trip-complete', label: 'Complete trip', done: 'The trip is complete.' },
};

interface Refusal {
  code: string;
  message: string;
}

const BLANK: TripRaiseDraft = { tripNo: '', loadId: '', plannedStartAt: '' };

function problem(e: unknown): Refusal {
  return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
}

/**
 * The trip desk: raise a trip against an awarded load, nominate its vehicle and driver, move it through loading,
 * dispatch, transit, arrival and completion, cancel it before it leaves, and see and tick off its stops. The server
 * decides what each state allows; every refusal is shown by its code.
 */
export function TripDesk() {
  const { gateway, can } = useSession();
  const [params] = useSearchParams();
  const linked = params.get('trip') ?? '';

  const [draft, setDraft] = useState<TripRaiseDraft>(BLANK);
  const [typed, setTyped] = useState(linked);
  const [trip, setTrip] = useState<TripCard | null>(null);
  const [stops, setStops] = useState<TripStopView[]>([]);
  const [loadError, setLoadError] = useState('');
  const [raiseError, setRaiseError] = useState<Refusal | null>(null);
  const [stopError, setStopError] = useState('');
  const [done, setDone] = useState('');
  const [refusal, setRefusal] = useState<Refusal | null>(null);
  const [vehicleId, setVehicleId] = useState('');
  const [driverId, setDriverId] = useState('');
  const confirm = useConfirm();
  const execute = can('TRIP_EXECUTE');

  async function refresh(id: string) {
    setLoadError('');
    try {
      setTrip(await gateway.tripCard(id));
      setStops(await gateway.tripStops(id));
    } catch (e) {
      setTrip(null);
      setLoadError((e as Error).message);
    }
  }

  async function open(id: string = typed) {
    const wanted = id.trim().toLowerCase();
    if (wanted !== trip?.id) {
      setTrip(null);
      setStops([]);
      setDone('');
      setRefusal(null);
      setVehicleId('');
      setDriverId('');
    }
    await refresh(wanted);
  }

  // A link from another screen names the trip, so it is shown straight away.
  useEffect(() => {
    if (linked) {
      void open(linked);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function raise(event: FormEvent) {
    event.preventDefault();
    setRaiseError(null);
    try {
      const id = await gateway.raiseTrip(draft);
      setDraft(BLANK);
      setTyped(id);
      await open(id);
      setDone('The trip is raised.');
    } catch (e) {
      setRaiseError(problem(e));
    }
  }

  /** Makes a change, then reads the trip again: the server decides what state it is in. */
  async function act(t: TripCard, outcome: string, change: () => Promise<void>) {
    setDone('');
    setRefusal(null);
    try {
      await change();
      setDone(outcome);
    } catch (e) {
      setRefusal(problem(e));
    }
    await refresh(t.id);
  }

  async function stopAct(t: TripCard, change: () => Promise<void>) {
    setStopError('');
    try {
      await change();
    } catch (e) {
      setStopError((e as Error).message);
    }
    await refresh(t.id);
  }

  const next = trip ? NEXT[trip.status] : undefined;

  return (
    <>
      <PageHeader title="Trip desk" />
      <p className="muted">Raise a trip against an awarded load, put a vehicle and driver on it, and take it from the yard to completion.</p>

      {execute && (
        <form className="inline" onSubmit={(ev) => void raise(ev)} data-testid="trip-raise-form">
          <input name="tripNo" placeholder="Trip number" aria-label="New trip number" data-testid="trip-no-input" required
            value={draft.tripNo} onChange={(ev) => setDraft((d) => ({ ...d, tripNo: ev.target.value }))} />
          <EntityPicker kind="load" placeholder="Awarded load" ariaLabel="Load id" testId="trip-load-id" required
            value={draft.loadId} onChange={(id) => setDraft((d) => ({ ...d, loadId: id }))} />
          <input name="plannedStartAt" type="datetime-local" aria-label="Planned start" data-testid="trip-planned-start"
            value={draft.plannedStartAt} onChange={(ev) => setDraft((d) => ({ ...d, plannedStartAt: ev.target.value }))} />
          <button type="submit" data-testid="trip-raise">Raise trip</button>
        </form>
      )}
      {execute && raiseError && (
        <ErrorBanner message={raiseError.message} code={raiseError.code} testId="trip-raise-error"
          codeTestId="trip-raise-refusal" />
      )}

      <form className="inline" onSubmit={(ev) => { ev.preventDefault(); void open(); }} data-testid="trip-open-form">
        <EntityPicker kind="trip" placeholder="Trip" ariaLabel="Trip id" testId="trip-open-id" required
          value={typed} onChange={setTyped} />
        <button type="submit" data-testid="trip-open">Open trip</button>
      </form>
      {loadError && <ErrorBanner message={loadError} testId="trip-load-error" />}

      {trip && (
        <section data-testid="trip-panel" data-id={trip.id}>
          <h2>Trip <span data-testid="trip-no">{trip.tripNo}</span></h2>
          <dl className="facts">
            <div><dt>Status</dt><dd><StatusBadge data-testid="trip-status" value={trip.status} /></dd></div>
            <div><dt>Load</dt><dd data-testid="trip-load" data-id={trip.loadId}>{trip.loadNo || '—'}</dd></div>
            <div><dt>Vendor</dt><dd data-testid="trip-vendor">{trip.vendorName || '—'}</dd></div>
            <div><dt>Vehicle</dt><dd data-testid="trip-vehicle">{trip.vehicle || '—'}</dd></div>
            <div><dt>Driver</dt><dd data-testid="trip-driver">{trip.driver || '—'}</dd></div>
            <div><dt>From</dt><dd data-testid="trip-origin">{trip.origin || '—'}</dd></div>
            <div><dt>To</dt><dd data-testid="trip-destination">{trip.destination || '—'}</dd></div>
            <div><dt>Planned start</dt><dd data-testid="trip-planned">{trip.plannedStartAt || '—'}</dd></div>
            <div><dt>Dispatched</dt><dd data-testid="trip-dispatched">{trip.dispatchedAt || '—'}</dd></div>
            <div><dt>Arrived</dt><dd data-testid="trip-arrived">{trip.arrivedAt || '—'}</dd></div>
            <div><dt>Completed</dt><dd data-testid="trip-completed">{trip.completedAt || '—'}</dd></div>
            {trip.cancelledAt && (
              <div><dt>Cancelled because</dt><dd data-testid="trip-cancelled-reason">{trip.cancellationReason || '—'}</dd></div>
            )}
          </dl>
          {done && <p className="ok" role="status" data-testid="trip-done">{done}</p>}
          {refusal && (
            <ErrorBanner message={refusal.message} code={refusal.code} testId="trip-error" codeTestId="trip-refusal" />
          )}

          {execute && trip.status === 'PLANNED' && (
            <form className="inline" data-testid="trip-assign-form" onSubmit={(ev) => {
              ev.preventDefault();
              void act(trip, 'The vehicle is assigned.', () => gateway.assignTrip(trip.id, vehicleId, driverId));
            }}>
              <EntityPicker kind="vehicle" placeholder="Vehicle" ariaLabel="Vehicle id" testId="trip-vehicle-id" required
                value={vehicleId} onChange={setVehicleId} />
              <EntityPicker kind="driver" placeholder="Driver (optional)" ariaLabel="Driver id" testId="trip-driver-id"
                value={driverId} onChange={setDriverId} />
              <button type="submit" data-testid="trip-assign">Assign</button>
            </form>
          )}
          {execute && trip.status === 'ASSIGNED' && (
            <p className="muted" data-testid="trip-gate-hint">Gate-in and weighing are recorded at the gate desk.</p>
          )}
          {execute && next && (
            <p className="actions">
              <button type="button" data-testid={next.testId}
                onClick={() => void act(trip, next.done, () => gateway.stepTrip(trip.id, next.step))}>
                {next.label}
              </button>
            </p>
          )}
          {execute && CANCELLABLE.includes(trip.status) && (
            <p className="actions" data-testid="trip-cancel-form">
              <button type="button" data-testid="trip-cancel" onClick={() => confirm.ask({
                wording: confirmWording('Cancel trip', trip.tripNo),
                reason: { label: 'Reason for cancelling the trip', testId: 'trip-cancel-reason', placeholder: 'Reason for cancelling', required: true },
                run: (why) => act(trip, 'The trip is cancelled.', () => gateway.cancelTrip(trip.id, why)),
              })}>Cancel trip</button>
            </p>
          )}

          {confirm.dialog}

          <h3>Stops</h3>
          {stopError && <ErrorBanner message={stopError} testId="trip-stop-error" />}
          {stops.length === 0 ? (
            <p className="muted" data-testid="trip-stops-empty">This trip has no planned stops.</p>
          ) : (
            <div className="table-wrap"><table data-testid="trip-stops">
              <thead><tr><th>#</th><th>Kind</th><th>Terminal</th><th>Status</th><th>Arrived</th><th>Departed</th><th /></tr></thead>
              <tbody>
                {stops.map((s) => (
                  <tr key={s.id} data-testid="trip-stop" data-seq={s.seq}>
                    <td>{s.seq}</td><td>{s.kind}</td><td>{s.terminalCode}</td>
                    <td><StatusBadge data-testid="trip-stop-status" value={s.status} /></td>
                    <td>{s.arrivedAt || '—'}</td><td>{s.departedAt || '—'}</td>
                    <td>
                      {execute && s.status === 'PLANNED' && (
                        <button type="button" className="link" data-testid="trip-stop-arrive"
                          aria-label={`Arrive at ${s.terminalCode}`}
                          onClick={() => void stopAct(trip, () => gateway.arriveAtStop(trip.id, s.id))}>Arrive</button>
                      )}
                      {execute && s.status === 'ARRIVED' && (
                        <button type="button" className="link" data-testid="trip-stop-depart"
                          aria-label={`Depart from ${s.terminalCode}`}
                          onClick={() => void stopAct(trip, () => gateway.departFromStop(trip.id, s.id))}>Depart</button>
                      )}
                    </td>
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
