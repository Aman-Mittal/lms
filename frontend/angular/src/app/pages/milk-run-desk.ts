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

import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiProblem, LmsGateway } from '../gateway/lms-gateway';
import {
  CircuitRunCard, CircuitStopCard, CircuitStopStep, ROUTE_DAYS, RoutePlanDraft, RouteStopDraft,
} from '../gateway/milk-itinerary-types';
import { Session } from '../session';
import { EntityPicker } from './entity-picker';
import { ErrorBanner } from './error-banner';
import { PageHeader } from './page-header';
import { StatusBadge } from './status-badge';
import { ConfirmHost } from './confirm-dialog';
import { confirmWording, type PendingConfirm } from '../../../../shared/confirm';

interface Refusal {
  code: string;
  message: string;
}

const BLANK_STOP: RouteStopDraft = { terminalId: '', windowOpenMin: '', windowCloseMin: '', plannedDwellMin: '' };

function blankRoute(): RoutePlanDraft {
  return {
    code: '', name: '', plantTerminalId: '', effectiveFrom: '', days: ['MON', 'TUE', 'WED', 'THU', 'FRI'],
    departureTime: '', timeZone: '', runRate: '', perStopFee: '', currency: '', stops: [{ ...BLANK_STOP }],
  };
}

/**
 * The milk-run desk: publish a route, plan the runs from it, hand a run to a trip, record what happens at each stop
 * and complete the run. The server decides what each state allows; every refusal is shown by its code.
 */
