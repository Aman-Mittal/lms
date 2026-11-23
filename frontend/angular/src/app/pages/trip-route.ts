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
import { ActivatedRoute } from '@angular/router';
import { LmsGateway, TripRoute as Route, TripStopView } from '../gateway/lms-gateway';
import { Session } from '../session';
import { MapView } from './map-view';
import { EntityPicker } from './entity-picker';
import { TripMoney } from './trip-money';
import { PageHeader } from './page-header';
import { StatusBadge } from './status-badge';
import { When } from './when';
import { ErrorBanner } from './error-banner';
import { ConfirmHost } from './confirm-dialog';
import { confirmWording, type PendingConfirm } from '../../../../shared/confirm';

/** A trip's track so far and where it is now. */
@Component({
  selector: 'app-trip-route',
  imports: [ConfirmHost, ErrorBanner, FormsModule, MapView, EntityPicker, TripMoney, PageHeader, StatusBadge, When],
  template: `
    <app-page-header title="Trip route" />
    <app-confirm-host [(pending)]="pending" />
    <p class="muted">The route a trip has driven so far, from the positions its vehicle reported.</p>
    <!-- A signal, not ngModel: ngModel connects after the screen is shown, and would wipe an id typed before it. -->
    <form ngNativeValidate class="inline" (ngSubmit)="lookUp()" data-testid="trip-lookup">
      <app-entity-picker [kind]="'trip'" placeholder="Trip" ariaLabel="Trip id" testId="trip-id" [required]="true"
                         [(value)]="typed" />
      <button type="submit">Show route</button>
    </form>
    @if (loading()) { <p class="muted">Loading…</p> }
    @if (error()) { <app-error-banner [message]="error()" /> }
    @if (route(); as r) {
      <section data-testid="trip-route">
        <dl class="facts">
          <div><dt>Trip</dt><dd data-testid="route-trip-no">{{ r.tripNo }}</dd></div>
          <div><dt>Status</dt><dd><app-status-badge [value]="r.status" /></dd></div>
          <div><dt>From</dt><dd>{{ r.originCode }}</dd></div>
          <div><dt>To</dt><dd>{{ r.destinationCode }}</dd></div>
          <div><dt>Last fix</dt><dd>{{ (r.lastFixAt || '—') | when }}</dd></div>
          <div><dt>Done</dt><dd>{{ r.completionPct ? r.completionPct + ' %' : '—' }}</dd></div>
        </dl>
        @if (r.features.length === 0) {
          <p class="muted" data-testid="no-track">No positions have been reported for this trip yet.</p>
        }
        <app-map [features]="r.features" [label]="'Route of ' + r.tripNo" />
      </section>
      <section data-testid="trip-stops" aria-label="Trip stops">
        <h2>Stops</h2>
        @if (stopError()) {
          <app-error-banner [message]="stopError()" testId="stop-error" />
        }
        @if (stops().length === 0) {
          <p class="muted" data-testid="stops-empty">This trip has no planned stops.</p>
        } @else {
          <div class="table-wrap"><table data-testid="stops">
            <thead>
              <tr><th>#</th><th>Kind</th><th>Terminal</th><th>Planned arrival</th><th>Status</th><th>Arrived</th>
                <th>Departed</th><th></th><th></th></tr>
            </thead>
            <tbody>
              @for (s of stops(); track s.id) {
                <tr data-testid="stop-row" [attr.data-seq]="s.seq">
                  <td>{{ s.seq }}</td><td>{{ s.kind }}</td><td>{{ s.terminalCode }}</td>
                  <td>{{ (s.plannedArrivalAt || '—') | when }}</td>
                  <td><app-status-badge data-testid="stop-status" [value]="s.status" /></td>
                  <td>{{ (s.arrivedAt || '—') | when }}</td><td>{{ (s.departedAt || '—') | when }}</td>
                  <td>
                    @if (s.outOfOrder) { <span class="badge" data-testid="stop-out-of-order">Out of order</span> }
                    @if (s.lateMinutes > 0) { <span class="badge" data-testid="stop-late">{{ s.lateMinutes }} min late</span> }
                    @if (s.skipReason) { <span class="muted">{{ s.skipReason }}</span> }
                  </td>
                  <td>
                    @if (session.can('TRIP_EXECUTE')) {
                      @if (s.status === 'PLANNED') {
                        <button type="button" class="link" data-testid="stop-arrive"
                                [attr.aria-label]="'Arrive at ' + s.terminalCode" (click)="arrive(s)">Arrive</button>
                        <button type="button" class="link" data-testid="stop-skip"
                                [attr.aria-label]="'Skip ' + s.terminalCode" (click)="askSkip(s)">Skip</button>
                      }
                      @if (s.status === 'ARRIVED') {
                        <button type="button" class="link" data-testid="stop-depart"
                                [attr.aria-label]="'Depart from ' + s.terminalCode" (click)="depart(s)">Depart</button>
                      }
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table></div>
        }
      </section>
      <app-trip-money [tripId]="shownTripId()" />
    }
  `,
})
export class TripRoute implements OnInit {
  /** The destructive action waiting for its confirmation, shown by the confirm host in the template. */
  protected readonly pending = signal<PendingConfirm | null>(null);
  private readonly gateway = inject(LmsGateway);
  private readonly location = inject(ActivatedRoute);
  protected readonly session = inject(Session);
  readonly route = signal<Route | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly typed = signal('');
  readonly stops = signal<TripStopView[]>([]);
  readonly stopError = signal('');
  readonly shownTripId = signal('');
  /** The trip the stops belong to: what was looked up, not what is typed since. */
  private shownTrip = '';

  /** A link from another screen names the trip, so the route is shown straight away. */
  ngOnInit(): void {
    const trip = this.location.snapshot.queryParamMap.get('trip');
    if (trip) {
      this.typed.set(trip);
      void this.lookUp();
    }
  }

  async lookUp(): Promise<void> {
    this.route.set(null);
    this.shownTripId.set('');
    this.stops.set([]);
    this.error.set('');
    this.stopError.set('');
    this.loading.set(true);
    try {
      this.shownTrip = this.typed().trim();
      this.route.set(await this.gateway.tripRoute(this.shownTrip));
      this.shownTripId.set(this.shownTrip);
      await this.loadStops();
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }

  arrive(stop: TripStopView): Promise<void> {
    return this.act(() => this.gateway.arriveAtStop(this.shownTrip, stop.id));
  }

  depart(stop: TripStopView): Promise<void> {
    return this.act(() => this.gateway.departFromStop(this.shownTrip, stop.id));
  }

  askSkip(stop: TripStopView): void {
    this.pending.set({
      wording: confirmWording('Skip stop', stop.terminalCode),
      reason: { label: 'Why the stop is skipped', testId: 'stop-skip-reason', placeholder: 'Why', required: true },
      run: (why) => this.act(() => this.gateway.skipStop(this.shownTrip, stop.id, why)),
    });
  }

  /** The stops are read again after every action: the server decides what state a stop is in. */
  private async loadStops(): Promise<void> {
    try {
      this.stops.set(await this.gateway.tripStops(this.shownTrip));
    } catch (e) {
      this.stopError.set((e as Error).message);
    }
  }

  private async act(action: () => Promise<void>): Promise<void> {
    this.stopError.set('');
    try {
      await action();
    } catch (e) {
      this.stopError.set((e as Error).message);
    }
    await this.loadStops();
  }
}
