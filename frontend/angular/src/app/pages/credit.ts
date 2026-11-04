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

import { Component, OnInit, effect, inject, input, output, signal } from '@angular/core';
import {
  ApiProblem, CREDIT_CHANGE_STATUSES, CreditChangeStatus, CreditPositionView, CreditSubjectType, LmsGateway, Row,
} from '../gateway/lms-gateway';
import { Session } from '../session';
import { EntityPicker } from './entity-picker';
import { RowColumn, RowTable } from './row-table';

/** The problem a hard credit limit refuses an action with. */
export const CREDIT_LIMIT_EXCEEDED = 'credit-limit-exceeded';

/** A typed amount, or nothing when it was left blank. */
function amountOrAbsent(value: string): number | undefined {
  const trimmed = value.trim();
  return trimmed ? Number(trimmed) : undefined;
}

/**
 * A customer's credit position: its limits, what it owes or is committed to, and the
 * headroom left. Read again whenever `refreshKey` changes, so a desk can pass whatever it
 * re-reads after an action.
 */
@Component({
  selector: 'app-credit-position',
  template: `
    <section class="credit" data-testid="credit-position" aria-label="Credit position">
      <h3>Credit position</h3>
      @if (error()) { <p class="error" role="alert" data-testid="credit-position-error">{{ error() }}</p> }
      @if (position(); as p) {
        <dl>
          <div><dt>Customer</dt><dd data-testid="credit-partner">{{ p.partnerCode }} · {{ p.partnerStatus }}</dd></div>
          <div><dt>State</dt><dd data-testid="credit-state">{{ p.limitState }}</dd></div>
          <div><dt>Credit limit</dt><dd data-testid="credit-limit">{{ p.creditLimit || 'No limit' }}</dd></div>
          <div><dt>Soft limit</dt><dd data-testid="credit-soft-limit">{{ p.softLimit || '—' }}</dd></div>
          <div><dt>Open orders</dt><dd data-testid="credit-open-orders">{{ p.openOrders }} ({{ p.openOrderCount }})</dd></div>
          <div><dt>Not yet invoiced</dt><dd data-testid="credit-unbilled">{{ p.unbilledCharges }}</dd></div>
          <div><dt>Unpaid invoices</dt><dd data-testid="credit-outstanding">{{ p.outstandingInvoices }}</dd></div>
          <div><dt>Exposure</dt><dd data-testid="credit-exposure">{{ p.exposure }} {{ p.currency }}</dd></div>
          <div><dt>Headroom</dt><dd data-testid="credit-headroom">{{ p.headroom || '—' }}</dd></div>
        </dl>
        @if (!p.trading) {
          <p class="error" data-testid="credit-not-trading">
            {{ p.partnerCode }} is {{ p.partnerStatus }}: its orders cannot be validated, amended or given new lines
            (<code>customer-not-trading</code>).
          </p>
        }
        @if (p.limitState === 'SOFT_EXCEEDED') {
          <p class="muted" data-testid="credit-soft-warning">Exposure is above the soft limit; the hard limit still has room.</p>
        }
        @if (p.unpricedOpenOrderCount !== '0' && p.unpricedOpenOrderCount) {
          <p class="muted" data-testid="credit-unpriced">
            {{ p.unpricedOpenOrderCount }} open orders are counted at nothing: no client contract prices them.
          </p>
        }
        @if (p.pendingChangeId) {
          <p class="muted" data-testid="credit-change-pending">A credit limit change is waiting for approval.</p>
        }
      }
    </section>
  `,
})
export class CreditPositionPanel {
  private readonly gateway = inject(LmsGateway);

  readonly partnerId = input.required<string>();
  /** Anything that changes when the desk has read its record again. */
  readonly refreshKey = input<unknown>(null);

  readonly position = signal<CreditPositionView | null>(null);
  readonly error = signal('');

  constructor() {
    effect(() => {
      const id = this.partnerId();
      this.refreshKey();
      void this.read(id);
    });
  }

  private async read(id: string): Promise<void> {
    this.error.set('');
    if (!id) {
      this.position.set(null);
      return;
    }
    try {
      this.position.set(await this.gateway.creditPosition(id));
    } catch (e) {
      this.position.set(null);
      this.error.set((e as Error).message);
    }
  }
}

/**
 * What a hard credit limit refusal says in figures, and, for a caller holding
 * CREDIT_OVERRIDE, a one-time override with a reason. `granted` fires once the override is
 * on record, so the desk can try the refused action again.
 */