@Component({
  selector: 'app-milk-run-desk',
  imports: [ConfirmHost, EntityPicker, ErrorBanner, FormsModule, PageHeader, StatusBadge],
  template: `
    <app-page-header title="Milk run desk" />
    <app-confirm-host [(pending)]="pending" />
    <p class="muted">Publish a pickup circuit, plan its runs, and follow each run stop by stop.</p>

    @if (session.can('MILKRUN_PLAN')) {
      <form ngNativeValidate data-testid="mr-publish-form" (ngSubmit)="publish()">
        <h2>Publish a route</h2>
        <div class="inline">
          <input name="code" placeholder="Route code" aria-label="Route code" data-testid="mr-code" required
                 [value]="draft().code" (input)="set('code', $any($event.target).value)" />
          <input name="name" placeholder="Route name (optional)" aria-label="Route name" data-testid="mr-name"
                 [value]="draft().name" (input)="set('name', $any($event.target).value)" />
          <app-entity-picker [kind]="'terminal'" placeholder="Plant" ariaLabel="Plant terminal" testId="mr-plant"
                             [required]="true" [value]="draft().plantTerminalId" (valueChange)="set('plantTerminalId', $event)" />
          <input name="effectiveFrom" type="date" aria-label="Effective from" data-testid="mr-from" required
                 [value]="draft().effectiveFrom" (input)="set('effectiveFrom', $any($event.target).value)" />
          <input name="departureTime" type="time" aria-label="Departure time" data-testid="mr-departs" required
                 [value]="draft().departureTime" (input)="set('departureTime', $any($event.target).value)" />
        </div>
        <div class="inline">
          @for (d of days; track d) {
            <label><input type="checkbox" [attr.data-testid]="'mr-day-' + d" [checked]="draft().days.includes(d)"
                          (change)="toggleDay(d)" /> {{ d }}</label>
          }
        </div>
        <div class="inline">
          <input name="runRate" type="number" min="0" step="any" placeholder="Rate per run" aria-label="Rate per run"
                 data-testid="mr-rate" required [value]="draft().runRate" (input)="set('runRate', $any($event.target).value)" />
          <input name="perStopFee" type="number" min="0" step="any" placeholder="Fee per stop" aria-label="Fee per stop"
                 data-testid="mr-fee" required [value]="draft().perStopFee" (input)="set('perStopFee', $any($event.target).value)" />
          <input name="currency" placeholder="Currency (optional)" aria-label="Route currency" data-testid="mr-currency"
                 [value]="draft().currency" (input)="set('currency', $any($event.target).value)" />
        </div>
        <h3>Supplier stops, in circuit order</h3>
        @for (s of draft().stops; track $index; let i = $index) {
          <div class="inline" data-testid="mr-stop-row">
            <app-entity-picker [kind]="'terminal'" placeholder="Supplier" [ariaLabel]="'Supplier ' + (i + 1)"
                               testId="mr-stop-terminal" [required]="true" [value]="s.terminalId"
                               (valueChange)="setStop(i, 'terminalId', $event)" />
            <input type="number" min="0" placeholder="Window opens (min)" [attr.aria-label]="'Window opens, supplier ' + (i + 1)"
                   data-testid="mr-stop-open" required [value]="s.windowOpenMin"
                   (input)="setStop(i, 'windowOpenMin', $any($event.target).value)" />
            <input type="number" min="0" placeholder="Window closes (min)" [attr.aria-label]="'Window closes, supplier ' + (i + 1)"
                   data-testid="mr-stop-close" required [value]="s.windowCloseMin"
                   (input)="setStop(i, 'windowCloseMin', $any($event.target).value)" />
            <input type="number" min="0" placeholder="Dwell (min)" [attr.aria-label]="'Dwell, supplier ' + (i + 1)"
                   data-testid="mr-stop-dwell" required [value]="s.plannedDwellMin"
                   (input)="setStop(i, 'plannedDwellMin', $any($event.target).value)" />
            @if (draft().stops.length > 1) {
              <button type="button" class="link" data-testid="mr-stop-remove" [attr.aria-label]="'Remove supplier ' + (i + 1)"
                      (click)="removeStop(i)">Remove</button>
            }
          </div>
        }
        <p class="actions">
          <button type="button" data-testid="mr-stop-add" (click)="addStop()">Add supplier stop</button>
          <button type="submit" data-testid="mr-publish">Publish route</button>
        </p>
      </form>
      @if (publishDone()) { <p class="ok" role="status" data-testid="mr-publish-done">{{ publishDone() }}</p> }
      @if (publishError(); as r) {
        <app-error-banner [message]="r.message" [code]="r.code" testId="mr-publish-error" codeTestId="mr-publish-refusal" />
      }

      <form ngNativeValidate class="inline" data-testid="mr-generate-form" (ngSubmit)="generate()">
        <input name="genFrom" type="date" aria-label="Plan runs from" data-testid="mr-gen-from"
               [value]="genFrom()" (input)="genFrom.set($any($event.target).value)" />
        <input name="genDays" type="number" min="1" max="31" placeholder="Days (1 to 31)" aria-label="Days to plan"
               data-testid="mr-gen-days" [value]="genDays()" (input)="genDays.set($any($event.target).value)" />
        <button type="submit" data-testid="mr-generate">Generate runs</button>
      </form>
      @if (generateDone()) { <p class="ok" role="status" data-testid="mr-generate-done">{{ generateDone() }}</p> }
      @if (generateError(); as r) {
        <app-error-banner [message]="r.message" [code]="r.code" testId="mr-generate-error" codeTestId="mr-generate-refusal" />
      }
    }

    <h2>Runs</h2>
    <form class="inline" data-testid="mr-runs-form" (submit)="$event.preventDefault(); loadRuns()">
      <input name="routeFilter" placeholder="Route code (optional)" aria-label="Show runs of route" data-testid="mr-route-filter"
             [value]="routeFilter()" (input)="routeFilter.set($any($event.target).value)" />
      <button type="submit" data-testid="mr-runs-show">Show runs</button>
    </form>
    @if (runsError()) { <app-error-banner [message]="runsError()" testId="mr-runs-error" /> }
    @if (runs().length === 0) {
      <p class="muted" data-testid="mr-runs-empty">No runs to show.</p>
    } @else {
      <div class="table-wrap"><table data-testid="mr-runs">
        <thead><tr><th>Route</th><th>Date</th><th>Status</th><th>Trip</th><th></th></tr></thead>
        <tbody>
          @for (r of runs(); track r.id) {
            <tr data-testid="mr-run" [attr.data-id]="r.id">
              <td>{{ r.routeCode }}</td><td>{{ r.runDate }}</td>
              <td><app-status-badge [value]="r.status" /></td><td>{{ r.tripNo || '—' }}</td>
              <td><button type="button" class="link" data-testid="mr-run-select"
                          [attr.aria-label]="'Select run ' + r.routeCode + ' ' + r.runDate" (click)="select(r.id)">Select</button></td>
            </tr>
          }
        </tbody>
      </table></div>
    }

    @if (run(); as r) {
      <section data-testid="mr-run-panel" [attr.data-id]="r.id">
        <h2>Run <span data-testid="mr-run-route">{{ r.routeCode }}</span> on <span data-testid="mr-run-date">{{ r.runDate }}</span></h2>
        <dl class="facts">
          <div><dt>Status</dt><dd><app-status-badge data-testid="mr-run-status" [value]="r.status" /></dd></div>
          <div><dt>Trip</dt><dd data-testid="mr-run-trip">{{ r.tripNo || '—' }}</dd></div>
          <div><dt>Started</dt><dd>{{ r.startedAt || '—' }}</dd></div>
          <div><dt>Completed</dt><dd data-testid="mr-run-completed">{{ r.completedAt || '—' }}</dd></div>
          <div><dt>Visited</dt><dd data-testid="mr-run-visited">{{ r.stopsVisited || '0' }}</dd></div>
          <div><dt>Skipped</dt><dd data-testid="mr-run-skipped">{{ r.stopsSkipped || '0' }}</dd></div>
          <div><dt>Picked up (kg)</dt><dd data-testid="mr-run-picked">{{ r.totalPickedUpKg || '0' }}</dd></div>
          <div><dt>Charge</dt><dd data-testid="mr-run-charge">{{ r.chargeAmount || '—' }}</dd></div>
        </dl>
        @if (runDone()) { <p class="ok" role="status" data-testid="mr-run-done">{{ runDone() }}</p> }
        @if (runRefusal(); as f) {
          <app-error-banner [message]="f.message" [code]="f.code" testId="mr-run-error" codeTestId="mr-run-refusal" />
        }
        @if (session.can('MILKRUN_EXECUTE') && r.status !== 'COMPLETED') {
          <form ngNativeValidate class="inline" data-testid="mr-trip-form" (ngSubmit)="assign(r)">
            <app-entity-picker [kind]="'trip'" placeholder="Trip" ariaLabel="Trip for the run" testId="mr-trip-id"
                               [required]="true" [(value)]="tripId" />
            <button type="submit" data-testid="mr-assign-trip">Assign trip</button>
          </form>
          @if (r.status === 'IN_PROGRESS') {
            <p class="actions"><button type="button" data-testid="mr-complete" (click)="complete(r)">Complete run</button></p>
          }
        }

        <h3>Stops</h3>
        @if (r.status === 'PLANNED') {
          <p class="muted" data-testid="mr-stops-hint">Stops are recorded once the run is under way.</p>
        }
        @if (stopError()) { <app-error-banner [message]="stopError()" testId="mr-stop-error" /> }
        <div class="table-wrap"><table data-testid="mr-stops">
          <thead><tr><th>#</th><th>Supplier</th><th>Status</th><th>Arrived</th><th>Departed</th><th>Picked up (kg)</th><th></th></tr></thead>
          <tbody>
            @for (s of stops(); track s.sequence) {
              <tr data-testid="mr-stop" [attr.data-seq]="s.sequence">
                <td>{{ s.sequence }}</td><td>{{ s.terminalCode }}</td>
                <td><app-status-badge data-testid="mr-stop-status" [value]="s.status" /></td>
                <td>{{ s.arrivedAt || '—' }}</td><td>{{ s.departedAt || '—' }}</td>
                <td data-testid="mr-stop-picked">{{ s.pickedUpKg || '—' }}</td>
                <td>
                  @if (session.can('MILKRUN_EXECUTE') && r.status === 'IN_PROGRESS') {
                    @if (s.status === 'PENDING') {
                      <button type="button" class="link" data-testid="mr-stop-arrive"
                              [attr.aria-label]="'Arrive at ' + s.terminalCode" (click)="stop(r, s, 'arrival')">Arrive</button>
                      <button type="button" class="link" data-testid="mr-stop-skip"
                              [attr.aria-label]="'Skip ' + s.terminalCode" (click)="askSkip(r, s)">Skip</button>
                    }
                    @if (s.status === 'ARRIVED') {
                      <input type="number" min="0" step="any" placeholder="kg" [attr.aria-label]="'Quantity picked up at ' + s.terminalCode"
                             data-testid="mr-stop-qty" [value]="entry(s.sequence)" (input)="setEntry(s.sequence, $any($event.target).value)" />
                      <button type="button" class="link" data-testid="mr-stop-pickup"
                              [attr.aria-label]="'Record pickup at ' + s.terminalCode" (click)="stop(r, s, 'pickup')">Pickup</button>
                      <button type="button" class="link" data-testid="mr-stop-depart"
                              [attr.aria-label]="'Depart from ' + s.terminalCode" (click)="stop(r, s, 'departure')">Depart</button>
                    }
                  }
                </td>
              </tr>
            }
          </tbody>
        </table></div>
      </section>
    }
  `,
})
export class MilkRunDesk implements OnInit {
  /** The destructive action waiting for its confirmation, shown by the confirm host in the template. */
  protected readonly pending = signal<PendingConfirm | null>(null);
  private readonly gateway = inject(LmsGateway);
  protected readonly session = inject(Session);

