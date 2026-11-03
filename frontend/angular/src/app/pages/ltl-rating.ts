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

import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Choice, LmsGateway, RATE_BASES, RateBasis, Row } from '../gateway/lms-gateway';
import { Session } from '../session';
import { RowTable } from './row-table';

/**
 * Part-load rating: the receivable each docket earned, and the two things a
 * tariff desk sets up for it -- which zone a site is in, and the zone-to-zone
 * rate card. Zones and rate cards have no list endpoint yet, so they are
 * written here and seen through the receivables they price.
 */
@Component({
  selector: 'app-ltl-rating',
  imports: [FormsModule, RowTable],
  template: `
    <h1>LTL rating</h1>
    <p class="muted">Part-load dockets are priced from the zones of their sites and the rate card between them.</p>
    @if (readReceivables()) {
      <section>
        <h2>Docket receivables</h2>
        @if (loading()) { <p class="muted">Loading…</p> }
        @if (listError()) { <p class="error" role="alert">{{ listError() }}</p> }
        @if (!loading() && !listError() && receivables().length === 0) {
          <p class="muted" data-testid="no-receivables">No LTL docket has been priced yet.</p>
        }
        @if (receivables().length > 0) {
          <app-row-table testId="receivables" [rows]="receivables()" [columns]="columns" />
        }
      </section>
    }
    @if (manage()) {
      <section>
        <h2>Put a site in a zone</h2>
        <form class="inline" (ngSubmit)="assign()" data-testid="zone-form">
          <select name="terminal" aria-label="Site" data-testid="zone-terminal" [(ngModel)]="zone.terminalId" required>
            <option value="">Site…</option>
            @for (t of sites(); track t.id) { <option [value]="t.id">{{ t.label }}</option> }
          </select>
          <input name="zoneCode" placeholder="Zone code" aria-label="Zone code" data-testid="zone-code"
                 [(ngModel)]="zone.code" required />
          <label>
            <input name="oda" type="checkbox" data-testid="zone-oda" [(ngModel)]="zone.oda" /> Out of delivery area
          </label>
          <button type="submit" data-testid="assign-zone">Assign</button>
        </form>
        @if (zoneDone()) { <p data-testid="zone-done">{{ zoneDone() }}</p> }
        @if (zoneError()) { <p class="error" role="alert">{{ zoneError() }}</p> }
      </section>
      <section>
        <h2>Publish a rate card</h2>
        <form class="inline" (ngSubmit)="publish()" data-testid="rate-card-form">
          <input name="code" placeholder="Code" aria-label="Rate card code" data-testid="rc-code" [(ngModel)]="card.code" required />
          <input name="origin" placeholder="From zone" aria-label="From zone" data-testid="rc-origin"
                 [(ngModel)]="card.originZone" required />
          <input name="destination" placeholder="To zone" aria-label="To zone" data-testid="rc-destination"
                 [(ngModel)]="card.destinationZone" required />
          <input name="from" type="date" aria-label="Effective from" data-testid="rc-from" [(ngModel)]="card.effectiveFrom" required />
          <select name="basis" aria-label="Rate basis" data-testid="rc-basis" [(ngModel)]="card.rateBasis">
            @for (b of bases; track b) { <option [value]="b">{{ b }}</option> }
          </select>
          <input name="rate" type="number" step="0.01" placeholder="Rate" aria-label="Rate" data-testid="rc-rate"
                 [(ngModel)]="card.rateAmount" required />
          <input name="kgcft" type="number" step="0.01" placeholder="kg per cft" aria-label="kg per cft" data-testid="rc-kgcft"
                 [(ngModel)]="card.kgPerCft" required />
          <input name="min" type="number" step="0.01" placeholder="Minimum kg" aria-label="Minimum chargeable kg"
                 data-testid="rc-min" [(ngModel)]="card.minimum" />
          <input name="docket" type="number" step="0.01" placeholder="Docket charge" aria-label="Docket charge"
                 data-testid="rc-docket" [(ngModel)]="card.docketCharge" />
          <button type="submit" data-testid="publish-rate-card">Publish</button>
        </form>
        @if (cardDone()) { <p data-testid="rate-card-done">{{ cardDone() }}</p> }
        @if (cardError()) { <p class="error" role="alert">{{ cardError() }}</p> }
      </section>
    } @else {
      <p class="muted" data-testid="no-tariff-rights">You do not have the right to manage tariffs.</p>
    }
  `,
})
export class LtlRating implements OnInit {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly readReceivables = computed(() => this.session.can('INVOICE_READ'));
  readonly manage = computed(() => this.session.can('TARIFF_MANAGE'));
  readonly bases = RATE_BASES;
  readonly receivables = signal<Row[]>([]);
  readonly sites = signal<Choice[]>([]);
  readonly loading = signal(true);
  readonly listError = signal('');
  readonly zoneDone = signal('');
  readonly zoneError = signal('');
  readonly cardDone = signal('');
  readonly cardError = signal('');
  readonly columns = [
    { header: 'Docket', field: 'docketNo' }, { header: 'Customer', field: 'customerName' },
    { header: 'Lane', field: 'lane' }, { header: 'Rate card', field: 'rateCardCode' },
    { header: 'Chargeable kg', field: 'chargeableWeightKg' }, { header: 'Total', field: 'totalAmount' },
    { header: 'Status', field: 'status' },
  ];

  zone = { terminalId: '', code: '', oda: false };
  card = {
    code: '', originZone: '', destinationZone: '', effectiveFrom: new Date().toISOString().slice(0, 10),
    rateBasis: 'PER_KG' as RateBasis, rateAmount: null as number | null, kgPerCft: 6 as number | null,
    minimum: null as number | null, docketCharge: null as number | null,
  };

  async ngOnInit(): Promise<void> {
    if (this.manage()) {
      this.gateway.terminalChoices().then((s) => this.sites.set(s)).catch(() => undefined);
    }
    if (!this.readReceivables()) {
      this.loading.set(false);
      return;
    }
    try {
      this.receivables.set(await this.gateway.ltlReceivables());
    } catch (e) {
      this.listError.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }

  async assign(): Promise<void> {
    this.zoneDone.set('');
    this.zoneError.set('');
    try {
      await this.gateway.assignLtlZone(this.zone.terminalId, this.zone.code.trim(), this.zone.oda);
      this.zoneDone.set(`Zone ${this.zone.code.trim()} assigned.`);
    } catch (e) {
      this.zoneError.set((e as Error).message);
    }
  }

  async publish(): Promise<void> {
    this.cardDone.set('');
    this.cardError.set('');
    const c = this.card;
    try {
      await this.gateway.publishLtlRateCard({
        code: c.code.trim(), originZone: c.originZone.trim(), destinationZone: c.destinationZone.trim(),
        effectiveFrom: c.effectiveFrom, rateBasis: c.rateBasis, rateAmount: Number(c.rateAmount),
        kgPerCft: Number(c.kgPerCft), minimumChargeableWeightKg: c.minimum ?? undefined,
        docketCharge: c.docketCharge ?? undefined,
      });
      this.cardDone.set(`Rate card ${c.code.trim()} published.`);
    } catch (e) {
      this.cardError.set((e as Error).message);
    }
  }
}
