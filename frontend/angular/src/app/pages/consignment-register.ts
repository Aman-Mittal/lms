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
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ConsignmentCard, LmsGateway } from '../gateway/lms-gateway';
import type { BulkConsignmentOutcome } from '../gateway/consignment-bulk-types';
import { Session } from '../session';
import { StatusBadge } from './status-badge';
import { ErrorBanner } from './error-banner';
import { ConfirmHost } from './confirm-dialog';
import { DISMISS_LABEL, type PendingConfirm } from '../../../../shared/confirm';
import {
  BULK_CONSIGNMENT_PERMISSION, MAX_BULK_CONSIGNMENTS, PARTICULAR_FIELDS, PICK_LIMIT_MESSAGE, bulkChanges, bulkSummary,
  pickUpTo, type ParticularKey,
} from '../../../../shared/consignment-bulk';

interface Picked {
  id: string;
  ref: string;
  /** The version last read for it, which an amendment sends back. */
  version: string;
}

interface ResultRow extends BulkConsignmentOutcome {
  ref: string;
}

/**
 * The consignment register: every consignment by reference, a page at a time, with the means to pick several and amend or cancel
 * them together. The server judges each one on its own, so the answer is a table saying which went through and which was refused.
 * Inputs are signals bound with [value] and (input), as on the rest of the desk.
 */
