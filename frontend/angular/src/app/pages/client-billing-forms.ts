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

import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiProblem, LmsGateway } from '../gateway/lms-gateway';
import {
  HAND_RECORDED_SERVICES, MarginPage, ServiceUsageDraft, StorageMonthView,
} from '../gateway/client-billing-form-types';
import type { AccountRow } from '../gateway/user-admin-types';
import { Session } from '../session';
import { ErrorBanner } from './error-banner';
import { Money } from './figures';

interface Refusal { message: string; code: string }
const NONE: Refusal = { message: '', code: '' };
const refusalOf = (e: unknown): Refusal => ({ message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' });

/** Today as a `YYYY-MM-DD` day, in UTC like the API. */
function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/** The month before this one, as `YYYY-MM`: the latest a month can have ended. */
function lastMonth(): string {
  const d = new Date();
  d.setUTCDate(1);
  d.setUTCMonth(d.getUTCMonth() - 1);
  return d.toISOString().slice(0, 7);
}

const blankUsage = (): ServiceUsageDraft => ({
  serviceType: 'HANDLING_IN', serviceCode: '', quantity: '', occurredOn: today(), reference: '', sourceKey: '',
});

/**
 * The client desk's three client-scoped forms: letting a user into the client's portal, recording a service used by
 * hand, and computing a month's storage. Each one is shown only to someone who may do it.
 */
@Component({
  selector: 'app-client-billing-forms',
  imports: [ErrorBanner, FormsModule],
  template: `
    @if (session.can('CLIENT_CONTRACT_MANAGE')) {
      <h3>Portal access</h3>
      <form ngNativeValidate class="inline" data-testid="cb-portal-form" (submit)="grant($event)">
        @if (users(); as list) {
          <select aria-label="Portal user" data-testid="cb-portal-user" required
                  [value]="portalUser()" (change)="portalUser.set($any($event.target).value)">
            <option value="" [selected]="!portalUser()">Choose a user</option>
            @for (u of list; track u.id) { <option [value]="u.id" [selected]="u.id === portalUser()">{{ u.fullName }} ({{ u.email }})</option> }
          </select>
        } @else {
          <input placeholder="User id" aria-label="Portal user id" data-testid="cb-portal-user" required
                 pattern="[0-9a-fA-F\\-]{36}" title="The user's id, as shown on the users screen"
                 [value]="portalUser()" (input)="portalUser.set($any($event.target).value)" />
        }
        <button type="submit" data-testid="cb-portal-submit">Grant portal access</button>
      </form>
      @if (portalDone()) { <p class="ok" role="status" data-testid="cb-portal-done">Portal access granted to {{ portalDone() }}.</p> }
      @if (portalError().message) {
        <app-error-banner [message]="portalError().message" [code]="portalError().code" testId="cb-portal-error" codeTestId="cb-portal-refusal" />
      }
    }

    @if (session.can('CLIENT_BILLING_RUN')) {
      <h3>Service usage</h3>
      <form ngNativeValidate class="inline" data-testid="cb-usage-form" (submit)="record($event)">
        <select aria-label="Service used" data-testid="cb-usage-type" [value]="usage().serviceType"
                (change)="setUsage('serviceType', $any($event.target).value)">
          @for (s of services; track s) { <option [value]="s" [selected]="s === usage().serviceType">{{ s }}</option> }
        </select>
        @if (usage().serviceType === 'VALUE_ADDED') {
          <input placeholder="Service code" aria-label="Usage service code" data-testid="cb-usage-code" required
                 [value]="usage().serviceCode" (input)="setUsage('serviceCode', $any($event.target).value)" />
        }
        <input placeholder="Quantity used" aria-label="Usage quantity" inputmode="decimal" data-testid="cb-usage-quantity" required
               [value]="usage().quantity" (input)="setUsage('quantity', $any($event.target).value)" />
        <input type="date" aria-label="Usage day" data-testid="cb-usage-day" required
               [value]="usage().occurredOn" (input)="setUsage('occurredOn', $any($event.target).value)" />
        <input placeholder="Reference (optional)" aria-label="Usage reference" data-testid="cb-usage-reference"
               [value]="usage().reference" (input)="setUsage('reference', $any($event.target).value)" />
        <input placeholder="Source key (your own id for this usage)" aria-label="Usage source key" data-testid="cb-usage-key" required
               [value]="usage().sourceKey" (input)="setUsage('sourceKey', $any($event.target).value)" />
        <button type="submit" data-testid="cb-usage-submit">Record service usage</button>
      </form>
      @if (usageDone()) { <p class="ok" role="status" data-testid="cb-usage-done">Usage {{ usageDone() }} recorded.</p> }
      @if (usageError().message) {
        <app-error-banner [message]="usageError().message" [code]="usageError().code" testId="cb-usage-error" codeTestId="cb-usage-refusal" />
      }
    }

    <h3>Storage by month</h3>
    <form ngNativeValidate class="inline" data-testid="cb-storage-form" (submit)="storage($event, false)">
      <input type="month" aria-label="Storage month" data-testid="cb-storage-month" required
             [value]="storageMonth()" (input)="storageMonth.set($any($event.target).value)" />
      <button type="submit" data-testid="cb-storage-view">View storage</button>
      @if (session.can('CLIENT_BILLING_RUN')) {
        <button type="button" data-testid="cb-storage-compute" (click)="storage($event, true)">Compute storage</button>
      }
    </form>
    @if (storageDone()) { <p class="ok" role="status" data-testid="cb-storage-done">Storage for {{ storageDone() }} computed.</p> }
    @if (storageError().message) {
      <app-error-banner [message]="storageError().message" [code]="storageError().code" testId="cb-storage-error" codeTestId="cb-storage-refusal" />
    }
    @if (storageView(); as v) {
      <section data-testid="cb-storage-result" aria-label="Storage for the month">
        @if (v.computed) {
          <dl>
            <div><dt>Unit-days</dt><dd data-testid="cb-storage-unit-days">{{ v.unitDays }}</dd></div>
            <div><dt>Pallet-days</dt><dd data-testid="cb-storage-pallet-days">{{ v.palletDays }}</dd></div>
          </dl>
        } @else {
          <p class="muted" data-testid="cb-storage-pending">This month has not been computed yet.</p>
        }
        @if (v.days.length > 0) {
          <div class="table-wrap"><table>
            <thead><tr><th>Day</th><th class="num">Units held</th><th class="num">Units / pallet</th><th class="num">Pallets</th><th>Charged</th></tr></thead>
            <tbody>
              @for (d of v.days; track d.heldOn) {
                <tr data-testid="cb-storage-day">
                  <td class="nowrap">{{ d.heldOn }}</td><td class="num">{{ d.unitsHeld }}</td>
                  <td class="num">{{ d.unitsPerPallet }}</td><td class="num">{{ d.pallets }}</td><td>{{ d.charged ? 'Yes' : '' }}</td>
                </tr>
              }
            </tbody>
          </table></div>
        }
      </section>
    }
  `,
})
export class ClientBillingForms implements OnInit {
  private readonly gateway = inject(LmsGateway);
  readonly session = inject(Session);

  readonly clientId = input.required<string>();
  /** Something was recorded or computed: the desk reads its lists again. */
  readonly changed = output<void>();
  readonly services = HAND_RECORDED_SERVICES;

  /** The active users to pick from; null while loading or when the list could not be read, which falls back to an id. */
  readonly users = signal<AccountRow[] | null>(null);
  readonly portalUser = signal('');
  readonly portalDone = signal('');
  readonly portalError = signal(NONE);

  readonly usage = signal<ServiceUsageDraft>(blankUsage());
  readonly usageDone = signal('');
  readonly usageError = signal(NONE);

  readonly storageMonth = signal(lastMonth());
  readonly storageView = signal<StorageMonthView | null>(null);
  readonly storageDone = signal('');
  readonly storageError = signal(NONE);

  ngOnInit(): void {
    if (this.session.can('CLIENT_CONTRACT_MANAGE')) {
      this.gateway.accounts('', 'ACTIVE').then((found) => this.users.set(found), () => this.users.set(null));
    }
  }

  setUsage<K extends keyof ServiceUsageDraft>(key: K, value: ServiceUsageDraft[K]): void {
    this.usage.update((d) => ({ ...d, [key]: value }));
  }

  async grant(event: Event): Promise<void> {
    event.preventDefault();
    this.portalDone.set('');
    this.portalError.set(NONE);
    try {
      const granted = this.portalUser().trim();
      const who = this.users()?.find((u) => u.id === granted);
      await this.gateway.grantPortalAccess(this.clientId(), granted);
      this.portalUser.set('');
      this.portalDone.set(who ? who.fullName : granted);
    } catch (e) {
      this.portalError.set(refusalOf(e));
    }
  }

  async record(event: Event): Promise<void> {
    event.preventDefault();
    this.usageDone.set('');
    this.usageError.set(NONE);
    try {
      const key = this.usage().sourceKey.trim();
      await this.gateway.recordServiceUsage(this.clientId(), this.usage());
      this.usage.set(blankUsage());
      this.usageDone.set(key);
      this.changed.emit();
    } catch (e) {
      this.usageError.set(refusalOf(e));
    }
  }

  async storage(event: Event, compute: boolean): Promise<void> {
    event.preventDefault();
    this.storageDone.set('');
    this.storageError.set(NONE);
    try {
      const period = `${this.storageMonth()}-01`;
      this.storageView.set(await (compute
        ? this.gateway.computeClientStorage(this.clientId(), period)
        : this.gateway.clientStorage(this.clientId(), period)));
      if (compute) {
        this.storageDone.set(this.storageMonth());
        this.changed.emit();
      }
    } catch (e) {
      this.storageError.set(refusalOf(e));
    }
  }
}

/** The margin of every client invoice of a billed month: what was billed, what the carriers cost, what is left. */
@Component({
  selector: 'app-margin-report',
  imports: [ErrorBanner, FormsModule, Money],
  template: `
    @if (session.can('CLIENT_MARGIN_READ')) {
      <section data-testid="cb-margins" aria-label="Client margins">
        <h2>Margins</h2>
        <form ngNativeValidate class="inline" data-testid="cb-margin-form" (submit)="show($event, false)">
          <input type="month" aria-label="Margin month" data-testid="cb-margin-month" required
                 [value]="month()" (input)="month.set($any($event.target).value)" />
          <button type="submit" data-testid="cb-margin-show">Show margins</button>
        </form>
        @if (error().message) {
          <app-error-banner [message]="error().message" [code]="error().code" testId="cb-margin-error" codeTestId="cb-margin-refusal" />
        }
        @if (page(); as p) {
          @if (p.rows.length === 0) {
            <p class="muted" data-testid="cb-margin-empty">No invoice was issued in this month.</p>
          } @else {
            <div class="table-wrap"><table>
              <thead>
                <tr>
                  <th>Client</th><th>Invoice</th><th class="num">Billed</th><th class="num">Transport</th>
                  <th class="num">Warehouse</th><th class="num">Credited</th><th class="num">Carrier cost</th>
                  <th class="num">Margin</th><th class="num">Margin %</th><th class="num">Movements</th>
                  <th class="num">Unpriced</th>
                </tr>
              </thead>
              <tbody>
                @for (r of p.rows; track $index) {
                  <tr data-testid="cb-margin-row">
                    <td>{{ r['clientCode'] }} · {{ r['clientName'] }}</td><td>{{ r['invoiceNo'] }}</td>
                    <td class="num"><app-money [value]="r['receivable']" [currency]="r['currency']" /></td>
                    <td class="num"><app-money [value]="r['transportReceivable']" [currency]="r['currency']" /></td>
                    <td class="num"><app-money [value]="r['warehouseReceivable']" [currency]="r['currency']" /></td>
                    <td class="num"><app-money [value]="r['credited']" [currency]="r['currency']" /></td>
                    <td class="num"><app-money [value]="r['carrierPayable']" [currency]="r['currency']" /></td>
                    <td class="num"><app-money data-testid="cb-margin-amount" [value]="r['margin']" [currency]="r['currency']" /></td>
                    <td class="num">{{ r['marginPct'] }}</td><td class="num">{{ r['movements'] }}</td>
                    <td class="num">{{ r['unpricedMovements'] }}</td>
                  </tr>
                }
              </tbody>
            </table></div>
          }
          @if (p.nextCursor) {
            <button type="button" data-testid="cb-margin-more" (click)="show($event, true)">Load more margins</button>
          }
        }
      </section>
    }
  `,
})
export class MarginReport {
  private readonly gateway = inject(LmsGateway);
  readonly session = inject(Session);

  readonly month = signal(lastMonth());
  readonly page = signal<MarginPage | null>(null);
  readonly error = signal(NONE);

  async show(event: Event, more: boolean): Promise<void> {
    event.preventDefault();
    this.error.set(NONE);
    const held = this.page();
    try {
      const next = await this.gateway.clientMargins(`${this.month()}-01`, more ? held?.nextCursor : undefined);
      this.page.set(more && held ? { rows: [...held.rows, ...next.rows], nextCursor: next.nextCursor } : next);
    } catch (e) {
      this.error.set(refusalOf(e));
    }
  }
}
