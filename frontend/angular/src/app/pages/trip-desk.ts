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

import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ApiProblem, LmsGateway, TripStopView } from '../gateway/lms-gateway';
import { TripCard, TripRaiseDraft, TripStep } from '../gateway/trip-desk-types';
import { Session } from '../session';
import { EntityPicker } from './entity-picker';
import { ErrorBanner } from './error-banner';
import { PageHeader } from './page-header';
import { StatusBadge } from './status-badge';
import { ConfirmHost } from './confirm-dialog';
import { confirmWording, type PendingConfirm } from '../../../../shared/confirm';

/** The states in which a trip has not left the yard, and so may still be cancelled. */
const CANCELLABLE = ['PLANNED', 'ASSIGNED', 'AT_ORIGIN', 'LOADED'];

/** The one milestone each state is waiting for, with its button's words and what is said when it is done. */
const NEXT: Record<string, { step: TripStep; testId: string; label: string; done: string } | undefined> = {
  AT_ORIGIN: { step: 'loaded', testId: 'trip-loaded', label: 'Mark loaded', done: 'The trip is marked loaded.' },
  LOADED: { step: 'dispatch', testId: 'trip-dispatch', label: 'Dispatch', done: 'The trip is dispatched.' },
  DISPATCHED: { step: 'in-transit', testId: 'trip-in-transit', label: 'Start transit', done: 'The trip is on the road.' },
  IN_TRANSIT: { step: 'arrival', testId: 'trip-arrival', label: 'Record arrival', done: 'Arrival is recorded.' },
  AT_DESTINATION: { step: 'completion', testId: 'trip-complete', label: 'Complete trip', done: 'The trip is complete.' },
};

interface Refusal {
  code: string;
  message: string;
}

/**
 * The trip desk: raise a trip against an awarded load, nominate its vehicle and driver, move it through loading,
 * dispatch, transit, arrival and completion, cancel it before it leaves, and see and tick off its stops. The server
 * decides what each state allows; every refusal is shown by its code.
 */