@Component({
  selector: 'app-consignment-register',
  imports: [ConfirmHost, ErrorBanner, FormsModule, StatusBadge],
  template: `
    <section data-testid="consignment-register">
      <h2>Consignment register</h2>
      <form ngNativeValidate class="inline" (ngSubmit)="search()" data-testid="cons-list-filter">
        <input name="q" placeholder="Search by reference" aria-label="Search consignments" data-testid="cons-list-q"
               [value]="typed()" (input)="typed.set($any($event.target).value)" />
        <select name="status" aria-label="Show consignments in status" data-testid="cons-list-status"
                [value]="status()" (change)="setStatus($any($event.target).value)">
          <option value="">Any status</option>
          @for (s of statuses; track s) { <option [value]="s" [selected]="s === status()">{{ s }}</option> }
        </select>
        <button type="submit">Search</button>
      </form>
      @if (listError()) { <app-error-banner [message]="listError()" /> }

      @if (allowed()) {
        <p class="actions" data-testid="cons-bulk-bar">
          <span data-testid="cons-picked-count">{{ picked().length }} picked</span>
          <button type="button" data-testid="cons-bulk-amend" [disabled]="picked().length === 0 || busy()"
                  (click)="amending.set(true)">Amend picked</button>
          <button type="button" data-testid="cons-bulk-cancel" [disabled]="picked().length === 0 || busy()"
                  (click)="askCancel()">Cancel picked</button>
          @if (picked().length > 0) {
            <button type="button" class="link" data-testid="cons-pick-none" (click)="clearPicks()">Clear picks</button>
          }
        </p>
      }
      @if (limitHit()) { <p class="error" role="alert" data-testid="cons-pick-limit">{{ limitMessage }}</p> }
      <app-confirm-host [(pending)]="pending" />

      @if (amending() && allowed()) {
        <section data-testid="cons-bulk-amend-form" aria-label="Amend picked consignments">
          <h3>Amend {{ picked().length }} picked</h3>
          <p class="muted">
            What you fill in is set on every picked consignment; tick Clear to empty a field on all of them; a field left alone is
            kept as it is. Times are ISO-8601, such as 2026-12-01T06:00:00Z.
          </p>
          <form ngNativeValidate (ngSubmit)="apply()">
            @for (f of fields; track f.key) {
              <div>
                <label>{{ f.label }}
                  <input [name]="f.key" [attr.data-testid]="'bulk-particular-' + f.key" [attr.aria-label]="f.label + ' for picked'"
                         [value]="values()[f.key] ?? ''" [disabled]="!!cleared()[f.key]"
                         (input)="setValue(f.key, $any($event.target).value)" />
                </label>
                <label>
                  <input type="checkbox" [attr.data-testid]="'bulk-clear-' + f.key" [attr.aria-label]="'Clear ' + f.label + ' on picked'"
                         [checked]="!!cleared()[f.key]" (change)="setCleared(f.key, $any($event.target).checked)" />
                  Clear
                </label>
              </div>
            }
            <p class="actions">
              <button type="submit" data-testid="cons-bulk-apply" [disabled]="busy() || !hasChanges()">Apply to picked</button>
              <button type="button" data-testid="cons-bulk-amend-close" (click)="amending.set(false)">Close form</button>
            </p>
          </form>
        </section>
      }

      @if (error()) { <app-error-banner [message]="error()" testId="cons-bulk-error" /> }
      @if (summary()) { <p class="ok" role="status" data-testid="cons-bulk-done">{{ summary() }}</p> }
      @if (results(); as rs) {
        @if (rs.length) {
          <div class="table-wrap"><table data-testid="cons-bulk-results">
            <thead><tr><th>Consignment</th><th>Result</th><th>Why</th></tr></thead>
            <tbody>
              @for (r of rs; track r.consignmentId) {
                <tr data-testid="cons-bulk-result" [attr.data-outcome]="r.ok ? 'ok' : 'refused'">
                  <td>{{ r.ref }}</td>
                  <td>@if (r.ok) { OK } @else { <code data-testid="cons-bulk-result-code">{{ r.code || 'refused' }}</code> }</td>
                  <td>{{ r.ok ? '' : whyOf(r) }}</td>
                </tr>
              }
            </tbody>
          </table></div>
        }
      }

      <div class="table-wrap"><table data-testid="cons-list">
        <thead>
          <tr>
            @if (allowed()) {
              <th>
                <input type="checkbox" data-testid="cons-pick-all" aria-label="Pick all on this page" [checked]="allOnPage()"
                       [disabled]="rows().length === 0" (change)="togglePage($any($event.target))" />
              </th>
            }
            <th>Reference</th><th>Status</th><th>Consignee</th><th>Destination</th><th>Weight kg</th><th>Load</th>
          </tr>
        </thead>
        <tbody>
          @for (r of rows(); track r.id) {
            <tr data-testid="cons-row">
              @if (allowed()) {
                <td>
                  <input type="checkbox" data-testid="cons-pick" [attr.aria-label]="'Pick consignment ' + r.trackingRef"
                         [checked]="isPicked(r.id)" (change)="toggle(r, $any($event.target))" />
                </td>
              }
              <td>{{ r.trackingRef }}</td><td><app-status-badge [value]="r.status" /></td><td>{{ r.consigneeName }}</td>
              <td>{{ r.destinationTerminalCode }}</td><td class="num">{{ r.deadWeightKg }}</td><td>{{ r.loadNo || '—' }}</td>
            </tr>
          }
        </tbody>
      </table></div>
      @if (!loading() && rows().length === 0 && !listError()) { <p class="muted" data-testid="cons-list-empty">No consignments match.</p> }
      <p class="actions">
        <button type="button" data-testid="cons-page-prev" [disabled]="cursors().length < 2 || loading()" (click)="previous()">Previous page</button>
        <button type="button" data-testid="cons-page-next" [disabled]="!nextCursor() || loading()" (click)="next()">Next page</button>
        <span class="muted">Up to {{ max }} can be picked at once, across pages.</span>
      </p>
    </section>
  `,
})
export class ConsignmentRegister {
  protected readonly pending = signal<PendingConfirm | null>(null);
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly statuses = ['PLANNED', 'ASSIGNED_TO_LOAD', 'IN_TRANSIT', 'ARRIVED_AT_HUB', 'ASSIGNED_TO_DELIVERY', 'OUT_FOR_DELIVERY',
    'DELIVERED', 'CANCELLED', 'SPLIT'];
  readonly fields = PARTICULAR_FIELDS;
  readonly max = MAX_BULK_CONSIGNMENTS;
  readonly limitMessage = PICK_LIMIT_MESSAGE;

  readonly allowed = computed(() => this.session.can(BULK_CONSIGNMENT_PERMISSION));
  readonly status = signal('');
  readonly typed = signal('');
  readonly q = signal('');
  /** The cursor each page was read with; the first has none. */
  readonly cursors = signal<string[]>(['']);
  readonly rows = signal<ConsignmentCard[]>([]);
  readonly nextCursor = signal('');
  readonly loading = signal(true);
  readonly listError = signal('');
  readonly picked = signal<Picked[]>([]);
  readonly limitHit = signal(false);
  readonly amending = signal(false);
  readonly values = signal<Partial<Record<ParticularKey, string>>>({});
  readonly cleared = signal<Partial<Record<ParticularKey, boolean>>>({});
  readonly busy = signal(false);
  readonly error = signal('');
  readonly results = signal<ResultRow[] | null>(null);
  readonly summary = signal('');

  readonly allOnPage = computed(() => this.rows().length > 0 && this.rows().every((r) => this.isPicked(r.id)));
  readonly hasChanges = computed(() => Object.keys(bulkChanges(this.values(), this.cleared())).length > 0);

