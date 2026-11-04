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
import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  AllocationDetail, ApiProblem, Choice, FreightBillDetail, GUIDE_STRATEGIES, GuideDraft, GuideStrategy, LmsGateway,
  SLAB_RATE_BASES, SlabRateBasis, RoutingGuideDetail, Row, SlabDraft, TariffDraft,
} from '../gateway/lms-gateway';
// Rate cards and rail tariffs
import type { RailTariffDraft, TariffCardDetail, TariffFilter } from '../gateway/lms-gateway';
import { Session } from '../session';
import { EntityPicker } from './entity-picker';

/** A refusal as the desk shows it: the problem's stable code, when it has one, and its message. */
export interface Refusal {
  code: string;
  message: string;
}

function refusalOf(e: unknown): Refusal {
  return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
}

/** Today as a `YYYY-MM-DD` day, in UTC like the API. */
function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/** A refusal, named by its code so the fix can be looked up. */
@Component({
  selector: 'app-refusal',
  template: `
    @if (refusal(); as r) {
      <p class="error" role="alert" [attr.data-testid]="testId()">
        @if (r.code) { <code data-testid="refusal-code">{{ r.code }}</code> }
        {{ r.message }}
      </p>
    }
  `,
})
export class RefusalNote {
  readonly refusal = input<Refusal | null>(null);
  readonly testId = input('refusal');
}

/** A plain table of rows, one column per field. */
@Component({
  selector: 'app-commercial-table',
  template: `
    <table>
      <thead>
        <tr>@for (c of columns(); track c[0]) { <th>{{ c[1] }}</th> }</tr>
      </thead>
      <tbody>
        @for (row of rows(); track $index) {
          <tr [attr.data-testid]="rowTestId()">
            @for (c of columns(); track c[0]) { <td>{{ row[c[0]] }}</td> }
          </tr>
        }
      </tbody>
    </table>
  `,
})
export class CommercialTable {
  readonly rows = input.required<Row[]>();
  readonly columns = input.required<Array<[string, string]>>();
  readonly rowTestId = input.required<string>();
}

const SLAB_COLUMNS: Array<[string, string]> = [
  ['minWeightKg', 'From kg'], ['maxWeightKg', 'To kg'], ['rateBasis', 'Basis'], ['rateAmount', 'Rate'],
];
const ENTRY_COLUMNS: Array<[string, string]> = [
  ['rankNo', 'Rank'], ['vendorName', 'Vendor'], ['responseSlaMinutes', 'Answers within (min)'],
  ['agreedRate', 'Agreed rate'],
];
const BILL_LINE_COLUMNS: Array<[string, string]> = [
  ['lineNo', '#'], ['chargeType', 'Charge'], ['narrative', 'Narrative'], ['quantity', 'Quantity'],
  ['unit', 'Unit'], ['rate', 'Rate'], ['amount', 'Amount'],
];

function blankTariff(): TariffDraft {
  return {
    code: '', vendorPartnerId: '', originTerminalId: '', destinationTerminalId: '', vehicleType: '',
    effectiveFrom: today(), currency: '', minimumCharge: '', detentionFreeHours: '', detentionHourlyRate: '',
    additionalDropFee: '',
  };
}

function blankSlab(): SlabDraft {
  return { minWeightKg: '', maxWeightKg: '', rateBasis: 'PER_KG', rateAmount: '' };
}

/**
 * Rate cards. A card is a version: correcting one means publishing a successor, so there is
 * nothing to edit here -- only publish, then add the weight bands while the card is open.
 */
