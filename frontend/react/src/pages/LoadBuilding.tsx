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
import { ApiProblem, MAX_BULK_ASSIGNMENT, type Row } from '../gateway/lmsGateway';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { useLoad } from '../useLoad';

/** A load's states in which more freight may still go on. */
const BUILDABLE = ['DRAFT', 'PLANNED'];

/**
 * Builds a load: what is aboard, in drop order, with a printable manifest, and
 * the consignments still waiting, which are picked and put on together.
 */
export function LoadBuilding() {
  const { gateway } = useSession();
  const [typed, setTyped] = useState('');
  const [loadId, setLoadId] = useState('');
  const [reload, setReload] = useState(0);

  const build = useLoad(
    () => (loadId ? gateway.loadBuild(loadId) : Promise.resolve(null)),
    `${loadId}:${reload}`,
  );

  function open(event: FormEvent) {
    event.preventDefault();
    setLoadId(typed.trim().toLowerCase());
    setReload((n) => n + 1);
  }

  return (
    <>
      <h1>Load building</h1>
      <p className="muted">
        Open a load to see what is aboard, print its manifest, and put waiting consignments on it,
        up to {MAX_BULK_ASSIGNMENT} at a time.
      </p>
      <form className="inline" onSubmit={open} data-testid="open-load">
        <EntityPicker kind="load" placeholder="Load" ariaLabel="Load id" testId="load-id"
          value={typed} onChange={setTyped} required />
        <button type="submit">Open</button>
      </form>
      {build.error && <p className="error" role="alert">{build.error}</p>}
      {/* Keyed by the load alone, and kept on screen while it refreshes: what was just
          added, and the queue it was picked from, outlive the reload that follows. */}
      {build.data && build.data.load.id === loadId && (
        <LoadPanel key={loadId} load={build.data.load} aboard={build.data.aboard}
          onChanged={() => setReload((n) => n + 1)} />
      )}
    </>
  );
}

interface LoadPanelProps {
  load: Row;
  aboard: Row[];
  onChanged: () => void;
}

function LoadPanel({ load, aboard, onChanged }: LoadPanelProps) {
  const { can } = useSession();
  const [manifest, setManifest] = useState(false);
  const buildable = can('LOAD_CREATE') && BUILDABLE.includes(load.status);

  return (
    <section data-testid="load-panel">
      <h2>{load.loadNo}</h2>
      <dl>
        <div><dt>Status</dt><dd data-testid="load-status">{load.status}</dd></div>
        <div><dt>Vehicle</dt><dd>{load.vehicleRegistrationNo || '—'}</dd></div>
        <div>
          <dt>Weight</dt>
          <dd data-testid="load-weight">
            {load.plannedWeightKg} of {load.capacityWeightKg} kg ({load.weightUtilisationPct}%)
          </dd>
        </div>
        <div><dt>Consignments</dt><dd data-testid="load-count">{load.consignmentCount}</dd></div>
      </dl>
      <p>
        <button type="button" className="link" data-testid="print-manifest" onClick={() => setManifest(true)}
          disabled={aboard.length === 0}>
          Print manifest
        </button>
      </p>
      {aboard.length === 0 ? (
        <p className="muted" data-testid="nothing-aboard">Nothing is aboard yet.</p>
      ) : (
        <div className="table-scroll">
          <ConsignmentTable rows={aboard} testId="aboard-table" rowTestId="aboard-row" />
        </div>
      )}
      {manifest && (
        <section className="slip manifest" data-testid="manifest" aria-label="Load manifest">
          <h3>Manifest · {load.loadNo}</h3>
          <p>
            {load.vehicleRegistrationNo || 'No vehicle'} · {aboard.length} consignments ·{' '}
            {load.plannedWeightKg} kg
          </p>
          <ConsignmentTable rows={aboard} testId="manifest-table" rowTestId="manifest-row" />
          <p className="actions no-print">
            <button data-testid="print-now" onClick={() => window.print()}>Print</button>{' '}
            <button className="link" onClick={() => setManifest(false)}>Close</button>
          </p>
        </section>
      )}
      {buildable && <AddPanel loadId={load.id} onAdded={onChanged} />}
    </section>
  );
}