@Component({
  selector: 'app-trip-desk',
  imports: [ConfirmHost, EntityPicker, ErrorBanner, FormsModule, PageHeader, StatusBadge],
  template: `
    <app-page-header title="Trip desk" />
    <p class="muted">Raise a trip against an awarded load, put a vehicle and driver on it, and take it from the yard to completion.</p>

    @if (session.can('TRIP_EXECUTE')) {
      <form ngNativeValidate class="inline" (ngSubmit)="raise()" data-testid="trip-raise-form">
        <input name="tripNo" placeholder="Trip number" aria-label="New trip number" data-testid="trip-no-input" required
               [value]="draft().tripNo" (input)="setDraft('tripNo', $any($event.target).value)" />
        <app-entity-picker [kind]="'load'" placeholder="Awarded load" ariaLabel="Load id" testId="trip-load-id"
                           [required]="true" [value]="draft().loadId" (valueChange)="setDraft('loadId', $event)" />
        <input name="plannedStartAt" type="datetime-local" aria-label="Planned start" data-testid="trip-planned-start"
               [value]="draft().plannedStartAt" (input)="setDraft('plannedStartAt', $any($event.target).value)" />
        <button type="submit" data-testid="trip-raise">Raise trip</button>
      </form>
      @if (raiseError(); as r) {
        <app-error-banner [message]="r.message" [code]="r.code" testId="trip-raise-error" codeTestId="trip-raise-refusal" />
      }
    }

    <form ngNativeValidate class="inline" (ngSubmit)="open()" data-testid="trip-open-form">
      <app-entity-picker [kind]="'trip'" placeholder="Trip" ariaLabel="Trip id" testId="trip-open-id" [required]="true"
                         [(value)]="typed" />
      <button type="submit" data-testid="trip-open">Open trip</button>
    </form>
    @if (loadError()) { <app-error-banner [message]="loadError()" testId="trip-load-error" /> }

    @if (trip(); as t) {
      <section data-testid="trip-panel" [attr.data-id]="t.id">
        <h2>Trip <span data-testid="trip-no">{{ t.tripNo }}</span></h2>
        <dl class="facts">
          <div><dt>Status</dt><dd><app-status-badge data-testid="trip-status" [value]="t.status" /></dd></div>
          <div><dt>Load</dt><dd data-testid="trip-load" [attr.data-id]="t.loadId">{{ t.loadNo || '—' }}</dd></div>
          <div><dt>Vendor</dt><dd data-testid="trip-vendor">{{ t.vendorName || '—' }}</dd></div>
          <div><dt>Vehicle</dt><dd data-testid="trip-vehicle">{{ t.vehicle || '—' }}</dd></div>
          <div><dt>Driver</dt><dd data-testid="trip-driver">{{ t.driver || '—' }}</dd></div>
          <div><dt>From</dt><dd data-testid="trip-origin">{{ t.origin || '—' }}</dd></div>
          <div><dt>To</dt><dd data-testid="trip-destination">{{ t.destination || '—' }}</dd></div>
          <div><dt>Planned start</dt><dd data-testid="trip-planned">{{ t.plannedStartAt || '—' }}</dd></div>
          <div><dt>Dispatched</dt><dd data-testid="trip-dispatched">{{ t.dispatchedAt || '—' }}</dd></div>
          <div><dt>Arrived</dt><dd data-testid="trip-arrived">{{ t.arrivedAt || '—' }}</dd></div>
          <div><dt>Completed</dt><dd data-testid="trip-completed">{{ t.completedAt || '—' }}</dd></div>
          @if (t.cancelledAt) {
            <div><dt>Cancelled because</dt><dd data-testid="trip-cancelled-reason">{{ t.cancellationReason || '—' }}</dd></div>
          }
        </dl>
        @if (done()) { <p class="ok" role="status" data-testid="trip-done">{{ done() }}</p> }
        @if (refusal(); as r) {
          <app-error-banner [message]="r.message" [code]="r.code" testId="trip-error" codeTestId="trip-refusal" />
        }

        @if (session.can('TRIP_EXECUTE')) {
          @if (t.status === 'PLANNED') {
            <form ngNativeValidate class="inline" (ngSubmit)="assign(t)" data-testid="trip-assign-form">
              <app-entity-picker [kind]="'vehicle'" placeholder="Vehicle" ariaLabel="Vehicle id" testId="trip-vehicle-id"
                                 [required]="true" [(value)]="vehicleId" />
              <app-entity-picker [kind]="'driver'" placeholder="Driver (optional)" ariaLabel="Driver id"
                                 testId="trip-driver-id" [(value)]="driverId" />
              <button type="submit" data-testid="trip-assign">Assign</button>
            </form>
          }
          @if (t.status === 'ASSIGNED') {
            <p class="muted" data-testid="trip-gate-hint">Gate-in and weighing are recorded at the gate desk.</p>
          }
          @if (next(t); as n) {
            <p class="actions"><button type="button" [attr.data-testid]="n.testId" (click)="step(t, n)">{{ n.label }}</button></p>
          }
          @if (cancellable(t)) {
            <p class="actions" data-testid="trip-cancel-form">
              <button type="button" data-testid="trip-cancel" (click)="askCancel(t)">Cancel trip</button>
            </p>
          }
          <app-confirm-host [(pending)]="pending" />
        }

        <h3>Stops</h3>
        @if (stopError()) { <app-error-banner [message]="stopError()" testId="trip-stop-error" /> }
        @if (stops().length === 0) {
          <p class="muted" data-testid="trip-stops-empty">This trip has no planned stops.</p>
        } @else {
          <div class="table-wrap"><table data-testid="trip-stops">
            <thead><tr><th>#</th><th>Kind</th><th>Terminal</th><th>Status</th><th>Arrived</th><th>Departed</th><th></th></tr></thead>
            <tbody>
              @for (s of stops(); track s.id) {
                <tr data-testid="trip-stop" [attr.data-seq]="s.seq">
                  <td>{{ s.seq }}</td><td>{{ s.kind }}</td><td>{{ s.terminalCode }}</td>
                  <td><app-status-badge data-testid="trip-stop-status" [value]="s.status" /></td>
                  <td>{{ s.arrivedAt || '—' }}</td><td>{{ s.departedAt || '—' }}</td>
                  <td>
                    @if (session.can('TRIP_EXECUTE')) {
                      @if (s.status === 'PLANNED') {
                        <button type="button" class="link" data-testid="trip-stop-arrive"
                                [attr.aria-label]="'Arrive at ' + s.terminalCode" (click)="arriveAt(t, s)">Arrive</button>
                      }
                      @if (s.status === 'ARRIVED') {
                        <button type="button" class="link" data-testid="trip-stop-depart"
                                [attr.aria-label]="'Depart from ' + s.terminalCode" (click)="departFrom(t, s)">Depart</button>
                      }
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table></div>
        }
      </section>
    }
  `,
})
export class TripDesk {
  /** The destructive action waiting for its confirmation, shown by the confirm host in the template. */
  protected readonly pending = signal<PendingConfirm | null>(null);
  private readonly gateway = inject(LmsGateway);
  private readonly location = inject(ActivatedRoute);
  protected readonly session = inject(Session);