@Component({
  selector: 'app-tariffs-panel',
  imports: [FormsModule, CommercialTable, RefusalNote],
  template: `
    <section data-testid="tariffs">
      <h2>Rate cards</h2>
      <form class="card" (ngSubmit)="publish()" data-testid="publish-tariff">
        <label>Code <input name="code" [value]="draft().code" (input)="set('code', $any($event.target).value)" required /></label>
        <label>Vendor
          <input name="vendor" list="commercial-vendors" [value]="draft().vendorPartnerId"
                 (input)="set('vendorPartnerId', $any($event.target).value)" required />
        </label>
        <label>Origin
          <input name="origin" list="commercial-terminals" [value]="draft().originTerminalId"
                 (input)="set('originTerminalId', $any($event.target).value)" required />
        </label>
        <label>Destination
          <input name="destination" list="commercial-terminals" [value]="draft().destinationTerminalId"
                 (input)="set('destinationTerminalId', $any($event.target).value)" required />
        </label>
        <label>Vehicle type
          <input name="vehicleType" [value]="draft().vehicleType" (input)="set('vehicleType', $any($event.target).value)" required />
        </label>
        <label>Effective from
          <input name="effectiveFrom" type="date" [value]="draft().effectiveFrom"
                 (input)="set('effectiveFrom', $any($event.target).value)" required />
        </label>
        <label>Currency <input name="currency" placeholder="INR" [value]="draft().currency" (input)="set('currency', $any($event.target).value)" /></label>
        <label>Minimum charge
          <input name="minimumCharge" inputmode="decimal" [value]="draft().minimumCharge"
                 (input)="set('minimumCharge', $any($event.target).value)" />
        </label>
        <label>Free detention hours
          <input name="detentionFreeHours" inputmode="decimal" [value]="draft().detentionFreeHours"
                 (input)="set('detentionFreeHours', $any($event.target).value)" />
        </label>
        <label>Detention per hour
          <input name="detentionHourlyRate" inputmode="decimal" [value]="draft().detentionHourlyRate"
                 (input)="set('detentionHourlyRate', $any($event.target).value)" />
        </label>
        <label>Fee per additional drop
          <input name="additionalDropFee" inputmode="decimal" [value]="draft().additionalDropFee"
                 (input)="set('additionalDropFee', $any($event.target).value)" />
        </label>
        <button type="submit">Publish rate card</button>
      </form>
      <app-refusal [refusal]="publishRefusal()" testId="tariff-refusal" />

      @if (tariffId()) {
        <section data-testid="tariff-panel" aria-label="Published rate card">
          <p>Rate card <strong data-testid="tariff-code">{{ publishedCode() }}</strong> published as
            <code data-testid="tariff-id">{{ tariffId() }}</code>.</p>
          <h3>Weight bands</h3>
          @if (slabs().length === 0) {
            <p class="muted" data-testid="no-slabs">No band yet: the card prices nothing until it has one.</p>
          } @else {
            <app-commercial-table [rows]="slabs()" [columns]="slabColumns" rowTestId="slab-row" />
          }
          <form class="inline" (ngSubmit)="addSlab()" data-testid="add-slab">
            <input name="minWeightKg" aria-label="From kg" placeholder="From kg" inputmode="decimal"
                   [value]="slab().minWeightKg" (input)="setSlab('minWeightKg', $any($event.target).value)" required />
            <input name="maxWeightKg" aria-label="To kg" placeholder="To kg (blank: no limit)" inputmode="decimal"
                   [value]="slab().maxWeightKg" (input)="setSlab('maxWeightKg', $any($event.target).value)" />
            <select name="rateBasis" aria-label="Rate basis" [value]="slab().rateBasis"
                    (change)="setSlab('rateBasis', $any($event.target).value)">
              @for (b of rateBases; track b) { <option [value]="b">{{ b }}</option> }
            </select>
            <input name="rateAmount" aria-label="Rate" placeholder="Rate" inputmode="decimal"
                   [value]="slab().rateAmount" (input)="setSlab('rateAmount', $any($event.target).value)" required />
            <button type="submit">Add band</button>
          </form>
          <app-refusal [refusal]="slabRefusal()" testId="slab-refusal" />
        </section>
      }

      <h3>Fuel surcharge</h3>
      <form class="inline" (ngSubmit)="fuel()" data-testid="fuel-surcharge">
        <input name="fuelMonth" type="month" aria-label="Month" [value]="fuelMonth()"
               (input)="fuelMonth.set($any($event.target).value)" required />
        <input name="fuelPct" aria-label="Surcharge %" placeholder="Surcharge %" inputmode="decimal"
               [value]="fuelPct()" (input)="fuelPct.set($any($event.target).value)" required />
        <button type="submit">Record surcharge</button>
      </form>
      @if (fuelRecorded()) { <p data-testid="fuel-recorded">Surcharge for {{ fuelRecorded() }} recorded.</p> }
      <app-refusal [refusal]="fuelRefusal()" testId="fuel-refusal" />
    </section>
  `,
})
export class TariffsPanel {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly rateBases = SLAB_RATE_BASES;
  readonly slabColumns = SLAB_COLUMNS;
  readonly draft = signal<TariffDraft>(blankTariff());
  readonly publishRefusal = signal<Refusal | null>(null);
  readonly tariffId = signal('');
  readonly publishedCode = signal('');
  readonly slab = signal<SlabDraft>(blankSlab());
  readonly slabs = signal<Row[]>([]);
  readonly slabRefusal = signal<Refusal | null>(null);
  readonly fuelMonth = signal(today().slice(0, 7));
  readonly fuelPct = signal('');
  readonly fuelRecorded = signal('');
  readonly fuelRefusal = signal<Refusal | null>(null);

  set(field: keyof TariffDraft, value: string): void {
    this.draft.update((d) => ({ ...d, [field]: value }));
  }

  setSlab(field: keyof SlabDraft, value: string): void {
    this.slab.update((s) => ({ ...s, [field]: field === 'rateBasis' ? (value as SlabRateBasis) : value }));
  }

  async publish(): Promise<void> {
    const orgUnitId = this.session.orgUnitId();
    if (!orgUnitId) {
      return;
    }
    this.publishRefusal.set(null);
    try {
      const id = await this.gateway.publishTariff(orgUnitId, this.draft());
      this.tariffId.set(id);
      this.publishedCode.set(this.draft().code.trim());
      this.slabs.set([]);
      this.draft.set(blankTariff());
    } catch (e) {
      this.publishRefusal.set(refusalOf(e));
    }
  }

  async addSlab(): Promise<void> {
    this.slabRefusal.set(null);
    const band = this.slab();
    try {
      await this.gateway.addTariffSlab(this.tariffId(), band);
      this.slabs.update((rows) => [...rows, {
        minWeightKg: band.minWeightKg, maxWeightKg: band.maxWeightKg || '—', rateBasis: band.rateBasis,
        rateAmount: band.rateAmount,
      }]);
      this.slab.set(blankSlab());
    } catch (e) {
      this.slabRefusal.set(refusalOf(e));
    }
  }

  async fuel(): Promise<void> {
    this.fuelRefusal.set(null);
    this.fuelRecorded.set('');
    const month = this.fuelMonth();
    try {
      await this.gateway.recordFuelSurcharge(`${month}-01`, Number(this.fuelPct()));
      this.fuelRecorded.set(month);
      this.fuelPct.set('');
    } catch (e) {
      this.fuelRefusal.set(refusalOf(e));
    }
  }
}