@Component({
  selector: 'app-credit-override',
  template: `
    <section class="credit" data-testid="credit-refusal">
      <p data-testid="credit-refusal-figures">
        Exposure {{ errors()['exposure'] || '?' }} against a credit limit of {{ errors()['creditLimit'] || '?' }}.
      </p>
      @if (session.can('CREDIT_OVERRIDE')) {
        <form class="inline" data-testid="credit-override" (submit)="grant($event)">
          <input aria-label="Override reason" placeholder="Why it may go through" [value]="reason()"
                 (input)="reason.set($any($event.target).value)" required />
          <input aria-label="Override up to" placeholder="Exposure allowed" inputmode="decimal" [value]="upTo()"
                 (input)="upTo.set($any($event.target).value)" required />
          <button type="submit" data-testid="credit-override-submit">Override and retry</button>
          @if (failure()) {
            <span class="error" role="alert" data-testid="credit-override-error">
              @if (failureCode()) { <code>{{ failureCode() }}</code> }
              {{ failure() }}
            </span>
          }
        </form>
      } @else {
        <p class="muted" data-testid="credit-override-unavailable">Only a holder of CREDIT_OVERRIDE can let this through.</p>
      }
    </section>
  `,
})
export class CreditOverride {
  private readonly gateway = inject(LmsGateway);
  readonly session = inject(Session);

  readonly partnerId = input.required<string>();
  readonly subjectType = input.required<CreditSubjectType>();
  readonly orderId = input('');
  /** For a billing run: any `YYYY-MM-DD` day of the month. */
  readonly period = input('');
  /** The refusal's `errors`, which name the exposure and the limit. */
  readonly errors = input<Record<string, string>>({});
  readonly granted = output<string>();

  readonly reason = signal('');
  readonly upTo = signal('');
  readonly failure = signal('');
  readonly failureCode = signal('');

  constructor() {
    effect(() => this.upTo.set(this.errors()['exposure'] ?? ''));
  }

  async grant(event: Event): Promise<void> {
    event.preventDefault();
    this.failure.set('');
    this.failureCode.set('');
    try {
      const id = await this.gateway.grantCreditOverride(this.partnerId(), {
        subjectType: this.subjectType(), orderId: this.orderId() || undefined, period: this.period() || undefined,
        upTo: Number(this.upTo().trim()), reason: this.reason(),
      });
      this.reason.set('');
      this.granted.emit(id);
    } catch (e) {
      this.failureCode.set(e instanceof ApiProblem ? e.code : '');
      this.failure.set((e as Error).message);
    }
  }
}

const CHANGE_COLUMNS: RowColumn[] = [
  { header: 'Partner', field: 'partnerCode' }, { header: 'Status', field: 'status' },
  { header: 'From', field: 'previousLimit' }, { header: 'To', field: 'requestedLimit' },
  { header: 'Soft limit', field: 'requestedSoftLimit' }, { header: 'Reason', field: 'reason' },
  { header: 'Asked by', field: 'requestedBy' }, { header: 'Asked at', field: 'requestedAt' },
  { header: 'Decided by', field: 'decidedBy' }, { header: 'Note', field: 'decisionNote' },
];

/**
 * Credit limits, maker and checker: one person asks for a customer's new limit, another
 * approves or rejects it, and only an approval changes the limit. Every request stays as
 * the customer's change log.
 */
