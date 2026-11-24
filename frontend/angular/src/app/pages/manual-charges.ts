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
import {
  ApiProblem, CHARGE_SUBJECT_TYPES, ChargeEntryView, LmsGateway, ManualChargeView, PickKind,
} from '../gateway/lms-gateway';
import { PageHeader } from './page-header';
import { StatusBadge } from './status-badge';
import { Money } from './figures';
import { ErrorBanner } from './error-banner';
import { EntityPicker } from './entity-picker';
import { ConfirmHost } from './confirm-dialog';
import { confirmWording, type PendingConfirm } from '../../../../shared/confirm';

function valueOf(event: Event): string {
  return (event.target as HTMLInputElement).value;
}

/**
 * Charges raised by hand: from a catalogue entry against a trip, consignment or order. Above the
 * tenant's threshold the server answers that an approval was requested instead, and the charge
 * waits; the list shows each charge's status and lets one that is not yet billed be cancelled.
 */
@Component({
  selector: 'app-manual-charges',
  imports: [ConfirmHost, EntityPicker, ErrorBanner, PageHeader, StatusBadge, Money],
  template: `
    <app-page-header title="Manual charges" />
    <app-confirm-host [(pending)]="pending" />
    <p class="muted">Detention, loading, tolls and penalties raised by hand against a trip, consignment or order.</p>
    @if (refusal(); as r) {
      <app-error-banner [message]="r.message" [code]="r.code" testId="mc-error" />
    }
    @if (result()) { <p class="ok" role="status" data-testid="mc-result">{{ result() }}</p> }

    <form ngNativeValidate data-testid="mc-form" (submit)="submit($event)">
      <h2>Raise a charge</h2>
      <select aria-label="Charge" data-testid="mc-catalogue" (change)="catalogue.set(valueOf($event))">
        <option value="">Choose a charge</option>
        @for (e of entries(); track e.id) {
          <option [value]="e.id" [selected]="e.id === catalogue()">{{ e.code }} - {{ e.name }}</option>
        }
      </select>
      <select aria-label="Subject type" data-testid="mc-subject-type" (change)="subjectType.set(valueOf($event)); subject.set('')">
        @for (t of subjectTypes; track t) { <option [value]="t" [selected]="t === subjectType()">{{ t }}</option> }
      </select>
      <app-entity-picker [kind]="subjectKind()" placeholder="Trip, consignment or order id" ariaLabel="Subject id"
                         testId="mc-subject" [required]="true" [(value)]="subject" />
      <select aria-label="Direction" data-testid="mc-direction" (change)="direction.set(valueOf($event))">
        <option value="">Direction (from the charge)</option>
        <option value="RECEIVABLE">RECEIVABLE</option>
        <option value="PAYABLE">PAYABLE</option>
      </select>
      <app-entity-picker [kind]="'customer'" placeholder="Customer id (several customers aboard)" ariaLabel="Customer id"
                         testId="mc-customer" [(value)]="customer" />
      <input type="number" min="0" step="any" placeholder="Quantity" aria-label="Quantity" data-testid="mc-quantity"
             required [value]="quantity()" (input)="quantity.set(valueOf($event))" />
      <input type="number" min="0" step="any" [placeholder]="ratePlaceholder()" aria-label="Rate" data-testid="mc-rate"
             [value]="rate()" (input)="rate.set(valueOf($event))" />
      <input placeholder="Reason" aria-label="Reason" data-testid="mc-reason" required
             [value]="reason()" (input)="reason.set(valueOf($event))" />
      <input placeholder="Evidence reference" aria-label="Evidence reference" data-testid="mc-evidence"
             [value]="evidence()" (input)="evidence.set(valueOf($event))" />
      <button type="submit" data-testid="mc-submit">Raise charge</button>
    </form>

    <h2>Raised charges</h2>
    <p class="actions">
      <select aria-label="Filter by status" data-testid="mc-filter-status" (change)="filter($event)">
        <option value="">All statuses</option>
        @for (s of statuses; track s) { <option [value]="s">{{ s }}</option> }
      </select>
    </p>
    @if (charges().length === 0) {
      <p class="muted" data-testid="mc-empty">No charges.</p>
    } @else {
      <div class="table-wrap"><table data-testid="mc-list">
        <thead><tr><th>Charge</th><th>Code</th><th>Subject</th><th>Direction</th><th>Amount</th><th>Status</th><th>Billed</th><th></th></tr></thead>
        <tbody>
          @for (c of charges(); track c.id) {
            <tr data-testid="mc-row" [attr.data-id]="c.id">
              <td>{{ c.chargeNo }}</td><td>{{ c.chargeCode }}</td><td>{{ c.subjectType }} {{ c.subjectRef }}</td>
              <td data-testid="mc-direction-cell">{{ c.direction }}</td>
              <td class="num"><app-money data-testid="mc-amount" [value]="c.amount" [currency]="c.currency" /></td>
              <td><app-status-badge data-testid="mc-status" [value]="c.status" /></td>
              <td>{{ c.billed ? 'Yes' : 'No' }}</td>
              <td>
                @if ((c.status === 'PENDING_APPROVAL' || (c.status === 'APPROVED' && !c.billed))) {
                  <button type="button" class="link" data-testid="mc-cancel" [attr.aria-label]="'Cancel ' + c.chargeNo"
                          (click)="askCancel(c.id, c.chargeNo)">Cancel</button>
                }
              </td>
            </tr>
          }
        </tbody>
      </table></div>
      @if (next()) { <button type="button" class="link" (click)="more()">More</button> }
    }
  `,
})
export class ManualCharges implements OnInit {
  /** The destructive action waiting for its confirmation, shown by the confirm host in the template. */
  protected readonly pending = signal<PendingConfirm | null>(null);
  private readonly gateway = inject(LmsGateway);
  protected readonly valueOf = valueOf;
  readonly subjectTypes = CHARGE_SUBJECT_TYPES;
  readonly statuses = ['PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CANCELLED'];

