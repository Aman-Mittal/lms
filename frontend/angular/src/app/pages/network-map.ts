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
import { LmsGateway, Mapped } from '../gateway/lms-gateway';
import { MapView } from './map-view';
import { RowTable } from './row-table';

/** Every site with its geofence, and every vehicle where it last reported. */
@Component({
  selector: 'app-network-map',
  imports: [MapView, RowTable],
  template: `
    <h1>Network map</h1>
    <p class="muted">Sites with their geofences, and each vehicle at its last reported position.</p>
    @if (loading()) { <p class="muted">Loading…</p> }
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (network(); as n) {
      <app-map [features]="n.features" label="Sites and vehicles" />
      <app-row-table testId="sites" [rows]="n.rows" [columns]="columns" />
    }
  `,
})
export class NetworkMap implements OnInit {
  private readonly gateway = inject(LmsGateway);
  readonly network = signal<Mapped | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly columns = [
    { header: 'Code', field: 'code' }, { header: 'Name', field: 'name' },
    { header: 'Category', field: 'functionalCategory' }, { header: 'Geofence', field: 'geofenceType' },
  ];

  async ngOnInit(): Promise<void> {
    try {
      this.network.set(await this.gateway.networkMap());
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }
}
