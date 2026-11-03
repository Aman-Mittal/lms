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
import { PARKED_STATUSES, type ParkedFilter, type ParkedStatus } from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { RowTable } from './RowTable';

/**
 * Events whose listener failed on every retry and were parked. An operator
 * reads what failed, replays it once the cause is fixed, or discards it with
 * a reason. Only the caller's own company's events are listed.
 */
export function ParkedEvents() {
  const { gateway, can } = useSession();
  const [status, setStatus] = useState<ParkedStatus | ''>('PARKED');
  const [eventType, setEventType] = useState('');
  const [listenerId, setListenerId] = useState('');
  const [filter, setFilter] = useState<ParkedFilter>({ status: 'PARKED' });
  const [picked, setPicked] = useState('');
  const [reload, setReload] = useState(0);
  const list = useLoad(() => gateway.parkedEvents(filter), `${JSON.stringify(filter)}:${reload}`);

  function apply(event: FormEvent) {
    event.preventDefault();
    setPicked('');
    setFilter({ status: status || undefined, eventType: eventType.trim(), listenerId: listenerId.trim() });
  }

  return (
    <>
      <h1>Parked events</h1>
      <p className="muted">Events whose listener kept failing after every retry. Replay one once its cause is fixed.</p>
      <form className="inline" onSubmit={apply} data-testid="parked-filter">
        <select aria-label="Status" data-testid="parked-status" value={status}
          onChange={(e) => setStatus(e.target.value as ParkedStatus | '')}>
          <option value="">Any status</option>
          {PARKED_STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <input placeholder="Event type" aria-label="Event type" data-testid="parked-type" value={eventType}
          onChange={(e) => setEventType(e.target.value)} />
        <input placeholder="Listener" aria-label="Listener" data-testid="parked-listener" value={listenerId}
          onChange={(e) => setListenerId(e.target.value)} />
        <button type="submit">Filter</button>
      </form>
      {list.loading && <p className="muted">Loading…</p>}
      {list.error && <p className="error" role="alert">{list.error}</p>}
      {list.data && list.data.length === 0 && (
        <p className="muted" data-testid="no-parked">Nothing is parked that matches.</p>
      )}
      {list.data && list.data.length > 0 && (
        <RowTable testId="parked" rows={list.data} picked={picked} onPick={(r) => setPicked(r.id)} columns={[
          { header: '#', field: 'parkedNo' }, { header: 'Event', field: 'eventType' },
          { header: 'Listener', field: 'listenerId' }, { header: 'Status', field: 'status' },
          { header: 'Attempts', field: 'attempts' }, { header: 'Parked', field: 'parkedAt' },
        ]} />
      )}
      {picked && (
        <Detail key={`${picked}:${reload}`} id={picked} manage={can('EVENT_DEAD_LETTER_MANAGE')}
          onChanged={() => setReload((n) => n + 1)} />
      )}
    </>
  );
}

function Detail({ id, manage, onChanged }: { id: string; manage: boolean; onChanged: () => void }) {
  const { gateway } = useSession();
  const detail = useLoad(() => gateway.parkedEvent(id), id);
  const [reason, setReason] = useState('');
  const [outcome, setOutcome] = useState('');
  const [error, setError] = useState('');

  async function replay() {
    setError('');
    try {
      const r = await gateway.replayParkedEvent(id);
      setOutcome(`${r.outcome} after ${r.attempts} attempts`);
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function discard(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      await gateway.discardParkedEvent(id, reason.trim());
      setOutcome('DISCARDED');
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  if (detail.error) {
    return <p className="error" role="alert">{detail.error}</p>;
  }
  if (!detail.data) {
    return <p className="muted">Loading…</p>;
  }
  const s = detail.data.summary;
  return (
    <section data-testid="parked-detail">
      <h2>Parked event {s.parkedNo}</h2>
      <dl className="facts">
        <div><dt>Event</dt><dd>{s.eventType}</dd></div>
        <div><dt>Listener</dt><dd>{s.listenerId}</dd></div>
        <div><dt>Status</dt><dd data-testid="parked-detail-status">{s.status}</dd></div>
        <div><dt>Attempts</dt><dd>{s.attempts}</dd></div>
        <div><dt>Last failed</dt><dd>{s.lastFailedAt}</dd></div>
      </dl>
      <p className="muted" data-testid="parked-error">{s.lastError}</p>
      <pre data-testid="parked-payload">{detail.data.payload}</pre>
      {outcome && <p data-testid="parked-outcome">{outcome}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {manage && s.status === 'PARKED' && (
        <>
          <button type="button" data-testid="replay" onClick={replay}>Replay</button>
          <form className="inline" onSubmit={discard} data-testid="discard-form">
            <input placeholder="Reason" aria-label="Reason to discard" data-testid="discard-reason" value={reason}
              onChange={(e) => setReason(e.target.value)} required />
            <button type="submit" data-testid="discard">Discard</button>
          </form>
        </>
      )}
    </section>
  );
}
