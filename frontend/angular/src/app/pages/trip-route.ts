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
import { LmsGateway, TripRoute as Route } from '../gateway/lms-gateway';
import { MapView } from './map-view';

/** A trip's track so far and where it is now. */
@Component({
  selector: 'app-trip-route',
  imports: [FormsModule, MapView],
  template: `
    <h1>Trip route</h1>
    <p class="muted">The route a trip has driven so far, from the positions its vehicle reported.</p>
    <form class="inline" (ngSubmit)="lookUp()" data-testid="trip-lookup">
      <input name="tripId" placeholder="Trip id" aria-label="Trip id" data-testid="trip-id" [(ngModel)]="typed" required />
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
    }
  `,
})
export class TripRoute {
  private readonly gateway = inject(LmsGateway);
  readonly route = signal<Route | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  typed = '';

  async lookUp(): Promise<void> {
    this.route.set(null);
    this.error.set('');
    this.loading.set(true);
    try {
      this.route.set(await this.gateway.tripRoute(this.typed.trim()));
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }
}
