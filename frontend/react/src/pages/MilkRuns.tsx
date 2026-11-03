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

import { useState } from 'react';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { MapView } from './MapView';
import { RowTable } from './RowTable';

/** Milk-run routes, one route's circuit on the map, and the runs generated from it. */
export function MilkRuns() {
  const { gateway } = useSession();
  const [picked, setPicked] = useState<{ id: string; code: string } | null>(null);
  const list = useLoad(() => gateway.milkRunRoutes(), 'routes');
  const detail = useLoad(() => (picked ? gateway.milkRunRoute(picked.id) : Promise.resolve(null)), picked?.id);
  const runs = useLoad(() => (picked ? gateway.circuitRuns(picked.code) : Promise.resolve([])), picked?.code);

  return (
    <>
      <h1>Milk runs</h1>
      <p className="muted">Scheduled pickup circuits: from the plant, round the suppliers in order, and back.</p>
      {list.loading && <p className="muted">Loading…</p>}
      {list.error && <p className="error" role="alert">{list.error}</p>}
      {list.data && list.data.length === 0 && <p className="muted" data-testid="no-routes">No milk-run routes yet.</p>}
      {list.data && list.data.length > 0 && (
        <RowTable testId="routes" rows={list.data} picked={picked?.id}
          onPick={(r) => setPicked({ id: r.id, code: r.code })} columns={[
            { header: 'Route', field: 'code' }, { header: 'Name', field: 'name' }, { header: 'Plant', field: 'plantCode' },
            { header: 'Days', field: 'daysOfWeek' }, { header: 'Departs', field: 'departureTime' },
            { header: 'From', field: 'effectiveFrom' },
          ]} />
      )}
      {detail.error && <p className="error" role="alert">{detail.error}</p>}
      {detail.data && (
        <section data-testid="route-detail">
          <h2>{detail.data.header.code} · {detail.data.header.name}</h2>
          <MapView features={detail.data.features} label={`Circuit of ${detail.data.header.code}`} />
          <RowTable testId="stops" rows={detail.data.parts} columns={[
            { header: '#', field: 'sequence' }, { header: 'Stop', field: 'terminalCode' },
            { header: 'Name', field: 'terminalName' }, { header: 'Window opens (min)', field: 'windowOpenMin' },
            { header: 'Window closes (min)', field: 'windowCloseMin' }, { header: 'Dwell (min)', field: 'plannedDwellMin' },
          ]} />
          <h3>Runs</h3>
          {runs.data && runs.data.length === 0 && <p className="muted" data-testid="no-runs">No runs generated yet.</p>}
          {runs.data && runs.data.length > 0 && (
            <RowTable testId="runs" rows={runs.data} columns={[
              { header: 'Date', field: 'runDate' }, { header: 'Status', field: 'status' }, { header: 'Trip', field: 'tripNo' },
              { header: 'Visited', field: 'stopsVisited' }, { header: 'Skipped', field: 'stopsSkipped' },
              { header: 'Charge', field: 'chargeAmount' },
            ]} />
          )}
        </section>
      )}
    </>
  );
}
