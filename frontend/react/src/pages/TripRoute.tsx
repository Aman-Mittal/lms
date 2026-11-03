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
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { MapView } from './MapView';

/** A trip's track so far and where it is now. */
export function TripRoute() {
  const { gateway } = useSession();
  const [typed, setTyped] = useState('');
  const [tripId, setTripId] = useState('');
  const route = useLoad(() => (tripId ? gateway.tripRoute(tripId) : Promise.resolve(null)), tripId);

  function lookUp(event: FormEvent) {
    event.preventDefault();
    setTripId(typed.trim());
  }

  return (
    <>
      <h1>Trip route</h1>
      <p className="muted">The route a trip has driven so far, from the positions its vehicle reported.</p>
      <form className="inline" onSubmit={lookUp} data-testid="trip-lookup">
        <input placeholder="Trip id" aria-label="Trip id" data-testid="trip-id"
          value={typed} onChange={(e) => setTyped(e.target.value)} required />
        <button type="submit">Show route</button>
      </form>
      {tripId && route.loading && <p className="muted">Loading…</p>}
      {route.error && <p className="error" role="alert">{route.error}</p>}
      {route.data && (
        <section data-testid="trip-route">
          <dl className="facts">
            <div><dt>Trip</dt><dd data-testid="route-trip-no">{route.data.tripNo}</dd></div>
            <div><dt>Status</dt><dd>{route.data.status}</dd></div>
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
    </>
  );
}