  readonly entries = signal<ChargeEntryView[]>([]);
  readonly charges = signal<ManualChargeView[]>([]);
  readonly next = signal<string | null>(null);
  readonly status = signal('');
  readonly refusal = signal<{ code: string; message: string } | null>(null);
  readonly result = signal('');

  readonly catalogue = signal('');
  readonly subjectType = signal<string>('TRIP');
  readonly subject = signal('');
  /** What the subject picker offers for the chosen kind of subject. */
  readonly subjectKind = computed<PickKind>(() => (
    { TRIP: 'trip', CONSIGNMENT: 'consignment', ORDER: 'order' } as Record<string, PickKind>)[this.subjectType()] ?? 'trip');
  readonly direction = signal('');
  readonly customer = signal('');
  readonly quantity = signal('1');
  readonly rate = signal('');
  readonly reason = signal('');
  readonly evidence = signal('');

  ratePlaceholder(): string {
    const entry = this.entries().find((e) => e.id === this.catalogue());
    return entry ? `Rate (default ${entry.defaultRate})` : 'Rate (default from the charge)';
  }

  async ngOnInit(): Promise<void> {
    await this.attempt(async () => {
      this.entries.set((await this.gateway.chargeCatalogue({ q: '', activeOnly: true })).items);
      await this.load(false);
    });
  }

  async filter(event: Event): Promise<void> {
    this.status.set(valueOf(event));
    await this.attempt(() => this.load(false));
  }

  async more(): Promise<void> {
    await this.attempt(() => this.load(true));
  }

  async submit(event: Event): Promise<void> {
    event.preventDefault();
    await this.attempt(async () => {
      const outcome = await this.gateway.recordManualCharge({
        catalogueId: this.catalogue(), subjectType: this.subjectType(), subjectId: this.subject(),
        direction: this.direction(), customerId: this.customer(), quantity: Number(this.quantity()),
        rate: this.rate().trim() === '' ? null : Number(this.rate()), reason: this.reason(), evidenceRef: this.evidence(),
      });
      this.result.set(outcome.pending
        ? 'Awaiting approval: the charge is above the approval threshold and an approval request was raised.'
        : 'Raised: the charge is in force.');
      this.reason.set('');
      this.evidence.set('');
      await this.load(false);
    });
  }

  askCancel(id: string, chargeNo: string): void {
    this.pending.set({
      wording: confirmWording('Cancel charge', chargeNo),
      reason: { label: 'Cancel reason', testId: 'mc-cancel-reason', placeholder: 'Why', required: true },
      run: (why) => this.cancel(id, why),
    });
  }

  private async cancel(id: string, reason: string): Promise<void> {
    await this.attempt(async () => {
      await this.gateway.cancelManualCharge(id, reason);
      await this.load(false);
    });
  }

  private async load(append: boolean): Promise<void> {
    const page = await this.gateway.manualCharges({ status: this.status(), tripId: '' },
      append ? this.next() ?? undefined : undefined);
    this.charges.set(append ? [...this.charges(), ...page.items] : page.items);
    this.next.set(page.nextCursor);
  }

  private async attempt(action: () => Promise<void>): Promise<void> {
    this.refusal.set(null);
    this.result.set('');
    try {
      await action();
    } catch (e) {
      this.refusal.set({ code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message });
    }
  }
}