/** Routing guides: who is offered a lane's loads, in what order, and how long each has to answer. */
@Component({
  selector: 'app-guides-panel',
  imports: [FormsModule, CommercialTable, RefusalNote, EntityPicker],
  template: `
    <section data-testid="guides">
      <h2>Routing guides</h2>
      @if (manage()) {
        <form class="inline" (ngSubmit)="define()" data-testid="define-guide">
          <input name="guideOrigin" aria-label="Guide origin" placeholder="Origin" list="commercial-terminals"
                 [value]="guide().originTerminalId" (input)="setGuide('originTerminalId', $any($event.target).value)" required />
          <input name="guideDestination" aria-label="Guide destination" placeholder="Destination" list="commercial-terminals"
                 [value]="guide().destinationTerminalId" (input)="setGuide('destinationTerminalId', $any($event.target).value)" required />
          <input name="guideVehicleType" aria-label="Guide vehicle type" placeholder="Vehicle type"
                 [value]="guide().vehicleType" (input)="setGuide('vehicleType', $any($event.target).value)" required />
          <select name="strategy" aria-label="Strategy" [value]="guide().strategy"
                  (change)="setGuide('strategy', $any($event.target).value)">
            @for (s of strategies; track s) { <option [value]="s">{{ s }}</option> }
          </select>
          <button type="submit">Define guide</button>
        </form>
        <app-refusal [refusal]="defineRefusal()" testId="guide-refusal" />
      }
      <form class="inline" (ngSubmit)="open(typed())" data-testid="open-guide">
        <app-entity-picker [kind]="'routing-guide'" ariaLabel="Guide id" placeholder="Guide (lane or vehicle type)"
                           [required]="true" [(value)]="typed" />
        <button type="submit">Open</button>
      </form>
      <app-refusal [refusal]="openRefusal()" testId="guide-open-refusal" />

      @if (detail(); as d) {
        <section data-testid="guide-panel" aria-label="Routing guide">
          <h3>{{ d.guide['originTerminalCode'] }} → {{ d.guide['destinationTerminalCode'] }} · {{ d.guide['vehicleType'] }}</h3>
          <p>Strategy {{ d.guide['strategy'] }} ·
            <span data-testid="guide-state">{{ d.guide['active'] ? 'Active' : 'Retired' }}</span></p>
          @if (d.entries.length === 0) {
            <p class="muted" data-testid="no-vendors">No vendor on this guide yet.</p>
          } @else {
            <app-commercial-table [rows]="d.entries" [columns]="entryColumns" rowTestId="guide-vendor" />
          }
          @if (manage() && d.guide['active']) {
            <form class="inline" (ngSubmit)="addVendor()" data-testid="add-vendor">
              <app-entity-picker [kind]="'vendor'" ariaLabel="Vendor" placeholder="Vendor" [required]="true"
                                 [(value)]="vendorId" />
              <input name="rankNo" aria-label="Rank" placeholder="Rank" inputmode="numeric"
                     [value]="rank()" (input)="rank.set($any($event.target).value)" required />
              <input name="sla" aria-label="Answers within minutes" placeholder="Answers within (min)" inputmode="numeric"
                     [value]="sla()" (input)="sla.set($any($event.target).value)" required />
              <input name="agreedRate" aria-label="Agreed rate" placeholder="Agreed rate" inputmode="decimal"
                     [value]="agreedRate()" (input)="agreedRate.set($any($event.target).value)" />
              <button type="submit">Add vendor</button>
            </form>
            <p><button class="link" data-testid="retire-guide" (click)="retire()">Retire guide</button></p>
          }
          <app-refusal [refusal]="actRefusal()" testId="guide-act-refusal" />
        </section>
      }
    </section>
  `,
})
export class GuidesPanel {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly manage = input(false);
  readonly strategies = GUIDE_STRATEGIES;
  readonly entryColumns = ENTRY_COLUMNS;
  readonly guide = signal<GuideDraft>({ originTerminalId: '', destinationTerminalId: '', vehicleType: '', strategy: 'CONTRACTUAL' });
  readonly defineRefusal = signal<Refusal | null>(null);
  readonly typed = signal('');
  readonly guideId = signal('');
  readonly detail = signal<RoutingGuideDetail | null>(null);
  readonly openRefusal = signal<Refusal | null>(null);
  readonly vendorId = signal('');
  readonly rank = signal('1');
  readonly sla = signal('60');
  readonly agreedRate = signal('');
  readonly actRefusal = signal<Refusal | null>(null);

  setGuide(field: keyof GuideDraft, value: string): void {
    this.guide.update((g) => ({ ...g, [field]: field === 'strategy' ? (value as GuideStrategy) : value }));
  }

  async define(): Promise<void> {
    const orgUnitId = this.session.orgUnitId();
    if (!orgUnitId) {
      return;
    }
    this.defineRefusal.set(null);
    try {
      const id = await this.gateway.defineRoutingGuide(orgUnitId, this.guide());
      this.typed.set(id);
      await this.open(id);
    } catch (e) {
      this.defineRefusal.set(refusalOf(e));
    }
  }

  async open(id: string): Promise<void> {
    this.openRefusal.set(null);
    this.actRefusal.set(null);
    try {
      this.detail.set(await this.gateway.routingGuide(id.trim()));
      this.guideId.set(id.trim());
    } catch (e) {
      this.detail.set(null);
      this.openRefusal.set(refusalOf(e));
    }
  }

  async addVendor(): Promise<void> {
    this.actRefusal.set(null);
    try {
      await this.gateway.addGuideVendor(this.guideId(), {
        vendorPartnerId: this.vendorId(), rankNo: Number(this.rank()), responseSlaMinutes: Number(this.sla()),
        agreedRate: this.agreedRate(),
      });
      this.vendorId.set('');
      this.agreedRate.set('');
      this.rank.update((r) => String(Number(r) + 1));
      this.detail.set(await this.gateway.routingGuide(this.guideId()));
    } catch (e) {
      this.actRefusal.set(refusalOf(e));
    }
  }

  async retire(): Promise<void> {
    this.actRefusal.set(null);
    try {
      await this.gateway.retireRoutingGuide(this.guideId());
      this.detail.set(await this.gateway.routingGuide(this.guideId()));
    } catch (e) {
      this.actRefusal.set(refusalOf(e));
    }
  }
}

