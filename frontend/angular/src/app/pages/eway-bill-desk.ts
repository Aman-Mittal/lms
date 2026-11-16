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
import { ApiProblem, LmsGateway } from '../gateway/lms-gateway';
import {
  EWAY_STATUSES, EwayAction, EwayBillRow, EwayFilter, EwayGenerateDraft, EwayPartBResult,
} from '../gateway/eway-types';
import { Session } from '../session';

/** A refusal as the desk shows it: what the server said, and its problem code. */
function problemText(e: unknown): string {
  const problem = e as ApiProblem;
  return problem.code ? `${problem.message} (${problem.code})` : problem.message;
}

const ACTION_DONE: Record<EwayAction, string> = {
  PART_B: 'Part-B updated.',
  EXTEND: 'Validity extended.',
  CANCEL: 'E-way bill cancelled.',
  CHANGE_VEHICLE: 'Vehicle changed.',
};

/**
 * The e-way bill desk (statutory, per consignment): the bills with their validity and Part-B vehicle, those
 * about to lapse, a lookup by bill number, generating a consignment's bill through the unit's adapter, and
 * Part-B for a whole trip with one outcome per consignment. Every refusal shows its problem code.
 */
@Component({
  selector: 'app-eway-bill-desk',
  imports: [FormsModule],
  template: `
    <h1>E-way bills</h1>
    <p class="muted">Generate a consignment's bill, put a vehicle on Part-B, extend or cancel it, and watch what is about to lapse.</p>

    <form class="inline" (ngSubmit)="load()" data-testid="eway-filter">
      <select aria-label="Status" data-testid="eway-filter-status" [value]="filter().status"
              (change)="setFilter('status', $any($event.target).value)">
        <option value="" [selected]="!filter().status">Any status</option>
        @for (s of statuses; track s) { <option [value]="s" [selected]="s === filter().status">{{ s }}</option> }
      </select>
      <input name="q" placeholder="Bill number or consignment" aria-label="Search e-way bills" data-testid="eway-filter-q"
             [value]="filter().q" (input)="setFilter('q', $any($event.target).value)" />
      <button type="submit" data-testid="eway-filter-apply">Show</button>
    </form>
    @if (error()) { <p class="error" role="alert" data-testid="eway-error">{{ error() }}</p> }
    @if (note()) { <p class="ok" role="status" data-testid="eway-note">{{ note() }}</p> }

    <table data-testid="eway-list">
      <thead><tr><th>Bill</th><th>Consignment</th><th>Status</th><th>Valid until</th><th>Vehicle</th><th></th></tr></thead>
      <tbody>
        @for (r of rows(); track r.id) {
          <tr data-testid="eway-row" [attr.data-id]="r.id">
            <td data-testid="eway-row-no">{{ r.billNo }}</td>
            <td data-testid="eway-row-consignment">{{ r.trackingRef }}</td>
            <td data-testid="eway-row-status">{{ r.status }}</td>
            <td data-testid="eway-row-valid-until">{{ r.validUntil }}</td>
            <td data-testid="eway-row-vehicle">{{ r.vehicleNo || '—' }}</td>
            <td><button class="link" data-testid="eway-row-open" (click)="open(r.id)">Open</button></td>
          </tr>
        }
      </tbody>
    </table>
    @if (rows().length === 0 && loaded()) { <p class="muted" data-testid="eway-none">No e-way bills.</p> }

    <h2>Expiring soon</h2>
    <table data-testid="eway-expiring">
      <thead><tr><th>Bill</th><th>Consignment</th><th>Valid until</th><th></th></tr></thead>
      <tbody>
        @for (r of expiring(); track r.id) {
          <tr data-testid="eway-expiring-row" [attr.data-id]="r.id">
            <td data-testid="eway-expiring-no">{{ r.billNo }}</td>
            <td>{{ r.trackingRef }}</td>
            <td data-testid="eway-expiring-valid-until">{{ r.validUntil }}</td>
            <td><button class="link" data-testid="eway-expiring-open" (click)="open(r.id)">Open</button></td>
          </tr>
        }
      </tbody>
    </table>
    @if (expiring().length === 0 && loaded()) { <p class="muted" data-testid="eway-expiring-none">Nothing is about to lapse.</p> }

    <h2>Find a consignment by bill number</h2>
    <form class="inline" (ngSubmit)="lookup()" data-testid="eway-lookup-form">
      <input name="lookupNo" placeholder="Bill number" aria-label="Bill number" data-testid="eway-lookup-no"
             [value]="lookupNo()" (input)="lookupNo.set($any($event.target).value)" />
      <button type="submit" data-testid="eway-lookup">Find</button>
    </form>
    @if (found(); as f) {
      <p data-testid="eway-lookup-result" [attr.data-id]="f.id">
        Bill <span data-testid="eway-lookup-bill">{{ f.billNo }}</span> covers consignment
        <span data-testid="eway-lookup-consignment" [attr.data-id]="f.consignmentId">{{ f.trackingRef }}</span>
        (<span data-testid="eway-lookup-status">{{ f.status }}</span>)
        <button class="link" data-testid="eway-lookup-open" (click)="open(f.id)">Open</button>
      </p>
    }
    @if (lookupMissing()) { <p class="muted" data-testid="eway-lookup-missing">No bill has that number.</p> }

    <h2>A consignment's bills</h2>
    <form class="inline" (ngSubmit)="showConsignment()" data-testid="eway-consignment-form">
      <input name="consignmentId" placeholder="Consignment id" aria-label="Consignment id" data-testid="eway-consignment-id"
             [value]="draft().consignmentId" (input)="setDraft('consignmentId', $any($event.target).value)" />
      <button type="submit" data-testid="eway-consignment-show">Show bills</button>
      @if (session.can('EWAY_BILL_MANAGE')) {
        <input name="distance" inputmode="numeric" placeholder="Distance (km)" aria-label="Distance in kilometres"
               data-testid="eway-generate-distance" [value]="draft().distanceKm"
               (input)="setDraft('distanceKm', $any($event.target).value)" />
        <input name="document" placeholder="Document (optional)" aria-label="Document reference"
               data-testid="eway-generate-document" [value]="draft().documentRef"
               (input)="setDraft('documentRef', $any($event.target).value)" />
        <button type="button" data-testid="eway-generate" (click)="generate()">Generate bill</button>
      }
    </form>
    <table data-testid="eway-consignment-bills">
      <thead><tr><th>Bill</th><th>Status</th><th>Valid until</th><th></th></tr></thead>
      <tbody>
        @for (r of consignmentBills(); track r.id) {
          <tr data-testid="eway-consignment-bill" [attr.data-id]="r.id">
            <td data-testid="eway-consignment-bill-no">{{ r.billNo }}</td>
            <td data-testid="eway-consignment-bill-status">{{ r.status }}</td>
            <td>{{ r.validUntil }}</td>
            <td><button class="link" data-testid="eway-consignment-bill-open" (click)="open(r.id)">Open</button></td>
          </tr>
        }
      </tbody>
    </table>

    @if (selected(); as b) {
      <section data-testid="eway-detail" [attr.data-id]="b.id">
        <h2>E-way bill <span data-testid="eway-detail-no">{{ b.billNo }}</span></h2>
        <dl>
          <div><dt>Status</dt><dd data-testid="eway-detail-status">{{ b.status }}</dd></div>
          <div><dt>Consignment</dt><dd data-testid="eway-detail-consignment" [attr.data-id]="b.consignmentId">{{ b.trackingRef }}</dd></div>
          <div><dt>Valid until</dt><dd data-testid="eway-detail-valid-until">{{ b.validUntil }}</dd></div>
          <div><dt>Part-B vehicle</dt><dd data-testid="eway-detail-vehicle">{{ b.vehicleNo || '—' }}</dd></div>
          <div><dt>Extensions</dt><dd data-testid="eway-detail-extensions">{{ b.extensionCount }}</dd></div>
          @if (b.expiringSoon) { <div><dt>Expiry</dt><dd data-testid="eway-detail-expiring">Expiring soon</dd></div> }
          @if (b.cancelReason) { <div><dt>Cancelled because</dt><dd data-testid="eway-detail-cancel-reason">{{ b.cancelReason }}</dd></div> }
        </dl>
        @if (session.can('EWAY_BILL_MANAGE') && (b.status === 'ACTIVE' || b.status === 'EXTENDED')) {
          <form class="inline" (ngSubmit)="act(b, 'PART_B')" data-testid="eway-vehicle-form">
            <input name="vehicle" placeholder="Vehicle number" aria-label="Vehicle number" data-testid="eway-vehicle"
                   [value]="vehicle()" (input)="vehicle.set($any($event.target).value)" />
            <button type="submit" data-testid="eway-part-b">Update Part-B</button>
            <button type="button" data-testid="eway-change-vehicle" (click)="act(b, 'CHANGE_VEHICLE')">Change vehicle</button>
          </form>
          <form class="inline" (submit)="$event.preventDefault()" data-testid="eway-reason-form">
            <input name="reason" placeholder="Reason" aria-label="Reason" data-testid="eway-reason"
                   [value]="reason()" (input)="reason.set($any($event.target).value)" />
            <button type="button" data-testid="eway-extend" (click)="act(b, 'EXTEND')">Extend validity</button>
            <button type="button" data-testid="eway-cancel" (click)="act(b, 'CANCEL')">Cancel bill</button>
          </form>
        }
      </section>
    }

    @if (session.can('EWAY_BILL_MANAGE')) {
      <h2>Part-B for a trip</h2>
      <form class="inline" (ngSubmit)="tripPartB()" data-testid="eway-trip-form">
        <input name="tripId" placeholder="Trip id" aria-label="Trip id" data-testid="eway-trip-id"
               [value]="tripId()" (input)="tripId.set($any($event.target).value)" />
        <input name="tripVehicle" placeholder="Vehicle (blank: the trip's)" aria-label="Trip vehicle number"
               data-testid="eway-trip-vehicle" [value]="tripVehicle()" (input)="tripVehicle.set($any($event.target).value)" />
        <button type="submit" data-testid="eway-trip-part-b">Update Part-B for the trip</button>
      </form>
      @if (tripResult(); as t) {
        <p data-testid="eway-trip-summary">{{ t.updated }} of {{ t.items.length }} consignments updated for vehicle {{ t.vehicleNo }}.</p>
        <table data-testid="eway-trip-results">
          <thead><tr><th>Consignment</th><th>Bill</th><th>Outcome</th><th>Code</th></tr></thead>
          <tbody>
            @for (i of t.items; track i.consignmentId) {
              <tr data-testid="eway-trip-item" [attr.data-id]="i.consignmentId">
                <td>{{ i.trackingRef }}</td>
                <td>{{ i.billNo || '—' }}</td>
                <td data-testid="eway-trip-item-outcome">{{ i.outcome }}</td>
                <td data-testid="eway-trip-item-code">{{ i.code || '—' }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
})
export class EwayBillDeskPage {
  private readonly gateway = inject(LmsGateway);
  protected readonly session = inject(Session);
  protected readonly statuses = EWAY_STATUSES;

  readonly filter = signal<EwayFilter>({ status: '', q: '' });
  readonly rows = signal<EwayBillRow[]>([]);
  readonly expiring = signal<EwayBillRow[]>([]);
  readonly loaded = signal(false);
  readonly error = signal('');
  readonly note = signal('');
  readonly selected = signal<EwayBillRow | null>(null);

  readonly lookupNo = signal('');
  readonly found = signal<EwayBillRow | null>(null);
  readonly lookupMissing = signal(false);

  readonly draft = signal<EwayGenerateDraft>({ consignmentId: '', documentRef: '', distanceKm: '', vehicleNo: '' });
  readonly consignmentBills = signal<EwayBillRow[]>([]);

  readonly vehicle = signal('');
  readonly reason = signal('');
  readonly tripId = signal('');
  readonly tripVehicle = signal('');
  readonly tripResult = signal<EwayPartBResult | null>(null);

  constructor() {
    void this.load();
  }

  setFilter(key: keyof EwayFilter, value: string): void {
    this.filter.update((f) => ({ ...f, [key]: value }));
  }

  setDraft(key: keyof EwayGenerateDraft, value: string): void {
    this.draft.update((d) => ({ ...d, [key]: value }));
  }

  /** Reads the list and the expiring-soon list again. */
  async load(): Promise<void> {
    this.error.set('');
    try {
      this.rows.set((await this.gateway.ewayBills(this.filter())).rows);
      this.expiring.set((await this.gateway.expiringEwayBills()).rows);
      this.loaded.set(true);
    } catch (e) {
      this.error.set(problemText(e));
    }
  }

  async open(id: string): Promise<void> {
    this.error.set('');
    this.note.set('');
    try {
      this.selected.set(await this.gateway.ewayBill(id));
    } catch (e) {
      this.error.set(problemText(e));
    }
  }

  async lookup(): Promise<void> {
    this.error.set('');
    this.found.set(null);
    this.lookupMissing.set(false);
    if (!this.lookupNo().trim()) {
      return;
    }
    try {
      this.found.set(await this.gateway.ewayBillByNumber(this.lookupNo()));
    } catch (e) {
      if ((e as ApiProblem).status === 404) {
        this.lookupMissing.set(true);
      } else {
        this.error.set(problemText(e));
      }
    }
  }

  async showConsignment(): Promise<void> {
    this.error.set('');
    if (!this.draft().consignmentId.trim()) {
      this.error.set('Enter the consignment id.');
      return;
    }
    try {
      this.consignmentBills.set(await this.gateway.consignmentEwayBills(this.draft().consignmentId));
    } catch (e) {
      this.error.set(problemText(e));
    }
  }

  async generate(): Promise<void> {
    this.error.set('');
    this.note.set('');
    if (!this.draft().consignmentId.trim()) {
      this.error.set('Enter the consignment id.');
      return;
    }
    try {
      const id = await this.gateway.generateEwayBill(this.draft());
      await this.load();
      await this.showConsignment();
      await this.open(id);
      this.note.set('E-way bill generated.');
    } catch (e) {
      this.error.set(problemText(e));
    }
  }

  async act(bill: EwayBillRow, action: EwayAction): Promise<void> {
    this.error.set('');
    this.note.set('');
    try {
      this.selected.set(await this.gateway.actOnEwayBill(bill.id, action, this.reason(), this.vehicle()));
      this.note.set(ACTION_DONE[action]);
      await this.load();
      if (this.draft().consignmentId.trim()) {
        await this.showConsignment();
      }
    } catch (e) {
      this.error.set(problemText(e));
    }
  }

  async tripPartB(): Promise<void> {
    this.error.set('');
    this.tripResult.set(null);
    if (!this.tripId().trim()) {
      this.error.set('Enter the trip id.');
      return;
    }
    try {
      this.tripResult.set(await this.gateway.tripEwayPartB(this.tripId(), this.tripVehicle()));
      await this.load();
    } catch (e) {
      this.error.set(problemText(e));
    }
  }
}