@Component({
  selector: 'app-credit-limits',
  imports: [EntityPicker, RowTable, CreditPositionPanel],
  template: `
    <h1>Credit limits</h1>
    <p class="muted">
      A new credit limit applies only once someone other than the person who asked for it approves it.
    </p>
    @if (refusal(); as r) {
      <p class="error" role="alert" data-testid="credit-admin-error">
        @if (r.code) { <code data-testid="credit-admin-refusal">{{ r.code }}</code> }
        {{ r.message }}
      </p>
    }
    @if (message()) { <p class="ok" role="status" data-testid="credit-admin-message">{{ message() }}</p> }

    <form class="inline" data-testid="credit-partner-form" (submit)="pickPartner($event)">
      <app-entity-picker [kind]="'customer'" placeholder="Customer" ariaLabel="Credit customer id" testId="credit-customer"
                         [required]="true" [(value)]="typed" />
      <button type="submit">Open</button>
    </form>

    @if (partnerId(); as id) {
      <app-credit-position [partnerId]="id" [refreshKey]="history()" />
      @if (session.can('CREDIT_LIMIT_REQUEST')) {
        <form class="inline" data-testid="credit-change-request" (submit)="request($event, id)">
          <h2>Ask for a new limit</h2>
          <input aria-label="New credit limit" placeholder="Credit limit" inputmode="decimal" [value]="limit()"
                 (input)="limit.set($any($event.target).value)" required />
          <input aria-label="New soft limit" placeholder="Soft limit (optional)" inputmode="decimal" [value]="softLimit()"
                 (input)="softLimit.set($any($event.target).value)" />
          <input aria-label="Change reason" placeholder="Why" [value]="reason()"
                 (input)="reason.set($any($event.target).value)" required />
          <button type="submit" data-testid="credit-change-submit">Ask for approval</button>
        </form>
      }
      <h2>Change log</h2>
      @if (history().length === 0) {
        <p class="muted" data-testid="credit-history-empty">No change has been asked for.</p>
      } @else {
        <app-row-table testId="credit-history" [rows]="history()" [columns]="columns" />
      }
    }

    <h2>Waiting for approval</h2>
    <label>
      Show
      <select aria-label="Change status" data-testid="credit-change-status" (change)="pickStatus($any($event.target).value)">
        @for (s of statuses; track s) { <option [value]="s" [selected]="s === status()">{{ s }}</option> }
      </select>
    </label>
    @if (queue().length === 0) {
      <p class="muted" data-testid="credit-queue-empty">Nothing here.</p>
    } @else {
      <table data-testid="credit-queue">
        <thead>
          <tr><th>Partner</th><th>From</th><th>To</th><th>Soft limit</th><th>Reason</th><th>Asked by</th><th>Status</th><th></th></tr>
        </thead>
        <tbody>
          @for (c of queue(); track c['id']) {
            <tr data-testid="credit-queue-row" [attr.data-id]="c['id']">
              <td>{{ c['partnerCode'] }}</td><td>{{ c['previousLimit'] || '—' }}</td><td>{{ c['requestedLimit'] }}</td>
              <td>{{ c['requestedSoftLimit'] || '—' }}</td><td>{{ c['reason'] }}</td><td>{{ c['requestedBy'] }}</td>
              <td>{{ c['status'] }}</td>
              <td>
                @if (c['status'] === 'PENDING' && session.can('CREDIT_LIMIT_APPROVE')) {
                  <input [attr.aria-label]="'Decision note for ' + c['partnerCode']" placeholder="Note"
                         [value]="notes()[c['id']] ?? ''" (input)="setNote(c['id'], $any($event.target).value)" />
                  <button type="button" data-testid="credit-approve" (click)="decide(c['id'], true)">Approve</button>
                  <button type="button" class="link" data-testid="credit-reject" (click)="decide(c['id'], false)">Reject</button>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class CreditLimitsPage implements OnInit {
  private readonly gateway = inject(LmsGateway);
  readonly session = inject(Session);

  readonly columns = CHANGE_COLUMNS;
  readonly statuses = CREDIT_CHANGE_STATUSES;

  readonly typed = signal('');
  readonly partnerId = signal('');
  readonly history = signal<Row[]>([]);
  readonly status = signal<CreditChangeStatus>('PENDING');
  readonly queue = signal<Row[]>([]);
  readonly notes = signal<Record<string, string>>({});
  readonly limit = signal('');
  readonly softLimit = signal('');
  readonly reason = signal('');
  readonly refusal = signal<{ code: string; message: string } | null>(null);
  readonly message = signal('');

  ngOnInit(): void {
    void this.readQueue();
  }

  async pickPartner(event: Event): Promise<void> {
    event.preventDefault();
    this.partnerId.set(this.typed().trim().toLowerCase());
    await this.readHistory();
  }

  async pickStatus(value: string): Promise<void> {
    this.status.set(value as CreditChangeStatus);
    await this.readQueue();
  }

  setNote(id: string, value: string): void {
    this.notes.update((n) => ({ ...n, [id]: value }));
  }

  async request(event: Event, partnerId: string): Promise<void> {
    event.preventDefault();
    await this.attempt('Change asked for; it waits for approval.', async () => {
      await this.gateway.requestCreditLimitChange(partnerId, {
        creditLimit: Number(this.limit().trim()), softLimit: amountOrAbsent(this.softLimit()), reason: this.reason(),
      });
      this.limit.set('');
      this.softLimit.set('');
      this.reason.set('');
    });
  }

  async decide(id: string, approve: boolean): Promise<void> {
    const note = this.notes()[id] ?? '';
    await this.attempt(approve ? 'Approved; the new limit applies.' : 'Rejected; the limit stays as it was.', () =>
      approve ? this.gateway.approveCreditLimitChange(id, note) : this.gateway.rejectCreditLimitChange(id, note));
  }

  private async attempt(outcome: string, action: () => Promise<unknown>): Promise<void> {
    this.refusal.set(null);
    this.message.set('');
    try {
      await action();
      this.message.set(outcome);
    } catch (e) {
      this.refusal.set({ code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message });
    }
    await Promise.all([this.readQueue(), this.readHistory()]);
  }

  private async readQueue(): Promise<void> {
    try {
      this.queue.set(await this.gateway.creditLimitChanges(this.status()));
    } catch (e) {
      this.refusal.set({ code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message });
    }
  }

  private async readHistory(): Promise<void> {
    const id = this.partnerId();
    if (!id) {
      return;
    }
    try {
      this.history.set(await this.gateway.partnerCreditLimitChanges(id));
    } catch (e) {
      this.history.set([]);
      this.refusal.set({ code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message });
    }
  }
}
