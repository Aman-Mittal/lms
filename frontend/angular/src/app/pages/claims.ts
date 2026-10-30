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
import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CLAIM_TYPES, ClaimTarget, ClaimType, LmsGateway, Row } from '../gateway/lms-gateway';
import { Session } from '../session';
import { Qr } from './qr';
import { RESOURCES } from './resources';

/** The panel beside the list: one claim, what may be done to it, and its slip. */
@Component({
  selector: 'app-claim-panel',
  imports: [FormsModule, Qr],
  template: `
    <aside class="panel" data-testid="claim-panel" aria-label="Claim details">
      <header>
        <h2>{{ row()['claimNo'] }}</h2>
        <button class="link" (click)="closed.emit()" aria-label="Close panel">Close</button>
      </header>
      <dl>
        <div><dt>Status</dt><dd data-testid="claim-status">{{ row()['status'] }}</dd></div>
        <div><dt>Type</dt><dd>{{ row()['claimType'] }}</dd></div>
        <div><dt>Claimed</dt><dd>{{ row()['amount'] }}</dd></div>
        <div><dt>Approved</dt><dd data-testid="claim-approved">{{ row()['approvedAmount'] || '—' }}</dd></div>
        <div><dt>Note</dt><dd>{{ row()['resolutionNote'] || '—' }}</dd></div>
      </dl>
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      @if (decide()) {
        @if (row()['status'] === 'OPEN') {
          <p><button data-testid="act-review" (click)="move('UNDER_REVIEW')">Take under review</button></p>
        }
        @if (row()['status'] === 'UNDER_REVIEW') {
          <label>
            Approved amount
            <input name="approved" data-testid="approved-amount" inputmode="decimal" [(ngModel)]="approved" />
          </label>
          <p><button data-testid="act-approve" (click)="move('APPROVED')">Approve</button></p>
          <label>
            Reason for rejecting
            <input name="note" data-testid="reject-note" [(ngModel)]="note" />
          </label>
          <p><button data-testid="act-reject" (click)="move('REJECTED')">Reject</button></p>
        }
        @if (row()['status'] === 'APPROVED') {
          <p><button data-testid="act-settle" (click)="move('SETTLED')">Settle</button></p>
        }
      }
      <p><button class="link" data-testid="print-claim" (click)="slip.set(true)">Print slip</button></p>
      @if (slip()) {
        <section class="slip" data-testid="claim-slip" aria-label="Claim slip">
          <h3>Claim slip</h3>
          <div class="slip-body">
            <dl>
              <div><dt>Claim</dt><dd>{{ row()['claimNo'] }}</dd></div>
              <div><dt>Type</dt><dd>{{ row()['claimType'] }}</dd></div>
              <div><dt>Claimed</dt><dd>{{ row()['amount'] }}</dd></div>
              <div><dt>Status</dt><dd>{{ row()['status'] }}</dd></div>
              <div><dt>Consignment</dt><dd>{{ row()['consignmentId'] }}</dd></div>
              <div><dt>Description</dt><dd>{{ row()['description'] }}</dd></div>
            </dl>
            <app-qr [payload]="'LMS:CLAIM:' + row()['claimNo']" />
          </div>
          <p class="actions no-print">
            <button data-testid="print-now" (click)="print()">Print</button>
          </p>
        </section>
      }
    </aside>
  `,
})
export class ClaimPanel {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly row = input.required<Row>();
  readonly closed = output<void>();
  readonly changed = output<void>();

  approved = '';
  note = '';
  readonly error = signal('');
  readonly slip = signal(false);
  readonly decide = computed(() => this.session.can('CLAIM_DECIDE'));

  async move(target: ClaimTarget): Promise<void> {
    this.error.set('');
    try {
      await this.gateway.transitionClaim(this.row()['id'], {
        target,
        approvedAmount: target === 'APPROVED' ? Number(this.approved) : undefined,
        resolutionNote: target === 'REJECTED' ? this.note : undefined,
      });
      this.changed.emit();
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  print(): void {
    window.print();
  }
}

/** Claims: the list, a form to raise one, and a panel to decide and print it. */
@Component({
  selector: 'app-claims',
  imports: [FormsModule, ClaimPanel],
  template: `
    <div [class.with-panel]="selected()">
      <div class="list">
        <h1>Claims</h1>
        @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
        @if (loading() && rows().length === 0) { <p>Loading…</p> }
        @else if (rows().length === 0 && !error()) { <p class="muted">Nothing here yet.</p> }
        @else if (rows().length > 0) {
          <table>
            <thead><tr>@for (c of columns; track c.field) { <th>{{ c.header }}</th> }</tr></thead>
            <tbody>
              @for (row of rows(); track row['id']) {
                <tr data-testid="row" tabindex="0" [class.selected]="row['id'] === selectedId()"
                    (click)="selectedId.set(row['id'])" (keydown.enter)="selectedId.set(row['id'])">
                  @for (c of columns; track c.field) { <td>{{ row[c.field] }}</td> }
                </tr>
              }
            </tbody>
          </table>
        }

        @if (session.can('CLAIM_RAISE')) {
          <h2>Raise a claim</h2>
          <form class="inline" (ngSubmit)="raise()" data-testid="raise-claim">
            <input name="consignmentId" placeholder="Consignment id" aria-label="Consignment id"
                   [(ngModel)]="consignmentId" required />
            <select name="claimType" aria-label="Claim type" [(ngModel)]="claimType">
              @for (t of claimTypes; track t) { <option [value]="t">{{ t }}</option> }
            </select>
            <input name="amount" placeholder="Amount" aria-label="Amount" inputmode="decimal"
                   [(ngModel)]="amount" required />
            <input name="description" placeholder="What happened" aria-label="Description"
                   [(ngModel)]="description" required />
            <button type="submit">Raise claim</button>
          </form>
          @if (formError()) { <p class="error" role="alert">{{ formError() }}</p> }
        }
      </div>
      @if (selected(); as row) {
        <!-- One panel per claim: a different id builds a fresh panel, with empty fields. -->
        @for (r of [row]; track r['id']) {
          <app-claim-panel [row]="r" (closed)="selectedId.set(null)" (changed)="load()" />
        }
      }
    </div>
  `,
})
export class ClaimsPage {
  protected readonly session = inject(Session);
  private readonly gateway = inject(LmsGateway);

  readonly columns = RESOURCES.find((r) => r.key === 'claims')!.columns;
  readonly claimTypes = CLAIM_TYPES;
  readonly rows = signal<Row[]>([]);
  readonly selectedId = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly selected = computed(() => this.rows().find((row) => row['id'] === this.selectedId()) ?? null);

  consignmentId = '';
  claimType: ClaimType = 'LOSS';
  amount = '';
  description = '';
  readonly formError = signal('');

  constructor() {
    effect(() => { void this.load(); });
  }

  async raise(): Promise<void> {
    this.formError.set('');
    try {
      await this.gateway.raiseClaim({
        consignmentId: this.consignmentId.trim(), claimType: this.claimType,
        amount: Number(this.amount), description: this.description,
      });
      this.consignmentId = '';
      this.amount = '';
      this.description = '';
      await this.load();
    } catch (e) {
      this.formError.set((e as Error).message);
    }
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.rows.set(await this.gateway.list('claims'));
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }
}
