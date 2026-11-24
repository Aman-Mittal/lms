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
import {
  ADVANCE_MODES, ApiProblem, LmsGateway, ManualChargeView, VendorAdvanceView,
} from '../gateway/lms-gateway';
import { Session } from '../session';
import { StatusBadge } from './status-badge';
import { Money } from './figures';
import { ErrorBanner } from './error-banner';
import { ConfirmHost } from './confirm-dialog';
import { confirmWording, type PendingConfirm } from '../../../../shared/confirm';

function valueOf(event: Event): string {
  return (event.target as HTMLInputElement).value;
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * The money on one trip: advances to its vendor (request, pay with a reference, cancel before
 * payment) and the manual charges raised against it, which are read only here. The right to
 * request or cancel an advance is ADVANCE_REQUEST and to record its payment PAYABLES_MANAGE;
 * the controls a person could not use are not drawn.
 */
@Component({
  selector: 'app-trip-money',
  imports: [ConfirmHost, ErrorBanner, StatusBadge, Money],
  template: `
    <section data-testid="trip-money" aria-label="Trip money">
      <h2>Advances and charges</h2>
      <app-confirm-host [(pending)]="pending" />
      @if (refusal(); as r) {
        <app-error-banner [message]="r.message" [code]="r.code" testId="adv-error" />
      }
      @if (result()) { <p class="ok" role="status" data-testid="adv-result">{{ result() }}</p> }
      @if (session.can('ADVANCE_REQUEST')) {
        <form ngNativeValidate data-testid="adv-form" (submit)="request($event)">
          <select aria-label="Advance mode" data-testid="adv-mode" (change)="mode.set(valueOf($event))">
            @for (m of modes; track m) { <option [value]="m" [selected]="m === mode()">{{ m }}</option> }
          </select>
          <input type="number" min="0" step="any" placeholder="Amount" aria-label="Advance amount" data-testid="adv-amount"
                 required [value]="amount()" (input)="amount.set(valueOf($event))" />
          <input placeholder="Reason" aria-label="Advance reason" data-testid="adv-reason" required
                 [value]="reason()" (input)="reason.set(valueOf($event))" />
          <button type="submit" data-testid="adv-submit">Request advance</button>
        </form>
      }
      @if (advances().length === 0) {
        <p class="muted" data-testid="adv-empty">No advances on this trip.</p>
      } @else {
        <div class="table-wrap"><table data-testid="advances">
          <thead><tr><th>Advance</th><th>Mode</th><th>Amount</th><th>Status</th><th>Reference</th><th></th></tr></thead>
          <tbody>
            @for (a of advances(); track a.id) {
              <tr data-testid="adv-row" [attr.data-id]="a.id">
                <td>{{ a.advanceNo }}</td><td>{{ a.mode }}</td>
                <td class="num"><app-money data-testid="adv-amount-cell" [value]="a.amount" [currency]="a.currency" /></td>
                <td><app-status-badge data-testid="adv-status" [value]="a.status" /></td>
                <td>{{ a.paidReference }}</td>
                <td>
                  @if (a.status === 'APPROVED' && session.can('PAYABLES_MANAGE')) {
                    <button type="button" class="link" data-testid="adv-pay" [attr.aria-label]="'Pay ' + a.advanceNo"
                            (click)="start(a.id)">Pay</button>
                  }
                  @if ((a.status === 'APPROVED' || a.status === 'PENDING_APPROVAL') && session.can('ADVANCE_REQUEST')) {
                    <button type="button" class="link" data-testid="adv-cancel" [attr.aria-label]="'Cancel ' + a.advanceNo"
                            (click)="askCancel(a.id, a.advanceNo)">Cancel</button>
                  }
                </td>
              </tr>
              @if (acting() === a.id) {
                <tr><td colspan="6">
                  <form ngNativeValidate class="inline" (submit)="pay($event, a.id)">
                    <input placeholder="Reference" aria-label="Payment reference" data-testid="adv-pay-reference" required
                           [value]="text()" (input)="text.set(valueOf($event))" />
                    <button type="submit" data-testid="adv-pay-confirm">Record payment</button>
                    <button type="button" class="link" (click)="acting.set('')">Keep</button>
                  </form>
                </td></tr>
              }
            }
          </tbody>
        </table></div>
      }
      <h3>Manual charges</h3>
      @if (charges().length === 0) {
        <p class="muted" data-testid="trip-charges-empty">No manual charges on this trip.</p>
      } @else {
        <div class="table-wrap"><table data-testid="trip-charges">
          <thead><tr><th>Charge</th><th>Code</th><th>Direction</th><th>Amount</th><th>Status</th></tr></thead>
          <tbody>
            @for (c of charges(); track c.id) {
              <tr data-testid="trip-charge-row">
                <td>{{ c.chargeNo }}</td><td>{{ c.chargeCode }}</td><td>{{ c.direction }}</td>
                <td class="num"><app-money [value]="c.amount" [currency]="c.currency" /></td><td><app-status-badge [value]="c.status" /></td>
              </tr>
            }
          </tbody>
        </table></div>
      }
    </section>
  `,
})
export class TripMoney implements OnInit {
  /** The destructive action waiting for its confirmation, shown by the confirm host in the template. */
  protected readonly pending = signal<PendingConfirm | null>(null);
  /** The trip these belong to. */
  readonly tripId = input.required<string>();
  private readonly gateway = inject(LmsGateway);
  protected readonly session = inject(Session);
  protected readonly valueOf = valueOf;
  readonly modes = ADVANCE_MODES;

  readonly advances = signal<VendorAdvanceView[]>([]);
  readonly charges = signal<ManualChargeView[]>([]);
  readonly refusal = signal<{ code: string; message: string } | null>(null);
  readonly result = signal('');
  readonly mode = signal<string>('BANK');
  readonly amount = signal('');
  readonly reason = signal('');
  readonly acting = signal('');
  readonly text = signal('');

  async ngOnInit(): Promise<void> {
    await this.attempt(() => this.load());
  }

  async request(event: Event): Promise<void> {
    event.preventDefault();
    await this.attempt(async () => {
      const outcome = await this.gateway.requestVendorAdvance({
        tripId: this.tripId(), mode: this.mode(), amount: Number(this.amount()), reason: this.reason(),
      });
      this.result.set(outcome.pending ? 'Awaiting approval: an approval request was raised.' : 'Requested.');
      this.amount.set('');
      this.reason.set('');
      await this.load();
    });
  }

  start(id: string): void {
    this.acting.set(id);
    this.text.set('');
  }

  async pay(event: Event, id: string): Promise<void> {
    event.preventDefault();
    await this.attempt(async () => {
      await this.gateway.payVendorAdvance(id, this.text(), today());
      this.acting.set('');
      await this.load();
    });
  }

  askCancel(id: string, advanceNo: string): void {
    this.pending.set({
      wording: confirmWording('Cancel advance', advanceNo),
      reason: { label: 'Cancel reason', testId: 'adv-cancel-reason', placeholder: 'Why', required: true },
      run: (why) => this.cancel(id, why),
    });
  }

  private async cancel(id: string, reason: string): Promise<void> {
    await this.attempt(async () => {
      await this.gateway.cancelVendorAdvance(id, reason);
      await this.load();
    });
  }

  private async load(): Promise<void> {
    this.advances.set((await this.gateway.vendorAdvances(this.tripId())).items);
    try {
      this.charges.set((await this.gateway.manualCharges({ status: '', tripId: this.tripId() })).items);
    } catch {
      // Reading charges needs a charge right; without it the list is simply empty.
      this.charges.set([]);
    }
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