  readonly days = ROUTE_DAYS;
  readonly draft = signal<RoutePlanDraft>(blankRoute());
  readonly publishDone = signal('');
  readonly publishError = signal<Refusal | null>(null);
  readonly genFrom = signal('');
  readonly genDays = signal('');
  readonly generateDone = signal('');
  readonly generateError = signal<Refusal | null>(null);
  readonly routeFilter = signal('');
  readonly runs = signal<CircuitRunCard[]>([]);
  readonly runsError = signal('');
  readonly run = signal<CircuitRunCard | null>(null);
  readonly stops = signal<CircuitStopCard[]>([]);
  readonly tripId = signal('');
  readonly runDone = signal('');
  readonly runRefusal = signal<Refusal | null>(null);
  readonly stopError = signal('');
  private readonly entries = signal<Record<number, string>>({});

  ngOnInit(): void {
    void this.loadRuns();
  }

  set(key: keyof RoutePlanDraft, value: string): void {
    this.draft.update((d) => ({ ...d, [key]: value }));
  }

  toggleDay(day: string): void {
    this.draft.update((d) => ({ ...d, days: d.days.includes(day) ? d.days.filter((x) => x !== day) : [...d.days, day] }));
  }

  setStop(i: number, key: keyof RouteStopDraft, value: string): void {
    this.draft.update((d) => ({ ...d, stops: d.stops.map((s, at) => (at === i ? { ...s, [key]: value } : s)) }));
  }

