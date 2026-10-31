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

import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { MapView } from './MapView';
import { RowTable } from './RowTable';

/** Every site with its geofence, and every vehicle where it last reported. */
export function NetworkMap() {
  const { gateway } = useSession();
  const network = useLoad(() => gateway.networkMap(), 'network');

  return (
    <>
      <h1>Network map</h1>
      <p className="muted">Sites with their geofences, and each vehicle at its last reported position.</p>
      {network.loading && <p className="muted">Loading…</p>}
      {network.error && <p className="error" role="alert">{network.error}</p>}
      {network.data && (
        <>
          <MapView features={network.data.features} label="Sites and vehicles" />
          <RowTable testId="sites" rows={network.data.rows} columns={[
            { header: 'Code', field: 'code' }, { header: 'Name', field: 'name' },
            { header: 'Category', field: 'functionalCategory' }, { header: 'Geofence', field: 'geofenceType' },
          ]} />
        </>
      )}
    </>
  );
}