/** Allocations: a load offered to the vendors its guide names, one at a time, until one accepts. */
@Component({
  selector: 'app-allocations-panel',
  imports: [FormsModule, RefusalNote, EntityPicker],
  template: `
    <section data-testid="allocations">
      <h2>Allocations</h2>
      @if (act()) {
        <form class="inline" (ngSubmit)="allocate()" data-testid="allocate-load">
          <app-entity-picker [kind]="'load'" ariaLabel="Load id to allocate" placeholder="Load" [required]="true"
                             [(value)]="loadId" />
          <button type="submit">Allocate load</button>
        </form>
        <app-refusal [refusal]="allocateRefusal()" testId="allocate-refusal" />
      }
      <form class="inline" (ngSubmit)="refresh()" data-testid="allocation-filter">
        <input name="allocationStatus" aria-label="Status" placeholder="Status (blank: all)" [value]="status()"
               (input)="status.set($any($event.target).value)" />
        <button type="submit">List</button>
      </form>
      <form class="inline" (ngSubmit)="open(typed().trim())" data-testid="open-allocation">
        <app-entity-picker [kind]="'allocation'" ariaLabel="Allocation id" placeholder="Allocation (load no)"
                           [required]="true" [(value)]="typed" />
        <button type="submit">Open</button>
      </form>
      <app-refusal [refusal]="listRefusal()" testId="allocations-refusal" />
      @if (loaded() && rows().length === 0) {
        <p class="muted" data-testid="no-allocations">No allocation matches.</p>
      } @else {
        <table>
          <thead><tr><th>Load</th><th>Status</th><th>Waiting on</th><th>Awarded to</th><th>Offers</th></tr></thead>
          <tbody>
            @for (a of rows(); track a['id']) {
              <tr data-testid="allocation-row" tabindex="0" [class.selected]="a['id'] === openId()"
                  (click)="open(a['id'])" (keydown.enter)="open(a['id'])">
                <td>{{ a['loadNo'] }}</td><td>{{ a['status'] }}</td><td>{{ a['pendingVendorName'] }}</td>
                <td>{{ a['awardedVendorName'] }}</td><td>{{ a['offersMade'] }}</td>
              </tr>
            }
          </tbody>
        </table>
      }

      @if (detail(); as d) {
        <section data-testid="allocation-panel" aria-label="Allocation">
          <h3>Load {{ d.allocation['loadNo'] }}</h3>
          <dl>
            <div><dt>Status</dt><dd data-testid="allocation-status">{{ d.allocation['status'] }}</dd></div>
            <div><dt>Strategy</dt><dd>{{ d.allocation['strategy'] }}</dd></div>
            <div><dt>Awarded to</dt><dd data-testid="allocation-awarded">{{ d.allocation['awardedVendorName'] }}</dd></div>
            @if (d.allocation['appliedRuleName']) {
              <div><dt>Rule</dt><dd>{{ d.allocation['appliedRuleName'] }}</dd></div>
            }
          </dl>
          <table>
            <thead><tr><th>Rank</th><th>Vendor</th><th>Outcome</th><th>Answer by</th><th></th></tr></thead>
            <tbody>
              @for (o of d.offers; track o['id']) {
                <tr data-testid="offer-row">
                  <td>{{ o['rankNo'] }}</td><td>{{ o['vendorName'] }}</td>
                  <td data-testid="offer-outcome">{{ o['outcome'] }}</td><td>{{ o['respondsBy'] }}</td>
                  <td>
                    @if (act() && o['outcome'] === 'PENDING') {
                      <button data-testid="accept-offer" (click)="accept(o['vendorPartnerId'])">Accept</button>
                      <button class="link" data-testid="reject-offer" (click)="reject(o['vendorPartnerId'])">Reject</button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
          @if (act() && d.allocation['status'] !== 'CANCELLED' && d.allocation['status'] !== 'AWARDED') {
            <p><button class="link" data-testid="cancel-allocation" (click)="cancel()">Cancel allocation</button></p>
          }
        </section>
      }
      <app-refusal [refusal]="actRefusal()" testId="allocation-refusal" />
    </section>
  `,
})
export class AllocationsPanel implements OnInit {
  private readonly gateway = inject(LmsGateway);

  readonly act = input(false);
  readonly loaded = signal(false);
  readonly status = signal('');
  readonly rows = signal<Row[]>([]);
  readonly listRefusal = signal<Refusal | null>(null);
  readonly typed = signal('');
  readonly loadId = signal('');
  readonly allocateRefusal = signal<Refusal | null>(null);
  readonly openId = signal<string | null>(null);
  readonly detail = signal<AllocationDetail | null>(null);
  readonly actRefusal = signal<Refusal | null>(null);

  ngOnInit(): void {
    void this.refresh();
  }

  async refresh(): Promise<void> {
    this.listRefusal.set(null);
    try {
      this.rows.set(await this.gateway.allocations(this.status().trim().toUpperCase()));
      this.loaded.set(true);
    } catch (e) {
      this.listRefusal.set(refusalOf(e));
    }
  }

  async allocate(): Promise<void> {
    this.allocateRefusal.set(null);
    try {
      const id = await this.gateway.allocateLoad(this.loadId());
      this.loadId.set('');
      await this.refresh();
      await this.open(id);
    } catch (e) {
      this.allocateRefusal.set(refusalOf(e));
    }
  }

  async open(id: string): Promise<void> {
    this.openId.set(id);
    this.actRefusal.set(null);
    try {
      this.detail.set(await this.gateway.allocationDetail(id));
    } catch (e) {
      this.detail.set(null);
      this.actRefusal.set(refusalOf(e));
    }
  }

  accept(vendorPartnerId: string): Promise<void> {
    return this.respond(() => this.gateway.acceptOffer(this.openId() ?? '', vendorPartnerId));
  }

  reject(vendorPartnerId: string): Promise<void> {
    return this.respond(() => this.gateway.rejectOffer(this.openId() ?? '', vendorPartnerId));
  }

  cancel(): Promise<void> {
    return this.respond(() => this.gateway.cancelAllocation(this.openId() ?? ''));
  }

  private async respond(action: () => Promise<void>): Promise<void> {
    const id = this.openId();
    if (!id) {
      return;
    }
    this.actRefusal.set(null);
    try {
      await action();
    } catch (e) {
      this.actRefusal.set(refusalOf(e));
    }
    await this.refresh();
    try {
      this.detail.set(await this.gateway.allocationDetail(id));
    } catch {
      // The refusal above, if any, already says what went wrong.
    }
  }
}

