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

/** Milk-run routes, one route's circuit on the map, and the runs generated from it. */
@Component({
  selector: 'app-milk-runs',
  imports: [MapView, RowTable],
  template: `
    <h1>Milk runs</h1>
    <p class="muted">Scheduled pickup circuits: from the plant, round the suppliers in order, and back.</p>
    @if (loading()) { <p class="muted">Loading…</p> }
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (!loading() && !error() && list().length === 0) {
      <p class="muted" data-testid="no-routes">No milk-run routes yet.</p>
    }
    @if (list().length > 0) {
      <app-row-table testId="routes" [rows]="list()" [columns]="columns" [pickable]="true"
                     [picked]="picked()" (pick)="open($event)" />
    }
    @if (detailError()) { <p class="error" role="alert">{{ detailError() }}</p> }
    @if (detail(); as d) {
      <section data-testid="route-detail">
        <h2>{{ d.header['code'] }} · {{ d.header['name'] }}</h2>
        <app-map [features]="d.features" [label]="'Circuit of ' + d.header['code']" />
        <app-row-table testId="stops" [rows]="d.parts" [columns]="stopColumns" />
        <h3>Runs</h3>
        @if (runs().length === 0) {
          <p class="muted" data-testid="no-runs">No runs generated yet.</p>
        } @else {
          <app-row-table testId="runs" [rows]="runs()" [columns]="runColumns" />
        }
      </section>
    }
  `,
})
export class MilkRuns implements OnInit {
  private readonly gateway = inject(LmsGateway);
  readonly list = signal<Row[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly picked = signal('');
  readonly detail = signal<MappedDetail | null>(null);
  readonly runs = signal<Row[]>([]);
  readonly detailError = signal('');
  readonly columns = [
    { header: 'Route', field: 'code' }, { header: 'Name', field: 'name' }, { header: 'Plant', field: 'plantCode' },
    { header: 'Days', field: 'daysOfWeek' }, { header: 'Departs', field: 'departureTime' },
    { header: 'From', field: 'effectiveFrom' },
  ];
  readonly stopColumns = [
    { header: '#', field: 'sequence' }, { header: 'Stop', field: 'terminalCode' },
    { header: 'Name', field: 'terminalName' }, { header: 'Window opens (min)', field: 'windowOpenMin' },
    { header: 'Window closes (min)', field: 'windowCloseMin' }, { header: 'Dwell (min)', field: 'plannedDwellMin' },
  ];
  readonly runColumns = [
    { header: 'Date', field: 'runDate' }, { header: 'Status', field: 'status' }, { header: 'Trip', field: 'tripNo' },
    { header: 'Visited', field: 'stopsVisited' }, { header: 'Skipped', field: 'stopsSkipped' },
    { header: 'Charge', field: 'chargeAmount' },
  ];

  async ngOnInit(): Promise<void> {
    try {
      this.list.set(await this.gateway.milkRunRoutes());
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }

  async open(row: Row): Promise<void> {
    this.picked.set(row['id']);
    this.detail.set(null);
    this.runs.set([]);
    this.detailError.set('');
    try {
      const [detail, runs] = await Promise.all([
        this.gateway.milkRunRoute(row['id']), this.gateway.circuitRuns(row['code']),
      ]);
      this.detail.set(detail);
      this.runs.set(runs);
    } catch (e) {
      this.detailError.set((e as Error).message);
    }
  }
}
