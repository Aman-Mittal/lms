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
import { LmsGateway, MappedDetail, Row } from '../gateway/lms-gateway';
import { MapView } from './map-view';
import { RowTable } from './row-table';

/** Road-rail-road itineraries, and one itinerary's legs on the map. */
@Component({
  selector: 'app-itineraries',
  imports: [MapView, RowTable],
  template: `
    <h1>Itineraries</h1>
    <p class="muted">Multimodal movements leg by leg: road to the railhead, rail between railheads, road to the customer.</p>
    @if (loading()) { <p class="muted">Loading…</p> }
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (!loading() && !error() && list().length === 0) {
      <p class="muted" data-testid="no-itineraries">No itineraries yet.</p>
    }
    @if (list().length > 0) {
      <app-row-table testId="itineraries" [rows]="list()" [columns]="columns" [pickable]="true"
                     [picked]="picked()" (pick)="open($event)" />
    }
    @if (detailError()) { <p class="error" role="alert">{{ detailError() }}</p> }
    @if (detail(); as d) {
      <section data-testid="itinerary-detail">
        <h2>{{ d.header['itineraryNo'] }} · {{ d.header['shipmentRef'] }}</h2>
        <app-map [features]="d.features" [label]="'Legs of ' + d.header['itineraryNo']" />
        <app-row-table testId="legs" [rows]="d.parts" [columns]="legColumns" />
      </section>
    }
  `,
})
export class Itineraries implements OnInit {
  private readonly gateway = inject(LmsGateway);
  readonly list = signal<Row[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly picked = signal('');
  readonly detail = signal<MappedDetail | null>(null);
  readonly detailError = signal('');
  readonly columns = [
    { header: 'Itinerary', field: 'itineraryNo' }, { header: 'Shipment', field: 'shipmentRef' },
    { header: 'Status', field: 'status' }, { header: 'Legs', field: 'legCount' },
    { header: 'Cost', field: 'totalCost' }, { header: 'Connection', field: 'connectionMissed' },
  ];
  readonly legColumns = [
    { header: '#', field: 'seq' }, { header: 'Mode', field: 'mode' }, { header: 'From', field: 'from' },
    { header: 'To', field: 'to' }, { header: 'Status', field: 'status' },
    { header: 'Planned departure', field: 'plannedDepartureAt' }, { header: 'Planned arrival', field: 'plannedArrivalAt' },
    { header: 'Carrier', field: 'carrier' }, { header: 'Reference', field: 'reference' },
  ];

  async ngOnInit(): Promise<void> {
    try {
      this.list.set(await this.gateway.itineraries());
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }

  async open(row: Row): Promise<void> {
    this.picked.set(row['id']);
    this.detail.set(null);
    this.detailError.set('');
    try {
      this.detail.set(await this.gateway.itinerary(row['id']));
    } catch (e) {
      this.detailError.set((e as Error).message);
    }
  }
}