/** Freight bills: what the platform computed, what the vendor claimed, and how it settled. */
@Component({
  selector: 'app-bills-panel',
  imports: [FormsModule, CommercialTable, RefusalNote, EntityPicker],
  template: `
    <section data-testid="freight-bills">
      <h2>Freight bills</h2>
      <form class="inline" (ngSubmit)="refresh()" data-testid="bill-filter">
        <input name="billStatus" aria-label="Bill status" placeholder="Status (blank: all)" [value]="status()"
               (input)="status.set($any($event.target).value)" />
        <button type="submit">List</button>
      </form>
      <form class="inline" (ngSubmit)="open(typed())" data-testid="open-bill">
        <app-entity-picker [kind]="'bill'" ariaLabel="Bill id" placeholder="Bill" [required]="true" [(value)]="typed" />
        <button type="submit">Open</button>
      </form>
      <app-refusal [refusal]="listRefusal()" testId="bills-refusal" />
      @if (loaded() && rows().length === 0) {
        <p class="muted" data-testid="no-bills">No bill matches.</p>
      } @else {
        <div class="table-scroll">
          <table>
            <thead><tr><th>Bill</th><th>Status</th><th>Vendor</th><th>Computed</th><th>Claimed</th><th>Variance</th></tr></thead>
            <tbody>
              @for (b of rows(); track b['id']) {
                <tr data-testid="bill-row" tabindex="0" [class.selected]="b['id'] === openId()"
                    (click)="open(b['id'])" (keydown.enter)="open(b['id'])">
                  <td>{{ b['billNo'] }}</td><td>{{ b['status'] }}</td><td>{{ b['vendorName'] }}</td>
                  <td>{{ b['computedAmount'] }}</td><td>{{ b['claimedAmount'] }}</td><td>{{ b['varianceAmount'] }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }

      @if (detail(); as d) {
        <section data-testid="bill-panel" aria-label="Freight bill">
          <header>
            <h3>{{ d.bill['billNo'] }}</h3>
            <button class="link" (click)="close()" aria-label="Close bill">Close</button>
          </header>
          <dl>
            <div><dt>Status</dt><dd data-testid="bill-status">{{ d.bill['status'] }}</dd></div>
            <div><dt>Vendor</dt><dd>{{ d.bill['vendorName'] }}</dd></div>
            <div><dt>Trip</dt><dd>{{ d.bill['tripNo'] }}</dd></div>
            <div><dt>Rate card</dt><dd>{{ d.bill['tariffCode'] }}</dd></div>
            <div><dt>Computed</dt><dd data-testid="bill-computed">{{ d.bill['computedAmount'] }} {{ d.bill['currency'] }}</dd></div>
            <div><dt>Claimed</dt><dd data-testid="bill-claimed">{{ d.bill['claimedAmount'] }}</dd></div>
            <div><dt>Variance</dt><dd data-testid="bill-variance">{{ d.bill['varianceAmount'] }}</dd></div>
            @if (d.bill['resolution']) {
              <div><dt>Resolution</dt><dd data-testid="bill-resolution">{{ d.bill['resolution'] }}</dd></div>
            }
            @if (d.bill['settlementHeldBy']) {
              <div><dt>Held by</dt><dd data-testid="bill-held-by">{{ d.bill['settlementHeldBy'] }}</dd></div>
            }
            @if (d.bill['awaitingProof']) {
              <div><dt>Proof of delivery</dt><dd data-testid="bill-awaiting-proof">Waiting for clean proofs of delivery</dd></div>
            }
            @if (d.bill['proofHoldReleaseNote']) {
              <div><dt>Proof hold</dt><dd data-testid="bill-proof-release">Released: {{ d.bill['proofHoldReleaseNote'] }}</dd></div>
            }
          </dl>
          <app-commercial-table [rows]="d.lines" [columns]="lineColumns" rowTestId="bill-line" />
          <p><button class="link" data-testid="print-bill" (click)="slip.set(true)">Print bill</button></p>
          @if (slip()) {
            <section class="slip manifest" data-testid="bill-slip" aria-label="Freight bill to print">
              <h3>Freight bill {{ d.bill['billNo'] }}</h3>
              <p>{{ d.bill['vendorName'] }} · trip {{ d.bill['tripNo'] }} · load {{ d.bill['loadNo'] }} · priced {{ d.bill['pricedOn'] }}</p>
              <app-commercial-table [rows]="d.lines" [columns]="lineColumns" rowTestId="slip-line" />
              <p>Computed {{ d.bill['computedAmount'] }} {{ d.bill['currency'] }} · claimed {{ d.bill['claimedAmount'] }} · status {{ d.bill['status'] }}</p>
              <p class="actions no-print">
                <button data-testid="print-now" (click)="print()">Print</button>
                <button class="link" (click)="slip.set(false)">Close</button>
              </p>
            </section>
          }
          @if (approve()) {
            @if (d.bill['status'] === 'DRAFT') {
              <form class="inline" (ngSubmit)="match()" data-testid="match-bill">
                <input name="claimedAmount" aria-label="Vendor's claimed amount" placeholder="Claimed amount" inputmode="decimal"
                       [value]="claimed()" (input)="claimed.set($any($event.target).value)" required />
                <button type="submit">Match claim</button>
              </form>
            }
            @if (d.bill['status'] === 'DISPUTED') {
              <form class="inline" (ngSubmit)="resolve()" data-testid="resolve-bill">
                <input name="reason" aria-label="Reason for settling" placeholder="Reason" [value]="reason()"
                       (input)="reason.set($any($event.target).value)" required />
                <button type="submit">Settle in the vendor's favour</button>
              </form>
            }
          }
          @if (release() && d.bill['awaitingProof']) {
            <form class="inline" (ngSubmit)="releaseHold()" data-testid="release-proof-hold">
              <input name="releaseReason" aria-label="Reason for releasing the proof hold"
                     placeholder="Why it may be paid without clean proofs" [value]="releaseReason()"
                     (input)="releaseReason.set($any($event.target).value)" required />
              <button type="submit">Release proof hold</button>
            </form>
          }
          @if (outcome()) { <p data-testid="match-outcome">Matched: {{ outcome() }}.</p> }
        </section>
      }
      <app-refusal [refusal]="actRefusal()" testId="bill-refusal" />
    </section>
  `,
})
export class BillsPanel implements OnInit {
  private readonly gateway = inject(LmsGateway);

  readonly approve = input(false);
  readonly release = input(false);
  readonly releaseReason = signal('');
  readonly lineColumns = BILL_LINE_COLUMNS;
  readonly loaded = signal(false);
  readonly status = signal('');
  readonly rows = signal<Row[]>([]);
  readonly listRefusal = signal<Refusal | null>(null);
  readonly typed = signal('');
  readonly openId = signal<string | null>(null);
  readonly detail = signal<FreightBillDetail | null>(null);
  readonly slip = signal(false);
  readonly claimed = signal('');
  readonly reason = signal('');
  readonly outcome = signal('');
  readonly actRefusal = signal<Refusal | null>(null);

