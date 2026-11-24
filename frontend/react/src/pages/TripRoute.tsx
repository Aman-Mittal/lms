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

import { Fragment, useState, type FormEvent } from 'react';
import type { TripStopView } from '../gateway/lmsGateway';
import { useSearchParams } from 'react-router-dom';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { MapView } from './MapView';
import { TripMoney } from './TripMoney';
import { PageHeader } from './PageHeader';
import { StatusBadge } from './StatusBadge';
import { ErrorBanner } from './ErrorBanner';
import { useConfirm } from './ConfirmDialog';
import { confirmWording } from '../../../shared/confirm';

/** A trip's track so far and where it is now. */
export function TripRoute() {
  const { gateway, can } = useSession();
  // A link from another screen names the trip, so the route is shown straight away.
  const [params] = useSearchParams();
  const [typed, setTyped] = useState(params.get('trip') ?? '');
  const [tripId, setTripId] = useState(params.get('trip') ?? '');
  const route = useLoad(() => (tripId ? gateway.tripRoute(tripId) : Promise.resolve(null)), tripId);
  // The stops are read again after every action: the server decides what state a stop is in.
  const [reread, setReread] = useState(0);
  const stops = useLoad(() => (tripId ? gateway.tripStops(tripId) : Promise.resolve([] as TripStopView[])),
    `${tripId}:${reread}`);
  const [stopError, setStopError] = useState('');
  const confirm = useConfirm();
  // Each "Show route" counts, so the money panel re-reads even when the trip is the same one.
  const [lookups, setLookups] = useState(0);

  function lookUp(event: FormEvent) {
    event.preventDefault();
    setTripId(typed.trim());
    setLookups((n) => n + 1);
  }

  async function act(action: () => Promise<void>) {
    setStopError('');
    try {
      await action();
    } catch (e) {
      setStopError((e as Error).message);
    }
    setReread((n) => n + 1);
  }

  return (
    <>
      <PageHeader title="Trip route" />
      <p className="muted">The route a trip has driven so far, from the positions its vehicle reported.</p>
      <form className="inline" onSubmit={lookUp} data-testid="trip-lookup">
        <EntityPicker kind="trip" placeholder="Trip" ariaLabel="Trip id" testId="trip-id"
          value={typed} onChange={setTyped} required />
        <button type="submit">Show route</button>
      </form>
      {tripId && route.loading && <p className="muted">Loading…</p>}
      {route.error && <ErrorBanner message={route.error} />}
      {route.data && (
        <section data-testid="trip-route">
          <dl className="facts">
            <div><dt>Trip</dt><dd data-testid="route-trip-no">{route.data.tripNo}</dd></div>
            <div><dt>Status</dt><dd><StatusBadge value={route.data.status} /></dd></div>
            <div><dt>From</dt><dd>{route.data.originCode}</dd></div>
            <div><dt>To</dt><dd>{route.data.destinationCode}</dd></div>
            <div><dt>Last fix</dt><dd>{route.data.lastFixAt || '—'}</dd></div>
            <div><dt>Done</dt><dd>{route.data.completionPct ? `${route.data.completionPct} %` : '—'}</dd></div>
          </dl>
          {route.data.features.length === 0 && (
            <p className="muted" data-testid="no-track">No positions have been reported for this trip yet.</p>
          )}
          <MapView features={route.data.features} label={`Route of ${route.data.tripNo}`} />
        </section>
      )}
      {route.data && (
        <section data-testid="trip-stops" aria-label="Trip stops">
          <h2>Stops</h2>
          {(stopError || stops.error) && <ErrorBanner message={stopError || stops.error} testId="stop-error" />}
          {(stops.data ?? []).length === 0 ? (
            <p className="muted" data-testid="stops-empty">This trip has no planned stops.</p>
          ) : (
            <div className="table-wrap"><table data-testid="stops">
              <thead>
                <tr><th>#</th><th>Kind</th><th>Terminal</th><th>Planned arrival</th><th>Status</th><th>Arrived</th>
                  <th>Departed</th><th /><th /></tr>
              </thead>
              <tbody>
                {(stops.data ?? []).map((s) => (
                  <Fragment key={s.id}>
                    <tr data-testid="stop-row" data-seq={s.seq}>
                      <td>{s.seq}</td><td>{s.kind}</td><td>{s.terminalCode}</td><td>{s.plannedArrivalAt || '—'}</td>
                      <td><StatusBadge data-testid="stop-status" value={s.status} /></td>
                      <td>{s.arrivedAt || '—'}</td><td>{s.departedAt || '—'}</td>
                      <td>
                        {s.outOfOrder && <span className="badge" data-testid="stop-out-of-order">Out of order</span>}
                        {s.lateMinutes > 0 && <span className="badge" data-testid="stop-late">{s.lateMinutes} min late</span>}
                        {s.skipReason && <span className="muted">{s.skipReason}</span>}
                      </td>
                      <td>
                        {can('TRIP_EXECUTE') && s.status === 'PLANNED' && (
                          <>
                            <button type="button" className="link" data-testid="stop-arrive"
                              aria-label={`Arrive at ${s.terminalCode}`}
                              onClick={() => void act(() => gateway.arriveAtStop(tripId, s.id))}>Arrive</button>
                            <button type="button" className="link" data-testid="stop-skip"
                              aria-label={`Skip ${s.terminalCode}`}
                              onClick={() => confirm.ask({
                                wording: confirmWording('Skip stop', s.terminalCode),
                                reason: { label: 'Why the stop is skipped', testId: 'stop-skip-reason', placeholder: 'Why', required: true },
                                run: (why) => act(() => gateway.skipStop(tripId, s.id, why)),
                              })}>Skip</button>
                          </>
                        )}
                        {can('TRIP_EXECUTE') && s.status === 'ARRIVED' && (
                          <button type="button" className="link" data-testid="stop-depart"
                            aria-label={`Depart from ${s.terminalCode}`}
                            onClick={() => void act(() => gateway.departFromStop(tripId, s.id))}>Depart</button>
                        )}
                      </td>
                    </tr>
                  </Fragment>
                ))}
              </tbody>
            </table></div>
          )}
        </section>
      )}
      {confirm.dialog}
      {route.data && tripId && <TripMoney key={`${tripId}:${lookups}`} tripId={tripId} />}
    </>
  );
}
