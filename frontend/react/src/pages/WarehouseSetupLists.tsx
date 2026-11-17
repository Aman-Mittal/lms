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
import type { Row } from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { ConfigActions } from './ConfigActions';
import { StatusFilter } from './StatusFilter';
import { ErrorBanner } from './ErrorBanner';

const active = (row: Row) => row.active !== 'false';

/**
 * The warehouse's bins, with a status filter (the active ones by default) and a Deactivate or Reactivate button on every
 * row. A bin holding stock refuses to switch off and says why; an inactive bin takes no stock.
 */
export function BinList({ warehouseId, version, onChanged }: { warehouseId: string; version: number; onChanged: () => void }) {
  const { gateway } = useSession();
  const [status, setStatus] = useState('');
  const [reload, setReload] = useState(0);
  const bins = useLoad(() => gateway.warehouseBins(warehouseId, status), `${version}:${status}:${reload}`);
  const rows = bins.data ?? [];

  return (
    <>
      <StatusFilter testId="bin-status-filter" value={status} onChange={setStatus} />
      {bins.error && <ErrorBanner message={bins.error} />}
      {!bins.loading && rows.length === 0 ? (
        <p className="muted" data-testid="no-bins">{status ? 'No bins match.' : 'This warehouse has no bins yet.'}</p>
      ) : (
        <div className="table-scroll"><table>
          <thead><tr><th>Bin</th><th>Capacity</th><th>Occupied</th><th>Status</th><th /></tr></thead>
          <tbody>
            {rows.map((b) => (
              <tr key={b.id} data-testid="bin-row" data-id={b.id}>
                <td>{b.code}</td><td>{b.capacityUnits}</td><td>{b.occupiedUnits}</td>
                <td>{active(b) ? 'Active' : 'Inactive'}</td>
                <td>
                  <ConfigActions kind="bins" id={b.id} parentId={warehouseId} active={active(b)} label={`bin ${b.code}`}
                    version={b.version} onChanged={() => { setReload((n) => n + 1); onChanged(); }} />
                </td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}
    </>
  );
}

/**
 * The tenant's SKUs, with a status filter and a Deactivate or Reactivate button on every row. A SKU still on an open ASN
 * or order, or in stock, refuses to switch off; an inactive one cannot be ordered or received.
 */
export function SkuList({ version, onChanged }: { version: number; onChanged: () => void }) {
  const { gateway } = useSession();
  const [status, setStatus] = useState('');
  const [reload, setReload] = useState(0);
  const skus = useLoad(() => gateway.skus(status), `${version}:${status}:${reload}`);

  return (
    <>
      <StatusFilter testId="sku-status-filter" value={status} onChange={setStatus} />
      {skus.error && <ErrorBanner message={skus.error} />}
      <div className="table-scroll"><table>
        <thead><tr><th>SKU</th><th>Description</th><th>Unit</th><th>Status</th><th /></tr></thead>
        <tbody>
          {(skus.data ?? []).map((s) => (
            <tr key={s.id} data-testid="sku-row" data-id={s.id}>
              <td>{s.code}</td><td>{s.description}</td><td>{s.uom}</td>
              <td>{active(s) ? 'Active' : 'Inactive'}</td>
              <td>
                <ConfigActions kind="skus" id={s.id} active={active(s)} label={`SKU ${s.code}`} version={s.version}
                  onChanged={() => { setReload((n) => n + 1); onChanged(); }} />
              </td>
            </tr>
          ))}
        </tbody>
      </table></div>
    </>
  );
}
