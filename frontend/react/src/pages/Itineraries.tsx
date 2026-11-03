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

/** Road-rail-road itineraries, and one itinerary's legs on the map. */
export function Itineraries() {
  const { gateway } = useSession();
  const [picked, setPicked] = useState('');
  const list = useLoad(() => gateway.itineraries(), 'itineraries');
  const detail = useLoad(() => (picked ? gateway.itinerary(picked) : Promise.resolve(null)), picked);

  return (
    <>
      <h1>Itineraries</h1>
      <p className="muted">Multimodal movements leg by leg: road to the railhead, rail between railheads, road to the customer.</p>
      {list.loading && <p className="muted">Loading…</p>}
      {list.error && <p className="error" role="alert">{list.error}</p>}
      {list.data && list.data.length === 0 && <p className="muted" data-testid="no-itineraries">No itineraries yet.</p>}
      {list.data && list.data.length > 0 && (
        <RowTable testId="itineraries" rows={list.data} picked={picked} onPick={(r) => setPicked(r.id)} columns={[
          { header: 'Itinerary', field: 'itineraryNo' }, { header: 'Shipment', field: 'shipmentRef' },
          { header: 'Status', field: 'status' }, { header: 'Legs', field: 'legCount' },
          { header: 'Cost', field: 'totalCost' }, { header: 'Connection', field: 'connectionMissed' },
        ]} />
      )}
      {detail.error && <p className="error" role="alert">{detail.error}</p>}
      {detail.data && (
        <section data-testid="itinerary-detail">
          <h2>{detail.data.header.itineraryNo} · {detail.data.header.shipmentRef}</h2>
          <MapView features={detail.data.features} label={`Legs of ${detail.data.header.itineraryNo}`} />
          <RowTable testId="legs" rows={detail.data.parts} columns={[
            { header: '#', field: 'seq' }, { header: 'Mode', field: 'mode' }, { header: 'From', field: 'from' },
            { header: 'To', field: 'to' }, { header: 'Status', field: 'status' },
            { header: 'Planned departure', field: 'plannedDepartureAt' }, { header: 'Planned arrival', field: 'plannedArrivalAt' },
            { header: 'Carrier', field: 'carrier' }, { header: 'Reference', field: 'reference' },
          ]} />
        </section>
      )}
    </>
  );
}
