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
import { Component, OnInit, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  ApiProblem, Choice, DetectorSettingsView, EXCEPTION_SEVERITIES, EXCEPTION_STATUSES, LmsGateway,
  MANUAL_EXCEPTION_CATEGORIES, Mapped, Row, TripActivity, TripWatchView,
} from '../gateway/lms-gateway';
import { Session } from '../session';
import { MapView } from './map-view';

/** A refusal's message, and its problem code when it had one. */
interface Refusal {
  message: string;
  code: string;
}

function refusalOf(e: unknown): Refusal {
  return { message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' };
}

/** A refusal, with its problem code first so the reason can be named. */
@Component({
  selector: 'app-refusal',
  template: `
    @if (refusal(); as r) {
      <p class="error" role="alert">
        @if (r.code) { <code data-testid="exception-refusal">{{ r.code }}</code> }
        {{ r.message }}
      </p>
    }
  `,
})
export class RefusalNote {
  readonly refusal = input<Refusal | null>(null);
}

const HISTORY_COLUMNS: Array<[string, string]> = [
  ['occurredAt', 'When'], ['action', 'Step'], ['fromStatus', 'From'], ['toStatus', 'To'], ['actor', 'By'], ['note', 'Note'],
];

/** One ticket, its history, and the step its status and the caller's rights allow next. */
@Component({
  selector: 'app-ticket-panel',
  imports: [FormsModule, RouterLink, RefusalNote],
  template: `
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (ticket(); as t) {
      <section data-testid="ticket-panel" aria-label="Ticket">
        <header>
          <h3>{{ t['ticketNo'] }}</h3>
          <button class="link" (click)="closed.emit()" aria-label="Close ticket">Close</button>
        </header>
        <dl class="facts">
          <div><dt>Status</dt><dd data-testid="ticket-status">{{ t['status'] }}</dd></div>
          <div><dt>Severity</dt><dd data-testid="ticket-severity">{{ t['severity'] }}</dd></div>
          <div><dt>Category</dt><dd>{{ t['category'] }}</dd></div>
          <div><dt>Source</dt><dd>{{ t['source'] }}</dd></div>
          <div><dt>About</dt><dd>{{ t['tripNo'] || t['subjectLabel'] }}</dd></div>
          <div><dt>Vehicle</dt><dd>{{ t['registrationNo'] || '—' }}</dd></div>
          <div><dt>Assignee</dt><dd data-testid="ticket-assignee">{{ t['assigneeName'] || '—' }}</dd></div>
          <div><dt>Holds settlement</dt><dd>{{ t['suspendsSettlement'] || 'No' }}</dd></div>
          <div><dt>Resolution</dt><dd data-testid="ticket-resolution">{{ t['resolution'] || '—' }}</dd></div>
        </dl>
        <p>{{ t['description'] }}</p>
        @if (t['tripId']) {
          <p>
            <button class="link" data-testid="ticket-trip" (click)="trip.emit(t['tripId'])">Watch this trip</button>
            ·
            <a [routerLink]="['/trip-route']" [queryParams]="{ trip: t['tripId'] }" data-testid="ticket-trip-route">Show the route</a>
          </p>
        }

        @if (session.can('EXCEPTION_MANAGE') && (t['status'] === 'RAISED' || t['status'] === 'ASSIGNED')) {
          <form class="inline" data-testid="assign-ticket" (ngSubmit)="assign()">
            <select name="assignee" aria-label="Assignee" data-testid="assignee" required
                    [value]="assignee()" (change)="assignee.set($any($event.target).value)">
              <option value="">Choose who takes it</option>
              @for (u of users(); track u.id) { <option [value]="u.id">{{ u.label }}</option> }
            </select>
            <input name="assignNote" placeholder="Note" aria-label="Assignment note"
                   [value]="note()" (input)="note.set($any($event.target).value)" />
            <button type="submit">Assign</button>
          </form>
        }
        @if (session.can('EXCEPTION_MANAGE') && t['status'] === 'ASSIGNED') {
          <p><button data-testid="act-investigate" (click)="investigate()">Start investigating</button></p>
        }
        @if (session.can('EXCEPTION_MANAGE') && t['status'] === 'UNDER_INVESTIGATION') {
          <form class="inline" data-testid="resolve-ticket" (ngSubmit)="resolve()">
            <input name="resolution" placeholder="How it was resolved" aria-label="Resolution" required
                   [value]="note()" (input)="note.set($any($event.target).value)" />
            <button type="submit">Resolve</button>
          </form>
        }
        @if (session.can('EXCEPTION_CLOSE') && t['status'] === 'RESOLVED') {
          <form class="inline" data-testid="close-ticket" (ngSubmit)="close()">
            <input name="closeNote" placeholder="Closing note" aria-label="Closing note"
                   [value]="note()" (input)="note.set($any($event.target).value)" />
            <button type="submit">Close ticket</button>
          </form>
          <form class="inline" data-testid="reopen-ticket" (ngSubmit)="reopen()">
            <input name="reopenReason" placeholder="Why it is not resolved" aria-label="Reason to reopen" required
                   [value]="reason()" (input)="reason.set($any($event.target).value)" />
            <button type="submit">Reopen</button>
          </form>
        }
        @if (done()) { <p data-testid="ticket-done">{{ done() }}</p> }
        <app-refusal [refusal]="refusal()" />
        @if (refusal()) {
          <p><button class="link" data-testid="ticket-reload" (click)="read()">Reload</button></p>
        }

        <h4>History</h4>
        <table>
          <thead><tr>@for (c of historyColumns; track c[0]) { <th>{{ c[1] }}</th> }</tr></thead>
          <tbody>
            @for (row of history(); track $index) {
              <tr data-testid="history-row">@for (c of historyColumns; track c[0]) { <td>{{ row[c[0]] }}</td> }</tr>
            }
          </tbody>
        </table>
      </section>
    }
  `,
})
export class TicketPanel {
  private readonly gateway = inject(LmsGateway);
  readonly session = inject(Session);

  readonly ticketId = input.required<string>();
  readonly changed = output<void>();
  readonly closed = output<void>();
  readonly trip = output<string>();

  readonly historyColumns = HISTORY_COLUMNS;
  readonly ticket = signal<Row | null>(null);
  readonly history = signal<Row[]>([]);
  readonly users = signal<Choice[]>([]);
  readonly error = signal('');
  readonly assignee = signal('');
  readonly note = signal('');
  readonly reason = signal('');
  readonly refusal = signal<Refusal | null>(null);
  readonly done = signal('');

  constructor() {
    // A different ticket starts the panel afresh.
    effect(() => {
      this.ticketId();
      untracked(() => {
        this.refusal.set(null);
        this.done.set('');
        void this.read();
      });
    });
    if (this.session.can('EXCEPTION_MANAGE')) {
      this.gateway.userChoices().then((u) => this.users.set(u), () => this.users.set([]));
    }
  }

  async read(): Promise<void> {
    const id = this.ticketId();
    this.error.set('');
    try {
      const [ticket, history] = await Promise.all([this.gateway.exceptionTicket(id), this.gateway.exceptionHistory(id)]);
      this.ticket.set(ticket);
      this.history.set(history);
    } catch (e) {
      this.ticket.set(null);
      this.error.set((e as Error).message);
    }
  }

  assign(): Promise<void> {
    return this.step('Assigned.', () => this.gateway.assignException(this.ticketId(), this.assignee(), this.note()));
  }

  investigate(): Promise<void> {
    return this.step('Investigation started.', () => this.gateway.investigateException(this.ticketId(), ''));
  }

  resolve(): Promise<void> {
    return this.step('Resolved.', () => this.gateway.resolveException(this.ticketId(), this.note()));
  }

  close(): Promise<void> {
    return this.step('Closed.', () => this.gateway.closeException(this.ticketId(), this.note()));
  }

  reopen(): Promise<void> {
    return this.step('Reopened.', () => this.gateway.reopenException(this.ticketId(), this.reason()));
  }

  private async step(label: string, act: () => Promise<void>): Promise<void> {
    this.refusal.set(null);
    this.done.set('');
    try {
      await act();
      this.note.set('');
      this.reason.set('');
      this.done.set(label);
      this.changed.emit();
    } catch (e) {
      this.refusal.set(refusalOf(e));
    }
    await this.read();
  }
}

/** One trip: how far it has got, the geofences it crossed, and the watch it is held to. */
@Component({
  selector: 'app-trip-panel',
  imports: [FormsModule, RouterLink, RefusalNote],
  template: `
    <section data-testid="trip-panel" aria-label="Trip">
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      @if (activity(); as a) {
        @if (a.progress; as p) {
          <dl class="facts">
            <div><dt>Trip</dt><dd data-testid="trip-no">{{ p['tripNo'] }}</dd></div>
            <div><dt>Status</dt><dd>{{ p['status'] }}</dd></div>
            <div><dt>Last fix</dt><dd>{{ p['lastFixAt'] || '—' }}</dd></div>
            <div><dt>Done</dt><dd>{{ p['completionPct'] ? p['completionPct'] + ' %' : '—' }}</dd></div>
            <div><dt>Minutes left</dt><dd>{{ p['estimatedMinutesRemaining'] || '—' }}</dd></div>
            <div><dt>Off route</dt><dd>{{ p['offRoute'] || 'No' }}</dd></div>
          </dl>
        } @else {
          <p class="muted" data-testid="no-progress">No position has been reported for this trip yet.</p>
        }
      }
      <p><a [routerLink]="['/trip-route']" [queryParams]="{ trip: tripId() }" data-testid="trip-route-link">Show the route</a></p>
      @if (activity()?.crossings?.length) {
        <table>
          <thead><tr><th>When</th><th>Crossing</th><th>Site</th></tr></thead>
          <tbody>
            @for (c of activity()!.crossings; track c['id']) {
              <tr data-testid="crossing-row"><td>{{ c['occurredAt'] }}</td><td>{{ c['eventType'] }}</td><td>{{ c['terminalCode'] }}</td></tr>
            }
          </tbody>
        </table>
      }

      <h3>Watch</h3>
      @if (watchRead()) {
        @if (watch(); as w) {
          <dl class="facts" data-testid="watch">
            <div><dt>Committed arrival</dt><dd data-testid="watch-arrival">{{ w.committedArrivalAt || '—' }}</dd></div>
            <div><dt>Band</dt><dd data-testid="watch-band">{{ w.tempMinC ? w.tempMinC + ' – ' + w.tempMaxC + ' °C' : '—' }}</dd></div>
            <div><dt>Outside since</dt><dd>{{ w.outsideRangeSince || '—' }}</dd></div>
            <div><dt>Last reading</dt><dd>{{ w.lastReadingAt || '—' }}</dd></div>
          </dl>
        } @else {
          <p class="muted" data-testid="no-watch">This trip is not watched.</p>
        }
      }
      @if (session.can('EXCEPTION_MANAGE')) {
        <form class="inline" data-testid="set-watch" (ngSubmit)="saveWatch()">
          <input name="arrival" type="datetime-local" aria-label="Committed arrival" data-testid="watch-arrival-input"
                 [value]="arrival()" (input)="arrival.set($any($event.target).value)" />
          <input name="tempMin" inputmode="decimal" placeholder="Min °C" aria-label="Minimum temperature"
                 [value]="tempMin()" (input)="tempMin.set($any($event.target).value)" />
          <input name="tempMax" inputmode="decimal" placeholder="Max °C" aria-label="Maximum temperature"
                 [value]="tempMax()" (input)="tempMax.set($any($event.target).value)" />
          <button type="submit">Save watch</button>
        </form>
        @if (watch()) {
          <p><button class="link" data-testid="clear-watch" (click)="clearWatch()">Stop watching</button></p>
        }
      }
      @if (done()) { <p data-testid="watch-done">{{ done() }}</p> }
      <app-refusal [refusal]="refusal()" />
    </section>
  `,
})
export class TripPanel {
  private readonly gateway = inject(LmsGateway);
  readonly session = inject(Session);

  readonly tripId = input.required<string>();

  readonly activity = signal<TripActivity | null>(null);
  readonly watch = signal<TripWatchView | null>(null);
  readonly watchRead = signal(false);
  readonly error = signal('');
  readonly arrival = signal('');
  readonly tempMin = signal('');
  readonly tempMax = signal('');
  readonly refusal = signal<Refusal | null>(null);
  readonly done = signal('');

  constructor() {
    effect(() => {
      const id = this.tripId();
      untracked(() => {
        this.activity.set(null);
        this.watchRead.set(false);
        this.refusal.set(null);
        this.done.set('');
        this.gateway.tripActivity(id).then((a) => this.activity.set(a), (e) => this.error.set((e as Error).message));
        void this.readWatch();
      });
    });
  }

  saveWatch(): Promise<void> {
    return this.act('Watch saved.', () => this.gateway.setTripWatch(this.tripId(),
      { committedArrivalAt: this.arrival(), tempMinC: this.tempMin(), tempMaxC: this.tempMax() }));
  }

  clearWatch(): Promise<void> {
    return this.act('Watch cleared.', () => this.gateway.clearTripWatch(this.tripId()));
  }

  private async act(label: string, call: () => Promise<void>): Promise<void> {
    this.refusal.set(null);
    this.done.set('');
    try {
      await call();
      this.done.set(label);
      await this.readWatch();
    } catch (e) {
      this.refusal.set(refusalOf(e));
    }
  }

  private async readWatch(): Promise<void> {
    try {
      this.watch.set(await this.gateway.tripWatch(this.tripId()));
      this.watchRead.set(true);
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }
}

/**
 * The control tower's working desk: tickets taken through their steps, what the
 * detectors tolerate, the watch a trip is held to, and where the fleet is.
 */
@Component({
  selector: 'app-exception-desk',
  imports: [FormsModule, MapView, RefusalNote, TicketPanel, TripPanel],
  template: `
    <h1>Exception desk</h1>
    <p class="muted">
      Take a ticket from raised to closed, tune the detectors, hold a trip to an arrival time or a
      temperature band, and see which vehicles are where.
    </p>

    <section data-testid="ticket-list">
      <h2>Tickets</h2>
      <form class="inline" (ngSubmit)="openTicket(typedTicket())" data-testid="open-ticket">
        <input name="ticketId" placeholder="Ticket id" aria-label="Ticket id" data-testid="ticket-id" required
               [value]="typedTicket()" (input)="typedTicket.set($any($event.target).value)" />
        <button type="submit">Open</button>
      </form>
      <div class="inline">
        <label>
          Status
          <select data-testid="filter-status" [value]="status()" (change)="filterBy($any($event.target).value, severity())">
            <option value="">Any</option>
            @for (s of statuses; track s) { <option [value]="s">{{ s }}</option> }
          </select>
        </label>
        <label>
          Severity
          <select data-testid="filter-severity" [value]="severity()" (change)="filterBy(status(), $any($event.target).value)">
            <option value="">Any</option>
            @for (s of severities; track s) { <option [value]="s">{{ s }}</option> }
          </select>
        </label>
      </div>
      @if (listError()) { <p class="error" role="alert">{{ listError() }}</p> }
      @if (listed() && tickets().length === 0) {
        <p class="muted" data-testid="no-tickets">No ticket matches.</p>
      } @else {
        <div class="table-scroll">
          <table>
            <thead><tr><th>Ticket</th><th>Severity</th><th>Status</th><th>Category</th><th>About</th><th>Assignee</th></tr></thead>
            <tbody>
              @for (t of tickets(); track t['id']) {
                <tr data-testid="ticket-row" tabindex="0" (click)="openTicket(t['id'])" (keydown.enter)="openTicket(t['id'])">
                  <td>{{ t['ticketNo'] }}</td><td>{{ t['severity'] }}</td><td>{{ t['status'] }}</td><td>{{ t['category'] }}</td>
                  <td>{{ t['tripNo'] || t['subjectLabel'] }}</td><td>{{ t['assigneeName'] }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>

    @if (session.can('EXCEPTION_RAISE')) {
      <section>
        <h2>Raise a ticket</h2>
        <form class="inline" (ngSubmit)="raise()" data-testid="raise-ticket">
          <input name="raiseTrip" placeholder="Trip id" aria-label="Trip id of the ticket" required
                 [value]="raiseTrip()" (input)="raiseTrip.set($any($event.target).value)" />
          <select name="category" aria-label="Category" [value]="category()" (change)="category.set($any($event.target).value)">
            @for (c of categories; track c) { <option [value]="c">{{ c }}</option> }
          </select>
          <input name="description" placeholder="What happened" aria-label="What happened" required
                 [value]="description()" (input)="description.set($any($event.target).value)" />
          <button type="submit">Raise</button>
        </form>
        <app-refusal [refusal]="raiseRefusal()" />
      </section>
    }

    @if (ticketId()) {
      <app-ticket-panel [ticketId]="ticketId()" (changed)="list()" (closed)="ticketId.set('')" (trip)="tripId.set($event)" />
    }

    <section data-testid="detector-settings">
      <h2>Detectors</h2>
      @if (settings(); as d) {
        <dl class="facts">
          <div><dt>ETA slip allowed</dt><dd data-testid="eta-slip">{{ d.etaSlipMinutes }} min</dd></div>
          <div><dt>Temperature excursion allowed</dt><dd data-testid="temperature-excursion">{{ d.temperatureExcursionMinutes }} min</dd></div>
          <div><dt>Settings</dt><dd data-testid="detector-customised">{{ d.customised ? 'Set for this tenant' : 'Platform defaults' }}</dd></div>
        </dl>
        @if (session.can('EXCEPTION_MANAGE')) {
          <form class="inline" (ngSubmit)="saveDetectors()" data-testid="save-detectors">
            <input name="eta" type="number" min="1" max="10080" aria-label="ETA slip minutes" data-testid="eta-slip-input"
                   [placeholder]="d.etaSlipMinutes" [value]="eta()" (input)="eta.set($any($event.target).value)" />
            <input name="excursion" type="number" min="1" max="10080" aria-label="Temperature excursion minutes"
                   data-testid="temperature-excursion-input" [placeholder]="d.temperatureExcursionMinutes"
                   [value]="excursion()" (input)="excursion.set($any($event.target).value)" />
            <button type="submit">Save</button>
          </form>
        }
      }
      @if (detectorsSaved()) { <p data-testid="detectors-saved">Detector settings saved.</p> }
      <app-refusal [refusal]="detectorRefusal()" />
    </section>

    <section data-testid="trip-desk">
      <h2>Trip watch</h2>
      <form class="inline" (ngSubmit)="tripId.set(typedTrip().trim().toLowerCase())" data-testid="open-trip">
        <input name="watchTrip" placeholder="Trip id" aria-label="Trip id to watch" data-testid="watch-trip-id" required
               [value]="typedTrip()" (input)="typedTrip.set($any($event.target).value)" />
        <button type="submit">Open trip</button>
      </form>
      @if (tripId()) { <app-trip-panel [tripId]="tripId()" /> }
    </section>

    <section data-testid="fleet">
      <h2>Fleet</h2>
      <p><button class="link" data-testid="fleet-reload" (click)="readFleet()">Refresh</button></p>
      <h3>Off route</h3>
      @if (fleetError()) { <p class="error" role="alert">{{ fleetError() }}</p> }
      @if (deviationsRead() && deviations().length === 0) {
        <p class="muted" data-testid="no-deviations">No trip is off its route.</p>
      } @else {
        <table>
          <thead><tr><th>Trip</th><th>Since</th><th>Off by (m)</th><th>Corridor (m)</th><th></th></tr></thead>
          <tbody>
            @for (d of deviations(); track d['id']) {
              <tr data-testid="deviation-row">
                <td>{{ d['tripNo'] }}</td><td>{{ d['detectedAt'] }}</td><td>{{ d['distanceM'] }}</td><td>{{ d['corridorM'] }}</td>
                <td><button class="link" (click)="tripId.set(d['tripId'])">Open trip</button></td>
              </tr>
            }
          </tbody>
        </table>
      }
      <h3>Positions</h3>
      @if (positions(); as p) {
        <p class="muted" data-testid="position-count">{{ p.rows.length }} vehicles reporting</p>
        <app-map [features]="p.features" label="Where the fleet last reported from" />
      }
    </section>
  `,
})
export class ExceptionDeskPage implements OnInit {
  private readonly gateway = inject(LmsGateway);
  readonly session = inject(Session);

  readonly statuses = EXCEPTION_STATUSES;
  readonly severities = EXCEPTION_SEVERITIES;
  readonly categories = MANUAL_EXCEPTION_CATEGORIES;

  readonly typedTicket = signal('');
  readonly ticketId = signal('');
  readonly status = signal('');
  readonly severity = signal('');
  readonly tickets = signal<Row[]>([]);
  readonly listed = signal(false);
  readonly listError = signal('');

  readonly raiseTrip = signal('');
  readonly category = signal<string>(MANUAL_EXCEPTION_CATEGORIES[0]);
  readonly description = signal('');
  readonly raiseRefusal = signal<Refusal | null>(null);

  readonly settings = signal<DetectorSettingsView | null>(null);
  readonly eta = signal('');
  readonly excursion = signal('');
  readonly detectorsSaved = signal(false);
  readonly detectorRefusal = signal<Refusal | null>(null);

  readonly typedTrip = signal('');
  readonly tripId = signal('');

  readonly positions = signal<Mapped | null>(null);
  readonly deviations = signal<Row[]>([]);
  readonly deviationsRead = signal(false);
  readonly fleetError = signal('');

  ngOnInit(): void {
    void this.list();
    void this.readDetectors();
    void this.readFleet();
  }

  async list(): Promise<void> {
    this.listError.set('');
    try {
      this.tickets.set(await this.gateway.exceptionTickets({ status: this.status(), severity: this.severity() }));
      this.listed.set(true);
    } catch (e) {
      this.listError.set((e as Error).message);
    }
  }

  filterBy(status: string, severity: string): Promise<void> {
    this.status.set(status);
    this.severity.set(severity);
    return this.list();
  }

  openTicket(id: string): void {
    this.ticketId.set(id.trim().toLowerCase());
  }

  async raise(): Promise<void> {
    this.raiseRefusal.set(null);
    try {
      const id = await this.gateway.raiseException({
        tripId: this.raiseTrip(), category: this.category(), description: this.description(),
      });
      this.description.set('');
      this.ticketId.set(id);
      await this.list();
    } catch (e) {
      this.raiseRefusal.set(refusalOf(e));
    }
  }

  async saveDetectors(): Promise<void> {
    const current = this.settings();
    this.detectorRefusal.set(null);
    this.detectorsSaved.set(false);
    try {
      await this.gateway.saveDetectorSettings(
        Number(this.eta() || current?.etaSlipMinutes), Number(this.excursion() || current?.temperatureExcursionMinutes));
      this.detectorsSaved.set(true);
      this.eta.set('');
      this.excursion.set('');
      await this.readDetectors();
    } catch (e) {
      this.detectorRefusal.set(refusalOf(e));
    }
  }

  async readFleet(): Promise<void> {
    this.fleetError.set('');
    try {
      const [positions, deviations] = await Promise.all([this.gateway.vehiclePositions(), this.gateway.openDeviations()]);
      this.positions.set(positions);
      this.deviations.set(deviations);
      this.deviationsRead.set(true);
    } catch (e) {
      this.fleetError.set((e as Error).message);
    }
  }

  private async readDetectors(): Promise<void> {
    try {
      this.settings.set(await this.gateway.detectorSettings());
    } catch (e) {
      this.detectorRefusal.set(refusalOf(e));
    }
  }
}
