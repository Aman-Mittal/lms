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

import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiProblem, LmsGateway, LoadBuild, MAX_BULK_ASSIGNMENT, Row } from '../gateway/lms-gateway';
import { Session } from '../session';
import { EntityPicker } from './entity-picker';

/** A load's states in which more freight may still go on. */
const BUILDABLE = ['DRAFT', 'PLANNED'];

/** What is aboard, in drop order; on screen and in the printed manifest alike. */
@Component({
  selector: 'app-consignment-table',
  template: `
    <table [attr.data-testid]="testId()">
      <thead>
        <tr><th>#</th><th>Tracking ref</th><th>Consignee</th><th>Destination</th><th>Weight kg</th><th>Classes</th><th>Hazmat</th></tr>
      </thead>
      <tbody>
        @for (c of rows(); track c['id']; let i = $index) {
          <tr [attr.data-testid]="rowTestId()">
            <td>{{ i + 1 }}</td>
            <td>{{ c['trackingRef'] }}</td>
            <td>{{ c['consigneeName'] }}</td>
            <td>{{ c['destinationTerminalCode'] }}</td>
            <td>{{ c['totalDeadWeightKg'] }}</td>
            <td>{{ c['materialClasses'] }}</td>
            <td>{{ c['containsHazmat'] }}</td>
          </tr>
        }
      </tbody>
    </table>
  `,
})
export class ConsignmentTable {
  readonly rows = input.required<Row[]>();
  readonly testId = input.required<string>();
  readonly rowTestId = input.required<string>();
}

/** Finds waiting consignments, picks them, and puts them on the load together. */
@Component({
  selector: 'app-add-consignments',
  imports: [FormsModule, EntityPicker],
  template: `
    <section data-testid="add-panel">
      <h3>Add consignments</h3>
      <!-- A signal, not ngModel: ngModel connects after the screen is shown, and would wipe an id typed before it. -->
      <form class="inline" (ngSubmit)="find()" data-testid="find-unassigned">
        <app-entity-picker [kind]="'order'" placeholder="Order (optional)" ariaLabel="Order id" testId="order-filter"
                           [(value)]="orderTyped" />
        <button type="submit">Find waiting consignments</button>
      </form>
      @if (added() > 0) { <p class="ok" data-testid="bulk-added">Added {{ added() }} consignments to the load.</p> }
      @if (error()) { <p class="error" role="alert" data-testid="bulk-error">{{ error() }}</p> }
      @if (refusals().length > 0) {
        <ul data-testid="bulk-refusals">
          @for (r of refusals(); track r[0]) {
            <li data-testid="refusal"><strong>{{ r[0] }}</strong>: {{ r[1] }}</li>
          }
        </ul>
      }
      @if (searched() && rows().length === 0) {
        <p class="muted" data-testid="queue-empty">No consignments are waiting.</p>
      }
      @if (rows().length > 0) {
        <p class="actions">
          <button type="button" data-testid="add-selected" (click)="add()"
                  [disabled]="picked().length === 0 || picked().length > max">
            Add selected ({{ picked().length }})
          </button>
          @if (picked().length > max) { <span class="error"> At most {{ max }} at a time.</span> }
        </p>
        <div class="table-scroll">
          <table data-testid="queue-table">
            <thead>
              <tr>
                <th>
                  <input type="checkbox" aria-label="Select all shown" data-testid="select-all"
                         [checked]="allPicked()" (change)="toggleAll()" />
                </th>
                <th>Tracking ref</th><th>Consignee</th><th>Destination</th><th>Weight kg</th><th>Classes</th><th>Hazmat</th>
              </tr>
            </thead>
            <tbody>
              @for (c of rows(); track c['id']) {
                <tr data-testid="queue-row">
                  <td>
                    <input type="checkbox" [attr.aria-label]="'Select ' + c['trackingRef']" data-testid="pick"
                           [checked]="picked().includes(c['id'])" (change)="toggle(c['id'])" />
                  </td>
                  <td>{{ c['trackingRef'] }}</td>
                  <td>{{ c['consigneeName'] }}</td>
                  <td>{{ c['destinationTerminalCode'] }}</td>
                  <td>{{ c['totalDeadWeightKg'] }}</td>
                  <td>{{ c['materialClasses'] }}</td>
                  <td>{{ c['containsHazmat'] }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        @if (nextCursor()) {
          <p><button type="button" class="link" data-testid="more-unassigned" (click)="more()">Show more</button></p>
        }
      }
    </section>
  `,
})
export class AddConsignments {
  private readonly gateway = inject(LmsGateway);

  readonly loadId = input.required<string>();
  /** The batch boarded; the load should be read again. */
  readonly boarded = output<void>();

