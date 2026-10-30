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
import { RouterLink } from '@angular/router';
import { ControlTowerSummary, LmsGateway } from '../gateway/lms-gateway';
import { RESOURCES } from './resources';

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink],
  template: `
    <h1>Control tower</h1>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (summary(); as s) {
      <div class="tiles" data-testid="tiles">
        <div class="tile"><span>{{ s.openP1 }}</span>Open P1</div>
        <div class="tile"><span>{{ s.openP2 }}</span>Open P2</div>
        <div class="tile"><span>{{ s.openP3 }}</span>Open P3</div>
        <div class="tile"><span>{{ s.unassigned }}</span>Unassigned</div>
        <div class="tile"><span>{{ s.settlementHolds }}</span>Settlement holds</div>
        <div class="tile"><span>{{ s.openWithoutTrip }}</span>Without a trip</div>
      </div>
    } @else if (!error()) {
      <p>Loading…</p>
    }
    <h2>Go to</h2>
    <div class="links">
      @for (r of resources; track r.key) { <a [routerLink]="['/', r.key]">{{ r.title }}</a> }
    </div>
  `,
})
export class Dashboard {
  readonly resources = RESOURCES;
  readonly summary = signal<ControlTowerSummary | null>(null);
  readonly error = signal('');

  constructor() {
    inject(LmsGateway).summary().then(
      (s) => this.summary.set(s),
      (e: Error) => this.error.set(e.message),
    );
  }
}