  ngOnInit(): void {
    void this.refresh();
  }

  async refresh(): Promise<void> {
    this.listRefusal.set(null);
    try {
      this.rows.set(await this.gateway.freightBillList(this.status().trim().toUpperCase()));
      this.loaded.set(true);
    } catch (e) {
      this.listRefusal.set(refusalOf(e));
    }
  }

  async open(id: string): Promise<void> {
    const target = id.trim();
    if (target !== this.openId()) {
      this.slip.set(false);
      this.outcome.set('');
    }
    this.openId.set(target);
    this.actRefusal.set(null);
    try {
      this.detail.set(await this.gateway.freightBill(target));
    } catch (e) {
      this.detail.set(null);
      this.actRefusal.set(refusalOf(e));
    }
  }

  close(): void {
    this.openId.set(null);
    this.detail.set(null);
  }

  async match(): Promise<void> {
    const id = this.openId();
    if (!id) {
      return;
    }
    this.actRefusal.set(null);
    try {
      this.outcome.set(await this.gateway.matchFreightBill(id, Number(this.claimed())));
      this.claimed.set('');
    } catch (e) {
      this.actRefusal.set(refusalOf(e));
    }
    await this.reread(id);
  }

  async releaseHold(): Promise<void> {
    const id = this.openId();
    if (!id) {
      return;
    }
    this.actRefusal.set(null);
    try {
      await this.gateway.releaseProofHold(id, this.releaseReason());
      this.releaseReason.set('');
    } catch (e) {
      this.actRefusal.set(refusalOf(e));
    }
    await this.reread(id);
  }

  async resolve(): Promise<void> {
    const id = this.openId();
    if (!id) {
      return;
    }
    this.actRefusal.set(null);
    try {
      await this.gateway.resolveFreightBill(id, this.reason());
      this.reason.set('');
      this.outcome.set('');
    } catch (e) {
      this.actRefusal.set(refusalOf(e));
    }
    await this.reread(id);
  }

  print(): void {
    window.print();
  }

  private async reread(id: string): Promise<void> {
    await this.refresh();
    try {
      this.detail.set(await this.gateway.freightBill(id));
    } catch {
      // The refusal above, if any, already says what went wrong.
    }
  }
}

// Rate cards and rail tariffs

const RATE_CARD_STATES: Array<[TariffFilter['state'], string]> = [
  ['', 'All versions'], ['open', 'In force or upcoming'], ['superseded', 'Superseded'],
];

/** Every published rate card version, newest first, and one card opened with its weight bands. */
@Component({
  selector: 'app-rate-cards-panel',
  imports: [FormsModule, CommercialTable, RefusalNote, EntityPicker],
  template: `
    <section data-testid="rate-cards">
      <h2>Published rate cards</h2>
      <form class="inline" (ngSubmit)="refresh()" data-testid="rate-card-filter">
        <app-entity-picker [kind]="'vendor'" ariaLabel="Vendor filter" placeholder="Vendor (blank: all)" [(value)]="vendor" />
        <select name="rateCardState" aria-label="Versions" [value]="state()" (change)="state.set($any($event.target).value)">
          @for (s of states; track s[0]) { <option [value]="s[0]">{{ s[1] }}</option> }
        </select>
        <button type="submit">List</button>
      </form>
      <form class="inline" (ngSubmit)="open(typed())" data-testid="open-rate-card">
        <input name="rateCardId" aria-label="Rate card id" placeholder="Rate card id" [value]="typed()"
               (input)="typed.set($any($event.target).value)" required />
        <button type="submit">Open</button>
      </form>
      <app-refusal [refusal]="listRefusal()" testId="rate-cards-refusal" />
      @if (loaded() && rows().length === 0) {
        <p class="muted" data-testid="no-rate-cards">No rate card matches.</p>
      } @else {
        <div class="table-scroll">
          <table>
            <thead><tr><th>Card</th><th>Vendor</th><th>Lane</th><th>Vehicle</th><th>From</th><th>To</th><th>Bands</th></tr></thead>
            <tbody>
              @for (t of rows(); track t['id']) {
                <tr data-testid="rate-card-row" tabindex="0" [class.selected]="t['id'] === openId()"
                    (click)="open(t['id'])" (keydown.enter)="open(t['id'])">
                  <td>{{ t['code'] }}</td><td>{{ t['vendorName'] }}</td>
                  <td>{{ t['originTerminalCode'] }} → {{ t['destinationTerminalCode'] }}</td>
                  <td>{{ t['vehicleType'] }}</td><td>{{ t['effectiveFrom'] }}</td><td>{{ t['effectiveTo'] || '—' }}</td>
                  <td>{{ t['slabCount'] }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
      @if (nextCursor()) { <p><button class="link" data-testid="rate-cards-more" (click)="more()">Load more</button></p> }
      <app-refusal [refusal]="openRefusal()" testId="rate-card-refusal" />
      @if (detail(); as d) {
        <section data-testid="rate-card-panel" aria-label="Rate card">
          <h3 data-testid="rate-card-code">{{ d.card['code'] }}</h3>
          <dl class="facts">
            <div><dt>Vendor</dt><dd data-testid="rate-card-vendor">{{ d.card['vendorName'] }}</dd></div>
            <div><dt>Lane</dt><dd>{{ d.card['originTerminalCode'] }} → {{ d.card['destinationTerminalCode'] }}</dd></div>
            <div><dt>Vehicle</dt><dd>{{ d.card['vehicleType'] }}</dd></div>
            <div><dt>Published under</dt><dd>{{ d.card['orgUnitName'] }}</dd></div>
            <div><dt>In force</dt><dd data-testid="rate-card-state">{{ d.card['superseded']
              ? d.card['effectiveFrom'] + ' to ' + d.card['effectiveTo'] : 'from ' + d.card['effectiveFrom'] }}</dd></div>
            <div><dt>Minimum charge</dt><dd>{{ d.card['minimumCharge'] }} {{ d.card['currency'] }}</dd></div>
            <div><dt>Free detention hours</dt><dd>{{ d.card['detentionFreeHours'] }}</dd></div>
            <div><dt>Detention per hour</dt><dd>{{ d.card['detentionHourlyRate'] }}</dd></div>
            <div><dt>Fee per additional drop</dt><dd>{{ d.card['additionalDropFee'] }}</dd></div>
          </dl>
          <h4>Weight bands</h4>
          @if (d.slabs.length === 0) {
            <p class="muted" data-testid="rate-card-no-bands">No band: the card prices nothing.</p>
          } @else {
            <app-commercial-table [rows]="d.slabs" [columns]="slabColumns" rowTestId="rate-card-band" />
          }
        </section>
      }
    </section>
  `,
})
export class RateCardsPanel implements OnInit {
  private readonly gateway = inject(LmsGateway);