  readonly draft = signal<TripRaiseDraft>({ tripNo: '', loadId: '', plannedStartAt: '' });
  readonly typed = signal('');
  readonly trip = signal<TripCard | null>(null);
  readonly stops = signal<TripStopView[]>([]);
  readonly loadError = signal('');
  readonly raiseError = signal<Refusal | null>(null);
  readonly stopError = signal('');
  readonly done = signal('');
  readonly refusal = signal<Refusal | null>(null);
  readonly vehicleId = signal('');
  readonly driverId = signal('');

  constructor() {
    // A link from another screen names the trip, so it is shown straight away.
    const linked = this.location.snapshot.queryParamMap.get('trip');
    if (linked) {
      this.typed.set(linked);
      void this.open();
    }
  }

  setDraft(key: keyof TripRaiseDraft, value: string): void {
    this.draft.update((d) => ({ ...d, [key]: value }));
  }

  next(t: TripCard) {
    return NEXT[t.status];
  }

  cancellable(t: TripCard): boolean {
    return CANCELLABLE.includes(t.status);
  }

  async open(): Promise<void> {
    const id = this.typed().trim().toLowerCase();
    if (id !== this.trip()?.id) {
      this.trip.set(null);
      this.stops.set([]);
      this.done.set('');
      this.refusal.set(null);
      this.vehicleId.set('');
      this.driverId.set('');
    }
    await this.refresh(id);
  }

  async raise(): Promise<void> {
    this.raiseError.set(null);
    try {
      const id = await this.gateway.raiseTrip(this.draft());
      this.draft.set({ tripNo: '', loadId: '', plannedStartAt: '' });
      this.typed.set(id);
      await this.open();
      this.done.set('The trip is raised.');
    } catch (e) {
      this.raiseError.set(this.problem(e));
    }
  }

  assign(t: TripCard): Promise<void> {
    return this.act(t, 'The vehicle is assigned.', () => this.gateway.assignTrip(t.id, this.vehicleId(), this.driverId()));
  }

  step(t: TripCard, n: { step: TripStep; done: string }): Promise<void> {
    return this.act(t, n.done, () => this.gateway.stepTrip(t.id, n.step));
  }

  askCancel(t: TripCard): void {
    this.pending.set({
      wording: confirmWording('Cancel trip', t.tripNo),
      reason: { label: 'Reason for cancelling the trip', testId: 'trip-cancel-reason', placeholder: 'Reason for cancelling', required: true },
      run: (why) => this.act(t, 'The trip is cancelled.', () => this.gateway.cancelTrip(t.id, why)),
    });
  }

  arriveAt(t: TripCard, s: TripStopView): Promise<void> {
    return this.stopAct(t, () => this.gateway.arriveAtStop(t.id, s.id));
  }

  departFrom(t: TripCard, s: TripStopView): Promise<void> {
    return this.stopAct(t, () => this.gateway.departFromStop(t.id, s.id));
  }

  private problem(e: unknown): Refusal {
    return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
  }

  /** Makes a change, then reads the trip again: the server decides what state it is in. */
  private async act(t: TripCard, outcome: string, change: () => Promise<void>): Promise<void> {
    this.done.set('');
    this.refusal.set(null);
    try {
      await change();
      this.done.set(outcome);
    } catch (e) {
      this.refusal.set(this.problem(e));
    }
    await this.refresh(t.id);
  }

  private async stopAct(t: TripCard, change: () => Promise<void>): Promise<void> {
    this.stopError.set('');
    try {
      await change();
    } catch (e) {
      this.stopError.set((e as Error).message);
    }
    await this.refresh(t.id);
  }

  private async refresh(id: string): Promise<void> {
    this.loadError.set('');
    try {
      this.trip.set(await this.gateway.tripCard(id));
      this.stops.set(await this.gateway.tripStops(id));
    } catch (e) {
      this.trip.set(null);
      this.loadError.set((e as Error).message);
    }
  }
}
