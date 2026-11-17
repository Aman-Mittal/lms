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
import { ConfigActions } from './ConfigActions';
import { StatusFilter } from './StatusFilter';

/**
 * The vendors on a routing guide, in rank order, with the status filter (the active ones by default) and a Deactivate
 * or Reactivate button on each. Allocation skips an inactive vendor and offers to the next rank; a vendor holding an
 * unanswered offer on the guide refuses to switch off. `version` is bumped by the guide's panel when it changes the guide.
 */
export function GuideVendors({ guideId, manage, version }: { guideId: string; manage: boolean; version: number }) {
  const { gateway } = useSession();
  const [status, setStatus] = useState('');
  const [reload, setReload] = useState(0);
  const loaded = useLoad(async () => (await gateway.routingGuide(guideId, status)).entries, `${guideId}:${version}:${status}:${reload}`);
  const rows = loaded.data ?? [];

  return (
    <>
      <StatusFilter value={status} onChange={setStatus} />
      {!loaded.loading && rows.length === 0 ? (
        <p className="muted" data-testid="no-vendors">{status ? 'No vendor matches.' : 'No vendor on this guide yet.'}</p>
      ) : (
        <div className="table-wrap"><table>
          <thead><tr><th>Rank</th><th>Vendor</th><th>Answers within (min)</th><th>Agreed rate</th><th>Status</th><th /></tr></thead>
          <tbody>
            {rows.map((e) => (
              <tr key={e.id} data-testid="guide-vendor" data-id={e.id}>
                <td>{e.rankNo}</td><td>{e.vendorName}</td><td>{e.responseSlaMinutes}</td><td>{e.agreedRate}</td>
                <td>{e.active ? 'Active' : 'Inactive'}</td>
                <td>
                  {manage && (
                    <ConfigActions kind="guideVendors" id={e.id} parentId={guideId} active={!!e.active} label={e.vendorName}
                      version={e.version} onChanged={() => setReload((n) => n + 1)} />
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}
    </>
  );
}