  readonly states = RATE_CARD_STATES;
  readonly slabColumns = SLAB_COLUMNS;
  readonly vendor = signal('');
  readonly state = signal<TariffFilter['state']>('');
  readonly rows = signal<Row[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly loaded = signal(false);
  readonly listRefusal = signal<Refusal | null>(null);
  readonly typed = signal('');
  readonly openId = signal<string | null>(null);
  readonly detail = signal<TariffCardDetail | null>(null);
  readonly openRefusal = signal<Refusal | null>(null);

  ngOnInit(): void {
    void this.refresh();
  }

  async refresh(): Promise<void> {
    this.listRefusal.set(null);
    try {
      const page = await this.gateway.tariffs({ vendorPartnerId: this.vendor(), state: this.state() });
      this.rows.set(page.rows);
      this.nextCursor.set(page.nextCursor);
      this.loaded.set(true);
    } catch (e) {
      this.listRefusal.set(refusalOf(e));
    }
  }

  /** The next page of the same listing, after what is already shown. */
  async more(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor) {
      return;
    }
    this.listRefusal.set(null);
    try {
      const page = await this.gateway.tariffs({ vendorPartnerId: this.vendor(), state: this.state() }, cursor);
      this.rows.update((shown) => [...shown, ...page.rows]);
      this.nextCursor.set(page.nextCursor);
    } catch (e) {
      this.listRefusal.set(refusalOf(e));
    }
  }

  async open(id: string): Promise<void> {
    const target = id.trim();
    this.openId.set(target);
    this.openRefusal.set(null);
    try {
      this.detail.set(await this.gateway.tariffCard(target));
    } catch (e) {
      this.detail.set(null);
      this.openRefusal.set(refusalOf(e));
    }
  }
}

function blankRailTariff(): RailTariffDraft {
  return {
    code: '', carrierPartnerId: '', originTerminalId: '', destinationTerminalId: '', effectiveFrom: today(),
    currency: '', perWagonRate: '', perContainerRate: '', minimumCharge: '',
  };
}

/** Rail carriers' rate cards between railheads: every version, newest first. Publishing needs TARIFF_MANAGE. */
@Component({
  selector: 'app-rail-tariffs-panel',
  imports: [FormsModule, RefusalNote],
  template: `
    <section data-testid="rail-tariffs">
      <h2>Rail rate cards</h2>
      @if (manage()) {
        <form class="card" (ngSubmit)="publish()" data-testid="publish-rail-tariff">
          <label>Code <input name="railCode" [value]="draft().code" (input)="set('code', $any($event.target).value)" required /></label>
          <label>Rail carrier
            <input name="railCarrier" list="commercial-vendors" [value]="draft().carrierPartnerId"
                   (input)="set('carrierPartnerId', $any($event.target).value)" required />
          </label>
          <label>Origin railhead
            <input name="railOrigin" list="commercial-terminals" [value]="draft().originTerminalId"
                   (input)="set('originTerminalId', $any($event.target).value)" required />
          </label>
          <label>Destination railhead
            <input name="railDestination" list="commercial-terminals" [value]="draft().destinationTerminalId"
                   (input)="set('destinationTerminalId', $any($event.target).value)" required />
          </label>
          <label>Effective from
            <input name="railEffectiveFrom" type="date" [value]="draft().effectiveFrom"
                   (input)="set('effectiveFrom', $any($event.target).value)" required />
          </label>
          <label>Currency <input name="railCurrency" placeholder="INR" [value]="draft().currency" (input)="set('currency', $any($event.target).value)" /></label>
          <label>Per wagon
            <input name="railPerWagon" inputmode="decimal" [value]="draft().perWagonRate"
                   (input)="set('perWagonRate', $any($event.target).value)" />
          </label>
          <label>Per container
            <input name="railPerContainer" inputmode="decimal" [value]="draft().perContainerRate"
                   (input)="set('perContainerRate', $any($event.target).value)" />
          </label>
          <label>Minimum charge
            <input name="railMinimum" inputmode="decimal" [value]="draft().minimumCharge"
                   (input)="set('minimumCharge', $any($event.target).value)" />
          </label>
          <button type="submit">Publish rail rate card</button>
        </form>
        <app-refusal [refusal]="publishRefusal()" testId="rail-tariff-refusal" />
        @if (published()) { <p data-testid="rail-tariff-published">Rail rate card {{ published() }} published.</p> }
      }
      <app-refusal [refusal]="listRefusal()" testId="rail-tariffs-refusal" />
      @if (loaded() && rows().length === 0) {
        <p class="muted" data-testid="no-rail-tariffs">No rail rate card yet.</p>
      } @else {
        <div class="table-scroll">
          <table>
            <thead><tr><th>Card</th><th>Carrier</th><th>Railheads</th><th>Per wagon</th><th>Per container</th><th>From</th><th>To</th></tr></thead>
            <tbody>
              @for (r of rows(); track r['id']) {
                <tr data-testid="rail-tariff-row" tabindex="0" [class.selected]="r['id'] === openId()"
                    (click)="open(r['id'])" (keydown.enter)="open(r['id'])">
                  <td>{{ r['code'] }}</td><td>{{ r['carrierCode'] }}</td>
                  <td>{{ r['originTerminalCode'] }} → {{ r['destinationTerminalCode'] }}</td>
                  <td>{{ r['perWagonRate'] || '—' }}</td><td>{{ r['perContainerRate'] || '—' }}</td>
                  <td>{{ r['effectiveFrom'] }}</td><td>{{ r['effectiveTo'] || '—' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
      @if (nextCursor()) { <p><button class="link" data-testid="rail-tariffs-more" (click)="more()">Load more</button></p> }
      <app-refusal [refusal]="openRefusal()" testId="rail-tariff-open-refusal" />
      @if (card(); as c) {
        <section data-testid="rail-tariff-panel" aria-label="Rail rate card">
          <h3 data-testid="rail-tariff-code">{{ c['code'] }}</h3>
          <dl class="facts">
            <div><dt>Carrier</dt><dd>{{ c['carrierCode'] }}</dd></div>
            <div><dt>Railheads</dt><dd>{{ c['originTerminalCode'] }} → {{ c['destinationTerminalCode'] }}</dd></div>
            <div><dt>Per wagon</dt><dd data-testid="rail-per-wagon">{{ c['perWagonRate'] || '—' }}</dd></div>
            <div><dt>Per container</dt><dd data-testid="rail-per-container">{{ c['perContainerRate'] || '—' }}</dd></div>
            <div><dt>Minimum charge</dt><dd>{{ c['minimumCharge'] }} {{ c['currency'] }}</dd></div>
            <div><dt>In force</dt><dd>{{ c['effectiveTo'] ? c['effectiveFrom'] + ' to ' + c['effectiveTo'] : 'from ' + c['effectiveFrom'] }}</dd></div>
          </dl>
        </section>
      }
    </section>
  `,
})
export class RailTariffsPanel implements OnInit {
  private readonly gateway = inject(LmsGateway);

