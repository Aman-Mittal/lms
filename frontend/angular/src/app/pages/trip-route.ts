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

/** A trip's track so far and where it is now. */
@Component({
  selector: 'app-trip-route',
  imports: [FormsModule, MapView, EntityPicker],
  template: `
    <h1>Trip route</h1>
    <p class="muted">The route a trip has driven so far, from the positions its vehicle reported.</p>
    <!-- A signal, not ngModel: ngModel connects after the screen is shown, and would wipe an id typed before it. -->
    <form class="inline" (ngSubmit)="lookUp()" data-testid="trip-lookup">
      <app-entity-picker [kind]="'trip'" placeholder="Trip" ariaLabel="Trip id" testId="trip-id" [required]="true"
                         [(value)]="typed" />
      <button type="submit">Show route</button>
    </form>
    @if (loading()) { <p class="muted">Loading…</p> }
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (route(); as r) {
      <section data-testid="trip-route">
        <dl class="facts">
          <div><dt>Trip</dt><dd data-testid="route-trip-no">{{ r.tripNo }}</dd></div>
          <div><dt>Status</dt><dd>{{ r.status }}</dd></div>
          <div><dt>From</dt><dd>{{ r.originCode }}</dd></div>
          <div><dt>To</dt><dd>{{ r.destinationCode }}</dd></div>
          <div><dt>Last fix</dt><dd>{{ r.lastFixAt || '—' }}</dd></div>
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
          <p class="error" role="alert" data-testid="stop-error">{{ stopError() }}</p>
        }
        @if (stops().length === 0) {
          <p class="muted" data-testid="stops-empty">This trip has no planned stops.</p>
        } @else {
          <table data-testid="stops">
            <thead>
              <tr><th>#</th><th>Kind</th><th>Terminal</th><th>Planned arrival</th><th>Status</th><th>Arrived</th>
                <th>Departed</th><th></th><th></th></tr>
            </thead>
            <tbody>
              @for (s of stops(); track s.id) {
                <tr data-testid="stop-row" [attr.data-seq]="s.seq">
                  <td>{{ s.seq }}</td><td>{{ s.kind }}</td><td>{{ s.terminalCode }}</td>
                  <td>{{ s.plannedArrivalAt || '—' }}</td>
                  <td><span class="badge" data-testid="stop-status">{{ s.status }}</span></td>
                  <td>{{ s.arrivedAt || '—' }}</td><td>{{ s.departedAt || '—' }}</td>
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
                                [attr.aria-label]="'Skip ' + s.terminalCode" (click)="startSkip(s.id)">Skip</button>
                      }
                      @if (s.status === 'ARRIVED') {
                        <button type="button" class="link" data-testid="stop-depart"
                                [attr.aria-label]="'Depart from ' + s.terminalCode" (click)="depart(s)">Depart</button>
                      }
                    }
                  </td>
                </tr>
                @if (skipping() === s.id) {
                  <tr data-testid="stop-skip-row">
                    <td colspan="9">
                      <form class="inline" data-testid="stop-skip-form" (submit)="skip($event, s)">
                        <input placeholder="Why" aria-label="Skip reason" data-testid="stop-skip-reason" required
                               [value]="skipReason()" (input)="skipReason.set($any($event.target).value)" />
                        <button type="submit" data-testid="stop-skip-confirm">Skip stop</button>
                        <button type="button" class="link" (click)="skipping.set('')">Keep</button>
                      </form>
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        }
      </section>
    }
  `,
})
export class TripRoute implements OnInit {
  private readonly gateway = inject(LmsGateway);
  private readonly location = inject(ActivatedRoute);
  protected readonly session = inject(Session);
  readonly route = signal<Route | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly typed = signal('');
  readonly stops = signal<TripStopView[]>([]);
  readonly stopError = signal('');
  readonly skipping = signal('');
  readonly skipReason = signal('');
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
    this.stops.set([]);
    this.error.set('');
    this.stopError.set('');
    this.loading.set(true);
    try {
      this.shownTrip = this.typed().trim();
      this.route.set(await this.gateway.tripRoute(this.shownTrip));
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

  startSkip(id: string): void {
    this.skipping.set(id);
    this.skipReason.set('');
  }

  async skip(event: Event, stop: TripStopView): Promise<void> {
    event.preventDefault();
    await this.act(() => this.gateway.skipStop(this.shownTrip, stop.id, this.skipReason()));
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
      this.skipping.set('');
    } catch (e) {
      this.stopError.set((e as Error).message);
    }
    await this.loadStops();
  }
}
