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

import { lazy, Suspense, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import {
  ApiProblem, EXCEPTION_SEVERITIES, EXCEPTION_STATUSES, MANUAL_EXCEPTION_CATEGORIES, type Row,
} from '../gateway/lmsGateway';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { useLoad } from '../useLoad';

// The map is Leaflet, the console's largest dependency: loaded only when the fleet is shown.
const MapView = lazy(() => import('./MapView').then((m) => ({ default: m.MapView })));

/** A refusal's message, and its problem code when it had one. */
interface Refusal {
  message: string;
  code: string;
}

function refusalOf(e: unknown): Refusal {
  return { message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' };
}

function RefusalNote({ refusal }: { refusal: Refusal | null }) {
  if (!refusal) {
    return null;
  }
  return (
    <p className="error" role="alert">
      {refusal.code && <code data-testid="exception-refusal">{refusal.code}</code>} {refusal.message}
    </p>
  );
}

function Table({ rows, columns, rowTestId }: { rows: Row[]; columns: Array<[string, string]>; rowTestId: string }) {
  return (
    <table>
      <thead>
        <tr>{columns.map(([field, header]) => <th key={field}>{header}</th>)}</tr>
      </thead>
      <tbody>
        {rows.map((row, i) => (
          <tr key={row.id || i} data-testid={rowTestId}>
            {columns.map(([field]) => <td key={field}>{row[field]}</td>)}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * The control tower's working desk: tickets taken through their steps, what the
 * detectors tolerate, the watch a trip is held to, and where the fleet is.
 */
export function ExceptionDesk() {
  const { can } = useSession();
  const [ticketId, setTicketId] = useState('');
  const [tripId, setTripId] = useState('');
  const [listed, setListed] = useState(0);

  return (
    <>
      <h1>Exception desk</h1>
      <p className="muted">
        Take a ticket from raised to closed, tune the detectors, hold a trip to an arrival time or a
        temperature band, and see which vehicles are where.
      </p>
      <TicketList onOpen={setTicketId} reload={listed} />
      {can('EXCEPTION_RAISE') && <RaiseTicket onRaised={(id) => { setTicketId(id); setListed((n) => n + 1); }} />}
      {ticketId && (
        <TicketPanel key={ticketId} ticketId={ticketId} onChanged={() => setListed((n) => n + 1)}
          onClose={() => setTicketId('')} onTrip={setTripId} />
      )}
      <DetectorSettings />
      <TripDesk tripId={tripId} onTrip={setTripId} />
      <Fleet onTrip={setTripId} />
    </>
  );
}

function TicketList({ onOpen, reload }: { onOpen: (id: string) => void; reload: number }) {
  const { gateway } = useSession();
  const [status, setStatus] = useState('');
  const [severity, setSeverity] = useState('');
  const [typed, setTyped] = useState('');
  const tickets = useLoad(() => gateway.exceptionTickets({ status, severity }), `${status}:${severity}:${reload}`);

  function open(event: FormEvent) {
    event.preventDefault();
    onOpen(typed.trim().toLowerCase());
  }

  return (
    <section data-testid="ticket-list">
      <h2>Tickets</h2>
      <form className="inline" onSubmit={open} data-testid="open-ticket">
        <EntityPicker kind="ticket" placeholder="Ticket" ariaLabel="Ticket id" testId="ticket-id"
          value={typed} onChange={setTyped} required />
        <button type="submit">Open</button>
      </form>
      <div className="inline">
        <label>
          Status{' '}
          <select data-testid="filter-status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">Any</option>
            {EXCEPTION_STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>{' '}
        <label>
          Severity{' '}
          <select data-testid="filter-severity" value={severity} onChange={(e) => setSeverity(e.target.value)}>
            <option value="">Any</option>
            {EXCEPTION_SEVERITIES.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
      </div>
      {tickets.error && <p className="error" role="alert">{tickets.error}</p>}
      {tickets.data && tickets.data.length === 0 ? (
        <p className="muted" data-testid="no-tickets">No ticket matches.</p>
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr><th>Ticket</th><th>Severity</th><th>Status</th><th>Category</th><th>About</th><th>Assignee</th></tr>
            </thead>
            <tbody>
              {(tickets.data ?? []).map((t) => (
                <tr key={t.id} data-testid="ticket-row" tabIndex={0} onClick={() => onOpen(t.id)}
                  onKeyDown={(e) => { if (e.key === 'Enter') { onOpen(t.id); } }}>
                  <td>{t.ticketNo}</td><td>{t.severity}</td><td>{t.status}</td><td>{t.category}</td>
                  <td>{t.tripNo || t.subjectLabel}</td><td>{t.assigneeName}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function RaiseTicket({ onRaised }: { onRaised: (id: string) => void }) {
  const { gateway } = useSession();
  const [tripId, setTripId] = useState('');
  const [category, setCategory] = useState<string>(MANUAL_EXCEPTION_CATEGORIES[0]);
  const [description, setDescription] = useState('');
  const [refusal, setRefusal] = useState<Refusal | null>(null);

  async function raise(event: FormEvent) {
    event.preventDefault();
    setRefusal(null);
    try {
      onRaised(await gateway.raiseException({ tripId, category, description }));
      setDescription('');
    } catch (e) {
      setRefusal(refusalOf(e));
    }
  }

  return (
    <section>
      <h2>Raise a ticket</h2>
      <form className="inline" onSubmit={raise} data-testid="raise-ticket">
        <EntityPicker kind="trip" placeholder="Trip" ariaLabel="Trip id of the ticket"
          value={tripId} onChange={setTripId} required />
        <select aria-label="Category" value={category} onChange={(e) => setCategory(e.target.value)}>
          {MANUAL_EXCEPTION_CATEGORIES.map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
        <input placeholder="What happened" aria-label="What happened" value={description}
          onChange={(e) => setDescription(e.target.value)} required />
        <button type="submit">Raise</button>
      </form>
      <RefusalNote refusal={refusal} />
    </section>
  );
}

interface TicketPanelProps {
  ticketId: string;
  onChanged: () => void;
  onClose: () => void;
  onTrip: (tripId: string) => void;
}

/** One ticket, its history, and the step its status and the caller's rights allow next. */
function TicketPanel({ ticketId, onChanged, onClose, onTrip }: TicketPanelProps) {
  const { gateway, can } = useSession();
  const [version, setVersion] = useState(0);
  const ticket = useLoad(() => gateway.exceptionTicket(ticketId), version);
  const history = useLoad(() => gateway.exceptionHistory(ticketId), version);
  const users = useLoad(() => (can('EXCEPTION_MANAGE') ? gateway.userChoices() : Promise.resolve([])), 'users');
  const [assignee, setAssignee] = useState('');
  const [text, setText] = useState('');
  const [refusal, setRefusal] = useState<Refusal | null>(null);
  const [done, setDone] = useState('');

  async function step(event: FormEvent | null, label: string, act: () => Promise<void>) {
    event?.preventDefault();
    setRefusal(null);
    setDone('');
    try {
      await act();
      setText('');
      setDone(label);
      onChanged();
    } catch (e) {
      setRefusal(refusalOf(e));
    }
    setVersion((n) => n + 1);
  }

  if (ticket.error) {
    return <p className="error" role="alert">{ticket.error}</p>;
  }
  if (!ticket.data) {
    return null;
  }
  const t = ticket.data;
  const manage = can('EXCEPTION_MANAGE');
  const close = can('EXCEPTION_CLOSE');

  return (
    <section data-testid="ticket-panel" aria-label="Ticket">
      <header>
        <h3>{t.ticketNo}</h3>
        <button className="link" onClick={onClose} aria-label="Close ticket">Close</button>
      </header>
      <dl className="facts">
        <div><dt>Status</dt><dd data-testid="ticket-status">{t.status}</dd></div>
        <div><dt>Severity</dt><dd data-testid="ticket-severity">{t.severity}</dd></div>
        <div><dt>Category</dt><dd>{t.category}</dd></div>
        <div><dt>Source</dt><dd>{t.source}</dd></div>
        <div><dt>About</dt><dd>{t.tripNo || t.subjectLabel}</dd></div>
        <div><dt>Vehicle</dt><dd>{t.registrationNo || '—'}</dd></div>
        <div><dt>Assignee</dt><dd data-testid="ticket-assignee">{t.assigneeName || '—'}</dd></div>
        <div><dt>Holds settlement</dt><dd>{t.suspendsSettlement || 'No'}</dd></div>
        <div><dt>Resolution</dt><dd data-testid="ticket-resolution">{t.resolution || '—'}</dd></div>
      </dl>
      <p>{t.description}</p>
      {t.tripId && (
        <p>
          <button className="link" data-testid="ticket-trip" onClick={() => onTrip(t.tripId)}>Watch this trip</button>
          {' · '}
          <Link to={`/trip-route?trip=${t.tripId}`} data-testid="ticket-trip-route">Show the route</Link>
        </p>
      )}

      {manage && (t.status === 'RAISED' || t.status === 'ASSIGNED') && (
        <form className="inline" data-testid="assign-ticket"
          onSubmit={(e) => step(e, 'Assigned.', () => gateway.assignException(ticketId, assignee, text))}>
          <select aria-label="Assignee" data-testid="assignee" value={assignee}
            onChange={(e) => setAssignee(e.target.value)} required>
            <option value="">Choose who takes it</option>
            {(users.data ?? []).map((u) => <option key={u.id} value={u.id}>{u.label}</option>)}
          </select>
          <input placeholder="Note" aria-label="Assignment note" value={text} onChange={(e) => setText(e.target.value)} />
          <button type="submit">Assign</button>
        </form>
      )}
      {manage && t.status === 'ASSIGNED' && (
        <p><button data-testid="act-investigate"
          onClick={() => step(null, 'Investigation started.', () => gateway.investigateException(ticketId, ''))}>
          Start investigating
        </button></p>
      )}
      {manage && t.status === 'UNDER_INVESTIGATION' && (
        <form className="inline" data-testid="resolve-ticket"
          onSubmit={(e) => step(e, 'Resolved.', () => gateway.resolveException(ticketId, text))}>
          <input placeholder="How it was resolved" aria-label="Resolution" value={text}
            onChange={(e) => setText(e.target.value)} required />
          <button type="submit">Resolve</button>
        </form>
      )}
      {close && t.status === 'RESOLVED' && (
        <>
          <form className="inline" data-testid="close-ticket"
            onSubmit={(e) => step(e, 'Closed.', () => gateway.closeException(ticketId, text))}>
            <input placeholder="Closing note" aria-label="Closing note" value={text}
              onChange={(e) => setText(e.target.value)} />
            <button type="submit">Close ticket</button>
          </form>
          <ReopenForm onReopen={(reason) => step(null, 'Reopened.', () => gateway.reopenException(ticketId, reason))} />
        </>
      )}
      {done && <p data-testid="ticket-done">{done}</p>}
      <RefusalNote refusal={refusal} />
      {refusal && (
        <p><button className="link" data-testid="ticket-reload" onClick={() => setVersion((n) => n + 1)}>Reload</button></p>
      )}

      <h4>History</h4>
      {history.error && <p className="error" role="alert">{history.error}</p>}
      <Table rows={history.data ?? []} rowTestId="history-row" columns={[
        ['occurredAt', 'When'], ['action', 'Step'], ['fromStatus', 'From'], ['toStatus', 'To'], ['actor', 'By'],
        ['note', 'Note'],
      ]} />
    </section>
  );
}

function ReopenForm({ onReopen }: { onReopen: (reason: string) => Promise<void> }) {
  const [reason, setReason] = useState('');
  return (
    <form className="inline" data-testid="reopen-ticket"
      onSubmit={(e) => { e.preventDefault(); void onReopen(reason).then(() => setReason('')); }}>
      <input placeholder="Why it is not resolved" aria-label="Reason to reopen" value={reason}
        onChange={(e) => setReason(e.target.value)} required />
      <button type="submit">Reopen</button>
    </form>
  );
}

function DetectorSettings() {
  const { gateway, can } = useSession();
  const [version, setVersion] = useState(0);
  const settings = useLoad(() => gateway.detectorSettings(), version);
  const [eta, setEta] = useState('');
  const [excursion, setExcursion] = useState('');
  const [refusal, setRefusal] = useState<Refusal | null>(null);
  const [saved, setSaved] = useState(false);

  async function save(event: FormEvent) {
    event.preventDefault();
    setRefusal(null);
    setSaved(false);
    try {
      await gateway.saveDetectorSettings(
        Number(eta || settings.data?.etaSlipMinutes), Number(excursion || settings.data?.temperatureExcursionMinutes));
      setSaved(true);
      setEta('');
      setExcursion('');
      setVersion((n) => n + 1);
    } catch (e) {
      setRefusal(refusalOf(e));
    }
  }

  return (
    <section data-testid="detector-settings">
      <h2>Detectors</h2>
      {settings.error && <p className="error" role="alert">{settings.error}</p>}
      {settings.data && (
        <dl className="facts">
          <div><dt>ETA slip allowed</dt><dd data-testid="eta-slip">{settings.data.etaSlipMinutes} min</dd></div>
          <div><dt>Temperature excursion allowed</dt>
            <dd data-testid="temperature-excursion">{settings.data.temperatureExcursionMinutes} min</dd></div>
          <div><dt>Settings</dt><dd data-testid="detector-customised">
            {settings.data.customised ? 'Set for this tenant' : 'Platform defaults'}</dd></div>
        </dl>
      )}
      {can('EXCEPTION_MANAGE') && settings.data && (
        <form className="inline" onSubmit={save} data-testid="save-detectors">
          <input type="number" min={1} max={10080} aria-label="ETA slip minutes" data-testid="eta-slip-input"
            placeholder={String(settings.data.etaSlipMinutes)} value={eta} onChange={(e) => setEta(e.target.value)} />
          <input type="number" min={1} max={10080} aria-label="Temperature excursion minutes"
            data-testid="temperature-excursion-input" placeholder={String(settings.data.temperatureExcursionMinutes)}
            value={excursion} onChange={(e) => setExcursion(e.target.value)} />
          <button type="submit">Save</button>
        </form>
      )}
      {saved && <p data-testid="detectors-saved">Detector settings saved.</p>}
      <RefusalNote refusal={refusal} />
    </section>
  );
}

/** One trip: how far it has got, the geofences it crossed, and the watch it is held to. */
function TripDesk({ tripId, onTrip }: { tripId: string; onTrip: (tripId: string) => void }) {
  const [typed, setTyped] = useState('');

  function open(event: FormEvent) {
    event.preventDefault();
    onTrip(typed.trim().toLowerCase());
  }

  return (
    <section data-testid="trip-desk">
      <h2>Trip watch</h2>
      <form className="inline" onSubmit={open} data-testid="open-trip">
        <EntityPicker kind="trip" placeholder="Trip" ariaLabel="Trip id to watch" testId="watch-trip-id"
          value={typed} onChange={setTyped} required />
        <button type="submit">Open trip</button>
      </form>
      {tripId && <TripPanel key={tripId} tripId={tripId} />}
    </section>
  );
}

function TripPanel({ tripId }: { tripId: string }) {
  const { gateway, can } = useSession();
  const [version, setVersion] = useState(0);
  const activity = useLoad(() => gateway.tripActivity(tripId), 'activity');
  const watch = useLoad(() => gateway.tripWatch(tripId), version);
  const [arrival, setArrival] = useState('');
  const [min, setMin] = useState('');
  const [max, setMax] = useState('');
  const [refusal, setRefusal] = useState<Refusal | null>(null);
  const [done, setDone] = useState('');

  async function act(label: string, call: () => Promise<void>) {
    setRefusal(null);
    setDone('');
    try {
      await call();
      setDone(label);
      setVersion((n) => n + 1);
    } catch (e) {
      setRefusal(refusalOf(e));
    }
  }

  const progress = activity.data?.progress;
  return (
    <section data-testid="trip-panel" aria-label="Trip">
      {activity.error && <p className="error" role="alert">{activity.error}</p>}
      {activity.data && !progress?.lastFixAt && (
        <p className="muted" data-testid="no-progress">No position has been reported for this trip yet.</p>
      )}
      {progress && (
        <dl className="facts">
          <div><dt>Trip</dt><dd data-testid="trip-no">{progress.tripNo}</dd></div>
          <div><dt>Status</dt><dd>{progress.status}</dd></div>
          <div><dt>Last fix</dt><dd>{progress.lastFixAt || '—'}</dd></div>
          <div><dt>Done</dt><dd>{progress.completionPct ? `${progress.completionPct} %` : '—'}</dd></div>
          <div><dt>Minutes left</dt><dd>{progress.estimatedMinutesRemaining || '—'}</dd></div>
          <div><dt>Off route</dt><dd>{progress.offRoute || 'No'}</dd></div>
        </dl>
      )}
      <p><Link to={`/trip-route?trip=${tripId}`} data-testid="trip-route-link">Show the route</Link></p>
      {activity.data && activity.data.crossings.length > 0 && (
        <Table rows={activity.data.crossings} rowTestId="crossing-row" columns={[
          ['occurredAt', 'When'], ['eventType', 'Crossing'], ['terminalCode', 'Site'],
        ]} />
      )}

      <h3>Watch</h3>
      {watch.error && <p className="error" role="alert">{watch.error}</p>}
      {watch.data === null && !watch.loading && !watch.error && (
        <p className="muted" data-testid="no-watch">This trip is not watched.</p>
      )}
      {watch.data && (
        <dl className="facts" data-testid="watch">
          <div><dt>Committed arrival</dt><dd data-testid="watch-arrival">{watch.data.committedArrivalAt || '—'}</dd></div>
          <div><dt>Band</dt><dd data-testid="watch-band">
            {watch.data.tempMinC ? `${watch.data.tempMinC} – ${watch.data.tempMaxC} °C` : '—'}</dd></div>
          <div><dt>Outside since</dt><dd>{watch.data.outsideRangeSince || '—'}</dd></div>
          <div><dt>Last reading</dt><dd>{watch.data.lastReadingAt || '—'}</dd></div>
        </dl>
      )}
      {can('EXCEPTION_MANAGE') && (
        <>
          <form className="inline" data-testid="set-watch" onSubmit={(e) => {
            e.preventDefault();
            void act('Watch saved.', () => gateway.setTripWatch(tripId,
              { committedArrivalAt: arrival, tempMinC: min, tempMaxC: max }));
          }}>
            <input type="datetime-local" aria-label="Committed arrival" data-testid="watch-arrival-input" value={arrival}
              onChange={(e) => setArrival(e.target.value)} />
            <input inputMode="decimal" placeholder="Min °C" aria-label="Minimum temperature" value={min}
              onChange={(e) => setMin(e.target.value)} />
            <input inputMode="decimal" placeholder="Max °C" aria-label="Maximum temperature" value={max}
              onChange={(e) => setMax(e.target.value)} />
            <button type="submit">Save watch</button>
          </form>
          {watch.data && (
            <p><button className="link" data-testid="clear-watch"
              onClick={() => act('Watch cleared.', () => gateway.clearTripWatch(tripId))}>Stop watching</button></p>
          )}
        </>
      )}
      {done && <p data-testid="watch-done">{done}</p>}
      <RefusalNote refusal={refusal} />
    </section>
  );
}

/** Where every vehicle last reported from, and the trips that have left their corridor. */
function Fleet({ onTrip }: { onTrip: (tripId: string) => void }) {
  const { gateway } = useSession();
  const [version, setVersion] = useState(0);
  const positions = useLoad(() => gateway.vehiclePositions(), version);
  const deviations = useLoad(() => gateway.openDeviations(), version);

  return (
    <section data-testid="fleet">
      <h2>Fleet</h2>
      <p><button className="link" data-testid="fleet-reload" onClick={() => setVersion((n) => n + 1)}>Refresh</button></p>
      <h3>Off route</h3>
      {deviations.error && <p className="error" role="alert">{deviations.error}</p>}
      {deviations.data && deviations.data.length === 0 ? (
        <p className="muted" data-testid="no-deviations">No trip is off its route.</p>
      ) : (
        <table>
          <thead>
            <tr><th>Trip</th><th>Since</th><th>Off by (m)</th><th>Corridor (m)</th><th /></tr>
          </thead>
          <tbody>
            {(deviations.data ?? []).map((d) => (
              <tr key={d.id} data-testid="deviation-row">
                <td>{d.tripNo}</td><td>{d.detectedAt}</td><td>{d.distanceM}</td><td>{d.corridorM}</td>
                <td><button className="link" onClick={() => onTrip(d.tripId)}>Open trip</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <h3>Positions</h3>
      {positions.error && <p className="error" role="alert">{positions.error}</p>}
      {positions.data && (
        <>
          <p className="muted" data-testid="position-count">{positions.data.rows.length} vehicles reporting</p>
          <Suspense fallback={<p className="muted">Loading…</p>}>
            <MapView features={positions.data.features} label="Where the fleet last reported from" />
          </Suspense>
        </>
      )}
    </section>
  );
}