  readonly manage = input(false);
  readonly draft = signal<RailTariffDraft>(blankRailTariff());
  readonly publishRefusal = signal<Refusal | null>(null);
  readonly published = signal('');
  readonly rows = signal<Row[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly loaded = signal(false);
  readonly listRefusal = signal<Refusal | null>(null);
  readonly openId = signal<string | null>(null);
  readonly card = signal<Row | null>(null);
  readonly openRefusal = signal<Refusal | null>(null);

  ngOnInit(): void {
    void this.refresh();
  }

  set(field: keyof RailTariffDraft, value: string): void {
    this.draft.update((d) => ({ ...d, [field]: value }));
  }

  async refresh(): Promise<void> {
    this.listRefusal.set(null);
    try {
      const page = await this.gateway.railTariffs();
      this.rows.set(page.rows);
      this.nextCursor.set(page.nextCursor);
      this.loaded.set(true);
    } catch (e) {
      this.listRefusal.set(refusalOf(e));
    }
  }

  /** The next page of the listing, after what is already shown. */
  async more(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor) {
      return;
    }
    this.listRefusal.set(null);
    try {
      const page = await this.gateway.railTariffs(cursor);
      this.rows.update((shown) => [...shown, ...page.rows]);
      this.nextCursor.set(page.nextCursor);
    } catch (e) {
      this.listRefusal.set(refusalOf(e));
    }
  }

  async open(id: string): Promise<void> {
    this.openId.set(id);
    this.openRefusal.set(null);
    try {
      this.card.set(await this.gateway.railTariff(id));
    } catch (e) {
      this.card.set(null);
      this.openRefusal.set(refusalOf(e));
    }
  }

  async publish(): Promise<void> {
    this.publishRefusal.set(null);
    this.published.set('');
    const draft = this.draft();
    try {
      const id = await this.gateway.publishRailTariff(draft);
      this.published.set(draft.code.trim());
      this.draft.set(blankRailTariff());
      await this.refresh();
      await this.open(id);
    } catch (e) {
      this.publishRefusal.set(refusalOf(e));
    }
  }
}

/** Rate cards, routing guides, allocations and freight bills: what a lane costs and who carries it. */
@Component({
  selector: 'app-commercial',
  imports: [TariffsPanel, GuidesPanel, AllocationsPanel, BillsPanel, RateCardsPanel, RailTariffsPanel],
  template: `
    <h1>Commercial</h1>
    <p class="muted">
      Publish rate cards, decide which vendors are offered a lane, follow loads out to vendors,
      and settle what each vendor bills.
    </p>
    <datalist id="commercial-vendors">
      @for (v of vendors(); track v.id) { <option [value]="v.id">{{ v.label }}</option> }
    </datalist>
    <datalist id="commercial-terminals">
      @for (t of terminals(); track t.id) { <option [value]="t.id">{{ t.label }}</option> }
    </datalist>
    @if (session.can('TARIFF_MANAGE') && session.orgUnitId()) { <app-tariffs-panel /> }
    <!-- Rate cards and rail tariffs -->
    @if (session.can('TARIFF_MANAGE') || session.can('INVOICE_READ')) { <app-rate-cards-panel /> }
    @if (session.can('MULTIMODAL_READ')) { <app-rail-tariffs-panel [manage]="session.can('TARIFF_MANAGE')" /> }
    @if (session.can('ROUTING_GUIDE_MANAGE') || session.can('LOAD_ALLOCATE')) {
      <app-guides-panel [manage]="session.can('ROUTING_GUIDE_MANAGE')" />
    }
    @if (session.can('LOAD_READ') || session.can('LOAD_ALLOCATE')) {
      <app-allocations-panel [act]="session.can('LOAD_ALLOCATE')" />
    }
    @if (session.can('INVOICE_READ')) { <app-bills-panel [approve]="session.can('INVOICE_APPROVE')" [release]="session.can('SETTLEMENT_HOLD_RELEASE')" /> }
  `,
})
export class Commercial implements OnInit {
  private readonly gateway = inject(LmsGateway);
  readonly session = inject(Session);

  readonly vendors = signal<Choice[]>([]);
  readonly terminals = signal<Choice[]>([]);

  async ngOnInit(): Promise<void> {
    try {
      const [vendors, terminals] = await Promise.all([this.gateway.activeVendors(), this.gateway.terminalChoices()]);
      this.vendors.set(vendors);
      this.terminals.set(terminals);
    } catch {
      // The pickers are a convenience; ids can still be typed.
    }
  }
}
