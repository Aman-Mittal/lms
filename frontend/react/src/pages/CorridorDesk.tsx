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
import { ApiProblem } from '../gateway/lmsGateway';
import type { CorridorDraft, CorridorRow } from '../gateway/smallOpsTypes';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { ErrorBanner } from './ErrorBanner';

const BLANK: CorridorDraft = { originTerminalId: '', destinationTerminalId: '', name: '', points: '', widthMetres: '' };

/**
 * The route corridors of the lanes: a trip on a lane with a corridor is measured against its polyline instead of the
 * straight line between its terminals. Import replaces a lane's corridor; clearing it takes the lane back to the
 * straight line.
 */
export function CorridorDesk() {
  const { gateway } = useSession();
  const [reload, setReload] = useState(0);
  const list = useLoad(() => gateway.corridors(), reload);
  const [draft, setDraft] = useState<CorridorDraft>(BLANK);
  const [done, setDone] = useState('');
  const [error, setError] = useState({ message: '', code: '' });
  const rows = list.data ?? [];
  const set = <K extends keyof CorridorDraft>(key: K, value: CorridorDraft[K]) => setDraft((d) => ({ ...d, [key]: value }));

  async function run(action: () => Promise<string>) {
    setDone('');
    setError({ message: '', code: '' });
    try {
      const said = await action();
      setReload((n) => n + 1);
      setDone(said);
    } catch (e) {
      setError({ message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' });
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    const name = draft.name.trim();
    void run(async () => {
      await gateway.importCorridor(draft);
      setDraft(BLANK);
      return `Corridor ${name} imported.`;
    });
  }

  function clear(c: CorridorRow) {
    void run(async () => {
      await gateway.clearCorridor(c.id);
      return `Corridor ${c.name} cleared.`;
    });
  }

  return (
    <section data-testid="corridors">
      <h3>Route corridors</h3>
      {list.error && <ErrorBanner message={list.error} />}
      {rows.length === 0 && list.data && (
        <p className="muted" data-testid="no-corridors">No lane has a corridor: its trips are measured against the straight line.</p>
      )}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table data-testid="corridor-list">
            <thead><tr><th>Name</th><th>From</th><th>To</th><th>Points</th><th>Width (m)</th><th></th></tr></thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id} data-testid="corridor-row" data-id={c.id}>
                  <td data-testid="corridor-name">{c.name}</td><td>{c.origin}</td><td>{c.destination}</td>
                  <td>{c.pointCount}</td><td>{c.widthMetres || '—'}</td>
                  <td>
                    <button type="button" className="link" data-testid="corridor-clear" aria-label={`Clear corridor ${c.name}`}
                      onClick={() => clear(c)}>Clear corridor</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <form data-testid="corridor-form" onSubmit={submit}>
        <EntityPicker kind="terminal" placeholder="Origin terminal" ariaLabel="Corridor origin terminal id"
          testId="corridor-origin" required value={draft.originTerminalId} onChange={(v) => set('originTerminalId', v)} />
        <EntityPicker kind="terminal" placeholder="Destination terminal" ariaLabel="Corridor destination terminal id"
          testId="corridor-destination" required value={draft.destinationTerminalId}
          onChange={(v) => set('destinationTerminalId', v)} />
        <input placeholder="Corridor name" aria-label="Corridor name" data-testid="corridor-name-input" required
          value={draft.name} onChange={(e) => set('name', e.target.value)} />
        <input inputMode="decimal" placeholder="Width in metres (optional)" aria-label="Corridor width in metres"
          data-testid="corridor-width" value={draft.widthMetres} onChange={(e) => set('widthMetres', e.target.value)} />
        <textarea rows={5} placeholder="One point per line: longitude, latitude" aria-label="Corridor points"
          data-testid="corridor-points" required value={draft.points} onChange={(e) => set('points', e.target.value)} />
        <button type="submit" data-testid="corridor-import">Import corridor</button>
      </form>
      {done && <p className="ok" role="status" data-testid="corridor-done">{done}</p>}
      {error.message && <ErrorBanner message={error.message} code={error.code} testId="corridor-error" codeTestId="corridor-refusal" />}
    </section>
  );
}