  addStop(): void {
    this.draft.update((d) => ({ ...d, stops: [...d.stops, { ...BLANK_STOP }] }));
  }

  removeStop(i: number): void {
    this.draft.update((d) => ({ ...d, stops: d.stops.filter((_, at) => at !== i) }));
  }

  entry(sequence: number): string {
    return this.entries()[sequence] ?? '';
  }

  setEntry(sequence: number, value: string): void {
    this.entries.update((e) => ({ ...e, [sequence]: value }));
  }

  private problem(e: unknown): Refusal {
    return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
  }

  async publish(): Promise<void> {
    this.publishDone.set('');
    this.publishError.set(null);
    try {
      const code = this.draft().code.trim();
      await this.gateway.publishMilkRoute(this.draft());
      this.draft.set(blankRoute());
      this.publishDone.set(`The route ${code} is published.`);
      this.routeFilter.set(code);
    } catch (e) {
      this.publishError.set(this.problem(e));
    }
  }

  async generate(): Promise<void> {
    this.generateDone.set('');
    this.generateError.set(null);
    try {
      const made = await this.gateway.generateCircuitRuns(this.genFrom(), this.genDays());
      this.generateDone.set(`${made} ${made === 1 ? 'run' : 'runs'} planned.`);
      await this.loadRuns();
    } catch (e) {
      this.generateError.set(this.problem(e));
    }
  }

  async loadRuns(): Promise<void> {
    this.runsError.set('');
    try {
      this.runs.set(await this.gateway.circuitRunCards(this.routeFilter()));
    } catch (e) {
      this.runs.set([]);
      this.runsError.set((e as Error).message);
    }
  }

  async select(id: string): Promise<void> {
    if (id !== this.run()?.id) {
      this.run.set(null);
      this.stops.set([]);
      this.runDone.set('');
      this.runRefusal.set(null);
      this.stopError.set('');
      this.tripId.set('');
      this.entries.set({});
    }
    await this.refresh(id);
  }

  assign(r: CircuitRunCard): Promise<void> {
    return this.act(r, 'The trip is assigned to the run.', async () => {
      await this.gateway.assignCircuitTrip(r.id, this.tripId());
      this.tripId.set('');
    });
  }

  complete(r: CircuitRunCard): Promise<void> {
    return this.act(r, 'The run is complete.', () => this.gateway.completeCircuitRun(r.id));
  }

  askSkip(r: CircuitRunCard, s: CircuitStopCard): void {
    this.pending.set({
      wording: confirmWording('Skip stop', s.terminalCode, 'It is recorded as skipped and the run moves on.'),
      reason: { label: `Why ${s.terminalCode} is skipped`, testId: 'mr-stop-skip-reason', placeholder: 'Reason', required: true },
      run: (why) => this.stop(r, s, 'skip', why),
    });
  }

  async stop(r: CircuitRunCard, s: CircuitStopCard, step: CircuitStopStep, reason = ''): Promise<void> {
    const value = step === 'skip' ? reason : step === 'pickup' ? this.entry(s.sequence).trim() : '';
    this.stopError.set('');
    if (step === 'pickup' && !value) {
      this.stopError.set('Enter the quantity picked up.');
      return;
    }
    try {
      await this.gateway.recordCircuitStop(r.id, s.sequence, step, value);
      this.setEntry(s.sequence, '');
    } catch (e) {
      this.stopError.set((e as Error).message);
    }
    await this.refresh(r.id);
  }

  /** Makes a change, then reads the run again: the server decides what state it is in. */
  private async act(r: CircuitRunCard, outcome: string, change: () => Promise<void>): Promise<void> {
    this.runDone.set('');
    this.runRefusal.set(null);
    try {
      await change();
      this.runDone.set(outcome);
    } catch (e) {
      this.runRefusal.set(this.problem(e));
    }
    await this.refresh(r.id);
    await this.loadRuns();
  }

  private async refresh(id: string): Promise<void> {
    try {
      this.run.set(await this.gateway.circuitRunCard(id));
      this.stops.set(await this.gateway.circuitRunStops(id));
    } catch (e) {
      this.run.set(null);
      this.runsError.set((e as Error).message);
    }
  }
}