  readonly max = MAX_BULK_ASSIGNMENT;
  readonly orderTyped = signal('');
  readonly rows = signal<Row[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly searched = signal(false);
  /** Insertion-ordered: consignments board in the order they were picked. */
  readonly picked = signal<string[]>([]);
  readonly error = signal('');
  readonly refusals = signal<Array<[string, string]>>([]);
  readonly added = signal(0);
  readonly allPicked = computed(() => {
    const rows = this.rows();
    const picked = this.picked();
    return rows.length > 0 && rows.every((r) => picked.includes(r['id']));
  });

  async find(): Promise<void> {
    this.error.set('');
    this.picked.set([]);
    try {
      const page = await this.gateway.unassignedConsignments(this.orderTyped().trim() || undefined);
      this.rows.set(page.rows);
      this.nextCursor.set(page.nextCursor);
      this.searched.set(true);
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  async more(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor) {
      return;
    }
    try {
      const page = await this.gateway.unassignedConsignments(this.orderTyped().trim() || undefined, cursor);
      this.rows.update((shown) => [...shown, ...page.rows]);
      this.nextCursor.set(page.nextCursor);
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  toggle(id: string): void {
    this.picked.update((now) => (now.includes(id) ? now.filter((p) => p !== id) : [...now, id]));
  }

  toggleAll(): void {
    this.picked.set(this.allPicked() ? [] : this.rows().map((r) => r['id']));
  }

  async add(): Promise<void> {
    const batch = this.picked();
    this.error.set('');
    this.refusals.set([]);
    this.added.set(0);
    try {
      await this.gateway.assignConsignments(this.loadId(), batch);
      this.added.set(batch.length);
      this.picked.set([]);
      this.rows.update((shown) => shown.filter((r) => !batch.includes(r['id'])));
      this.boarded.emit();
    } catch (e) {
      this.error.set((e as Error).message);
      if (e instanceof ApiProblem) {
        this.refusals.set(Object.entries(e.errors));
      }
    }
  }
}

/**
 * Builds a load: what is aboard, in drop order, with a printable manifest, and
 * the consignments still waiting, which are picked and put on together.
 */
@Component({
  selector: 'app-load-building',
  imports: [FormsModule, ConsignmentTable, AddConsignments, EntityPicker],
  template: `
    <h1>Load building</h1>
    <p class="muted">
      Open a load to see what is aboard, print its manifest, and put waiting consignments on it,
      up to {{ max }} at a time.
    </p>
    <!-- A signal, not ngModel: ngModel connects after the screen is shown, and would wipe an id typed before it. -->
    <form class="inline" (ngSubmit)="open()" data-testid="open-load">
      <app-entity-picker [kind]="'load'" placeholder="Load" ariaLabel="Load id" testId="load-id" [required]="true"
                         [(value)]="typed" />
      <button type="submit">Open</button>
    </form>
    @if (loadError()) { <p class="error" role="alert">{{ loadError() }}</p> }
    @if (build(); as b) {
      <section data-testid="load-panel">
        <h2>{{ b.load['loadNo'] }}</h2>
        <dl>
          <div><dt>Status</dt><dd data-testid="load-status">{{ b.load['status'] }}</dd></div>
          <div><dt>Vehicle</dt><dd>{{ b.load['vehicleRegistrationNo'] || '—' }}</dd></div>
          <div>
            <dt>Weight</dt>
            <dd data-testid="load-weight">
              {{ b.load['plannedWeightKg'] }} of {{ b.load['capacityWeightKg'] }} kg ({{ b.load['weightUtilisationPct'] }}%)
            </dd>
          </div>
          <div><dt>Consignments</dt><dd data-testid="load-count">{{ b.load['consignmentCount'] }}</dd></div>
        </dl>
        <p>
          <button type="button" class="link" data-testid="print-manifest" (click)="manifest.set(true)"
                  [disabled]="b.aboard.length === 0">
            Print manifest
          </button>
        </p>
        @if (b.aboard.length === 0) {
          <p class="muted" data-testid="nothing-aboard">Nothing is aboard yet.</p>
        } @else {
          <div class="table-scroll">
            <app-consignment-table [rows]="b.aboard" testId="aboard-table" rowTestId="aboard-row" />
          </div>
        }
        @if (manifest()) {
          <section class="slip manifest" data-testid="manifest" aria-label="Load manifest">
            <h3>Manifest · {{ b.load['loadNo'] }}</h3>
            <p>
              {{ b.load['vehicleRegistrationNo'] || 'No vehicle' }} · {{ b.aboard.length }} consignments ·
              {{ b.load['plannedWeightKg'] }} kg
            </p>
            <app-consignment-table [rows]="b.aboard" testId="manifest-table" rowTestId="manifest-row" />
            <p class="actions no-print">
              <button data-testid="print-now" (click)="print()">Print</button>
              <button class="link" (click)="manifest.set(false)">Close</button>
            </p>
          </section>
        }
        @if (buildable()) {
          <app-add-consignments [loadId]="b.load['id']" (boarded)="refresh()" />
        }
      </section>
    }
  `,
})
export class LoadBuilding {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly max = MAX_BULK_ASSIGNMENT;
  readonly typed = signal('');
  readonly loadId = signal('');
  readonly build = signal<LoadBuild | null>(null);
  readonly loadError = signal('');
  readonly manifest = signal(false);
  readonly buildable = computed(() => {
    const b = this.build();
    return !!b && this.session.can('LOAD_CREATE') && BUILDABLE.includes(b.load['status']);
  });

  async open(): Promise<void> {
    const id = this.typed().trim().toLowerCase();
    if (id !== this.loadId()) {
      this.build.set(null);
      this.manifest.set(false);
    }
    this.loadId.set(id);
    await this.refresh();
  }

  /** Re-reads the load in place: what was just added, and the queue it came from, stay on screen. */
  async refresh(): Promise<void> {
    this.loadError.set('');
    try {
      this.build.set(await this.gateway.loadBuild(this.loadId()));
    } catch (e) {
      this.build.set(null);
      this.loadError.set((e as Error).message);
    }
  }

  print(): void {
    window.print();
  }
}
