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
import { Session } from '../session';

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
  imports: [FormsModule, CommercialTable, RefusalNote],
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
        <input name="guideId" aria-label="Guide id" placeholder="Guide id" [value]="typed()"
               (input)="typed.set($any($event.target).value)" required />
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
              <input name="vendorPartnerId" aria-label="Vendor" placeholder="Vendor" list="commercial-vendors"
                     [value]="vendorId()" (input)="vendorId.set($any($event.target).value)" required />
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
  imports: [FormsModule, RefusalNote],
  template: `
    <section data-testid="allocations">
      <h2>Allocations</h2>
      @if (act()) {
        <form class="inline" (ngSubmit)="allocate()" data-testid="allocate-load">
          <input name="loadId" aria-label="Load id to allocate" placeholder="Load id" [value]="loadId()"
                 (input)="loadId.set($any($event.target).value)" required />
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
        <input name="allocationId" aria-label="Allocation id" placeholder="Allocation id" [value]="typed()"
               (input)="typed.set($any($event.target).value)" required />
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
  imports: [FormsModule, CommercialTable, RefusalNote],
  template: `
    <section data-testid="freight-bills">
      <h2>Freight bills</h2>
      <form class="inline" (ngSubmit)="refresh()" data-testid="bill-filter">
        <input name="billStatus" aria-label="Bill status" placeholder="Status (blank: all)" [value]="status()"
               (input)="status.set($any($event.target).value)" />
        <button type="submit">List</button>
      </form>
      <form class="inline" (ngSubmit)="open(typed())" data-testid="open-bill">
        <input name="billId" aria-label="Bill id" placeholder="Bill id" [value]="typed()"
               (input)="typed.set($any($event.target).value)" required />
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

/** Rate cards, routing guides, allocations and freight bills: what a lane costs and who carries it. */
@Component({
  selector: 'app-commercial',
  imports: [TariffsPanel, GuidesPanel, AllocationsPanel, BillsPanel],
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
    @if (session.can('ROUTING_GUIDE_MANAGE') || session.can('LOAD_ALLOCATE')) {
      <app-guides-panel [manage]="session.can('ROUTING_GUIDE_MANAGE')" />
    }
    @if (session.can('LOAD_READ') || session.can('LOAD_ALLOCATE')) {
      <app-allocations-panel [act]="session.can('LOAD_ALLOCATE')" />
    }
    @if (session.can('INVOICE_READ')) { <app-bills-panel [approve]="session.can('INVOICE_APPROVE')" /> }
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