function ConsignmentTable({ rows, testId, rowTestId }: { rows: Row[]; testId: string; rowTestId: string }) {
  return (
    <table data-testid={testId}>
      <thead>
        <tr><th>#</th><th>Tracking ref</th><th>Consignee</th><th>Destination</th><th>Weight kg</th><th>Classes</th><th>Hazmat</th></tr>
      </thead>
      <tbody>
        {rows.map((c, i) => (
          <tr key={c.id} data-testid={rowTestId}>
            <td>{i + 1}</td>
            <td>{c.trackingRef}</td>
            <td>{c.consigneeName}</td>
            <td>{c.destinationTerminalCode}</td>
            <td>{c.totalDeadWeightKg}</td>
            <td>{c.materialClasses}</td>
            <td>{c.containsHazmat}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function AddPanel({ loadId, onAdded }: { loadId: string; onAdded: () => void }) {
  const { gateway } = useSession();
  const [orderTyped, setOrderTyped] = useState('');
  const [rows, setRows] = useState<Row[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [searched, setSearched] = useState(false);
  // Insertion-ordered: consignments board in the order they were picked.
  const [picked, setPicked] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [refusals, setRefusals] = useState<Array<[string, string]>>([]);
  const [added, setAdded] = useState(0);

  async function find(event: FormEvent) {
    event.preventDefault();
    setError('');
    setPicked([]);
    try {
      const page = await gateway.unassignedConsignments(orderTyped.trim() || undefined);
      setRows(page.rows);
      setNextCursor(page.nextCursor);
      setSearched(true);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function more() {
    if (!nextCursor) {
      return;
    }
    try {
      const page = await gateway.unassignedConsignments(orderTyped.trim() || undefined, nextCursor);
      setRows((shown) => [...shown, ...page.rows]);
      setNextCursor(page.nextCursor);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function toggle(id: string) {
    setPicked((now) => (now.includes(id) ? now.filter((p) => p !== id) : [...now, id]));
  }

  const allPicked = rows.length > 0 && rows.every((r) => picked.includes(r.id));

  function toggleAll() {
    setPicked(allPicked ? [] : rows.map((r) => r.id));
  }

  async function add() {
    setError('');
    setRefusals([]);
    setAdded(0);
    try {
      await gateway.assignConsignments(loadId, picked);
      setAdded(picked.length);
      setPicked([]);
      setRows((shown) => shown.filter((r) => !picked.includes(r.id)));
      onAdded();
    } catch (e) {
      setError((e as Error).message);
      if (e instanceof ApiProblem) {
        setRefusals(Object.entries(e.errors));
      }
    }
  }

  return (
    <section data-testid="add-panel">
      <h3>Add consignments</h3>
      <form className="inline" onSubmit={find} data-testid="find-unassigned">
        <EntityPicker kind="order" placeholder="Order (optional)" ariaLabel="Order id" testId="order-filter"
          value={orderTyped} onChange={setOrderTyped} />
        <button type="submit">Find waiting consignments</button>
      </form>
      {added > 0 && <p className="ok" data-testid="bulk-added">Added {added} consignments to the load.</p>}
      {error && <p className="error" role="alert" data-testid="bulk-error">{error}</p>}
      {refusals.length > 0 && (
        <ul data-testid="bulk-refusals">
          {refusals.map(([ref, why]) => (
            <li key={ref} data-testid="refusal"><strong>{ref}</strong>: {why}</li>
          ))}
        </ul>
      )}
      {searched && rows.length === 0 && (
        <p className="muted" data-testid="queue-empty">No consignments are waiting.</p>
      )}
      {rows.length > 0 && (
        <>
          <p className="actions">
            <button type="button" data-testid="add-selected" onClick={add}
              disabled={picked.length === 0 || picked.length > MAX_BULK_ASSIGNMENT}>
              Add selected ({picked.length})
            </button>
            {picked.length > MAX_BULK_ASSIGNMENT && (
              <span className="error"> At most {MAX_BULK_ASSIGNMENT} at a time.</span>
            )}
          </p>
          <div className="table-scroll">
            <table data-testid="queue-table">
              <thead>
                <tr>
                  <th>
                    <input type="checkbox" aria-label="Select all shown" data-testid="select-all"
                      checked={allPicked} onChange={toggleAll} />
                  </th>
                  <th>Tracking ref</th><th>Consignee</th><th>Destination</th><th>Weight kg</th><th>Classes</th><th>Hazmat</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((c) => (
                  <tr key={c.id} data-testid="queue-row">
                    <td>
                      <input type="checkbox" aria-label={`Select ${c.trackingRef}`} data-testid="pick"
                        checked={picked.includes(c.id)} onChange={() => toggle(c.id)} />
                    </td>
                    <td>{c.trackingRef}</td>
                    <td>{c.consigneeName}</td>
                    <td>{c.destinationTerminalCode}</td>
                    <td>{c.totalDeadWeightKg}</td>
                    <td>{c.materialClasses}</td>
                    <td>{c.containsHazmat}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {nextCursor && (
            <p><button type="button" className="link" data-testid="more-unassigned" onClick={more}>Show more</button></p>
          )}
        </>
      )}
    </section>
  );
}