  constructor() {
    void this.load();
  }

  isPicked(id: string): boolean {
    return this.picked().some((p) => p.id === id);
  }

  whyOf(r: ResultRow): string {
    return [r.message, ...r.fieldErrors].filter(Boolean).join('; ');
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.listError.set('');
    try {
      const cursors = this.cursors();
      const page = await this.gateway.consignmentList(this.status(), this.q(), cursors[cursors.length - 1] || undefined);
      this.rows.set(page.rows);
      this.nextCursor.set(page.nextCursor);
      // Keep each picked consignment's version as fresh as the page that shows it.
      this.picked.update((current) => current.map((p) => {
        const row = page.rows.find((r) => r.id === p.id);
        return row && row.version !== p.version ? { ...p, version: row.version } : p;
      }));
    } catch (e) {
      this.rows.set([]);
      this.nextCursor.set('');
      this.listError.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }

  search(): void {
    this.q.set(this.typed().trim());
    this.cursors.set(['']);
    void this.load();
  }

  setStatus(value: string): void {
    this.status.set(value);
    this.cursors.set(['']);
    void this.load();
  }

  next(): void {
    this.cursors.update((c) => [...c, this.nextCursor()]);
    void this.load();
  }

  previous(): void {
    this.cursors.update((c) => c.slice(0, -1));
    void this.load();
  }

  private pick(rows: ConsignmentCard[]): void {
    const current = this.picked();
    const { picked: ids, truncated } = pickUpTo(current.map((p) => p.id), rows.map((r) => r.id));
    const known = [...current, ...rows.map((r) => ({ id: r.id, ref: r.trackingRef, version: r.version }))];
    this.picked.set(ids.map((id) => known.find((k) => k.id === id) as Picked));
    this.limitHit.set(truncated);
  }

  /** `box` is put back in line with what is picked: a pick refused at the limit must not stay ticked. */
  toggle(row: ConsignmentCard, box: HTMLInputElement): void {
    if (this.isPicked(row.id)) {
      this.picked.update((c) => c.filter((p) => p.id !== row.id));
      this.limitHit.set(false);
    } else {
      this.pick([row]);
    }
    box.checked = this.isPicked(row.id);
  }

  togglePage(box: HTMLInputElement): void {
    if (this.allOnPage()) {
      const rows = this.rows();
      this.picked.update((c) => c.filter((p) => !rows.some((r) => r.id === p.id)));
      this.limitHit.set(false);
    } else {
      this.pick(this.rows());
    }
    box.checked = this.allOnPage();
  }

  clearPicks(): void {
    this.picked.set([]);
    this.limitHit.set(false);
  }

  setValue(key: ParticularKey, value: string): void {
    this.values.update((v) => ({ ...v, [key]: value }));
  }

  setCleared(key: ParticularKey, on: boolean): void {
    this.cleared.update((c) => ({ ...c, [key]: on }));
  }

  askCancel(): void {
    const ids = this.picked().map((p) => p.id);
    this.pending.set({
      wording: {
        title: `Cancel ${ids.length} picked consignments?`, confirmLabel: 'Cancel picked', dismissLabel: DISMISS_LABEL,
        body: 'Each is taken off its load first; one that cannot be cancelled is refused and the rest go through.',
      },
      reason: { label: 'Reason for cancelling', testId: 'cons-bulk-cancel-reason', placeholder: 'Reason' },
      run: (reason) => this.run('cancelled', () => this.gateway.bulkCancelConsignments(ids, reason)),
    });
  }

  async apply(): Promise<void> {
    const changes = bulkChanges(this.values(), this.cleared());
    const items = this.picked().map((p) => ({ consignmentId: p.id, version: p.version, changes }));
    this.amending.set(false);
    await this.run('amended', () => this.gateway.bulkAmendConsignments(items));
    this.values.set({});
    this.cleared.set({});
  }

  private async run(done: 'amended' | 'cancelled', call: () => Promise<BulkConsignmentOutcome[]>): Promise<void> {
    this.error.set('');
    this.busy.set(true);
    const refs = new Map(this.picked().map((p) => [p.id, p.ref]));
    try {
      const outcomes = await call();
      this.results.set(outcomes.map((o) => ({ ...o, ref: refs.get(o.consignmentId) ?? o.consignmentId })));
      this.summary.set(bulkSummary(outcomes, done));
      const succeeded = new Set(outcomes.filter((o) => o.ok).map((o) => o.consignmentId));
      this.picked.update((c) => c.filter((p) => !succeeded.has(p.id)));
      await this.load();
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.busy.set(false);
    }
  }
}
