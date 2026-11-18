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
import type { LoadOpenDraft } from '../gateway/tripDeskTypes';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { ErrorBanner } from './ErrorBanner';

interface Refused {
  message: string;
  code: string;
}

const NONE: Refused = { message: '', code: '' };
const BLANK: LoadOpenDraft = { loadNo: '', originTerminalId: '', vehicleId: '', freightMode: '' };

function refusal(e: unknown): Refused {
  return { message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' };
}

/** Opens a new load for building: a number, where it starts, and the vehicle that will carry it. */
export function OpenLoadForm({ onOpened }: { onOpened: (loadId: string) => void }) {
  const { gateway, can, auth } = useSession();
  const [draft, setDraft] = useState<LoadOpenDraft>(BLANK);
  const [error, setError] = useState<Refused>(NONE);
  const set = (key: keyof LoadOpenDraft) => (value: string) => setDraft((d) => ({ ...d, [key]: value }));

  if (!can('LOAD_CREATE') || !auth?.orgUnitId) {
    return null;
  }
  const unit = auth.orgUnitId;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(NONE);
    try {
      const id = await gateway.openLoad(unit, draft);
      setDraft(BLANK);
      onOpened(id);
    } catch (e) {
      setError(refusal(e));
    }
  }

  return (
    <section data-testid="new-load">
      <h2>Open a new load</h2>
      <form className="inline" onSubmit={(ev) => void submit(ev)} data-testid="new-load-form">
        <input name="loadNo" placeholder="Load number" aria-label="New load number" data-testid="new-load-no" required
          value={draft.loadNo} onChange={(ev) => set('loadNo')(ev.target.value)} />
        <EntityPicker kind="terminal" placeholder="Origin terminal" ariaLabel="Origin terminal id"
          testId="new-load-origin" required value={draft.originTerminalId} onChange={set('originTerminalId')} />
        <EntityPicker kind="vehicle" placeholder="Vehicle" ariaLabel="Vehicle id" testId="new-load-vehicle" required
          value={draft.vehicleId} onChange={set('vehicleId')} />
        <select aria-label="Freight mode" data-testid="new-load-mode" value={draft.freightMode}
          onChange={(ev) => set('freightMode')(ev.target.value)}>
          <option value="">Full truckload</option>
          <option value="LTL">Part load</option>
        </select>
        <button type="submit" data-testid="new-load-open">Open load</button>
      </form>
      {error.message && <ErrorBanner message={error.message} code={error.code} testId="new-load-error" />}
    </section>
  );
}

/** Puts one named consignment on the load, for the single one a planner already knows by reference. */
export function AssignOne({ loadId, onBoarded }: { loadId: string; onBoarded: () => void }) {
  const { gateway } = useSession();
  const [consignmentId, setConsignmentId] = useState('');
  const [done, setDone] = useState(false);
  const [error, setError] = useState<Refused>(NONE);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setDone(false);
    setError(NONE);
    try {
      await gateway.assignConsignments(loadId, [consignmentId.trim()]);
      setConsignmentId('');
      setDone(true);
      onBoarded();
    } catch (e) {
      setError(refusal(e));
    }
  }

  return (
    <>
      <form className="inline" onSubmit={(ev) => void submit(ev)} data-testid="assign-one-form">
        <EntityPicker kind="unassigned" placeholder="Waiting consignment" ariaLabel="Consignment id"
          testId="assign-one-id" required value={consignmentId} onChange={setConsignmentId} />
        <button type="submit" data-testid="assign-one">Add to load</button>
      </form>
      {done && <p className="ok" role="status" data-testid="assign-one-done">Consignment added to the load.</p>}
      {error.message && <ErrorBanner message={error.message} code={error.code} testId="assign-one-error" />}
    </>
  );
}
