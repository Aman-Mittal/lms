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
  ENQUIRY_STATUSES, EnquiryDesk, EnquiryFilter, EnquiryLineDraft, EnquiryLineView, EnquiryOutcome, EnquiryRow,
} from '../gateway/enquiry-types';
import { Session } from '../session';
import { EntityPicker } from './entity-picker';
import { PageHeader } from './page-header';
import { StatusBadge } from './status-badge';
import { When } from './when';
import { ErrorBanner } from './error-banner';

/** A refusal as the desk shows it: what the server said, and its problem code. */
function problemText(e: unknown): string {
  const problem = e as ApiProblem;
  return problem.code ? `${problem.message} (${problem.code})` : problem.message;
}

const BLANK_LINE: EnquiryLineDraft = {
  originTerminalId: '', destinationTerminalId: '', loadType: '', material: '', quantity: '', uom: 'KG', weightKg: '',
  wantedPickupOn: '',
};

/** What is typed against one line of the open enquiry. */
interface LineInputs {
  amount: string;
  reason: string;
  wonQuantity: string;
}

/**
 * The enquiry desk: a customer's request for a price, quoted line by line, decided line by line, and then
 * turned into a draft order. Every change carries the enquiry's version, so two people cannot overwrite
 * each other; converting does not, because asking twice must give the same order.
 */
@Component({
  selector: 'app-enquiry-desk',
  imports: [ErrorBanner, FormsModule, EntityPicker, PageHeader, StatusBadge, When],
  template: `
    <app-page-header title="Enquiry desk" />
    <p class="muted">Quote each line, record who won it, and convert what was won into a draft order.</p>

    <form ngNativeValidate class="inline" (ngSubmit)="load()" data-testid="enquiry-filter">
      <select aria-label="Status" data-testid="enquiry-filter-status" [value]="filter().status"
              (change)="setFilter('status', $any($event.target).value)">
        <option value="" [selected]="!filter().status">Any status</option>
        @for (s of statuses; track s) { <option [value]="s" [selected]="s === filter().status">{{ s }}</option> }
      </select>
      <input name="q" placeholder="Number or reference" aria-label="Search enquiries" data-testid="enquiry-filter-q"
             [value]="filter().q" (input)="setFilter('q', $any($event.target).value)" />
      <button type="submit" data-testid="enquiry-filter-apply">Show</button>
    </form>
    @if (error()) { <app-error-banner [message]="error()" testId="enquiry-error" /> }

    <div class="table-wrap"><table data-testid="enquiry-list">
      <thead><tr><th>Enquiry</th><th>Customer</th><th>Status</th><th>Respond by</th><th>Lines won</th><th></th></tr></thead>
      <tbody>
        @for (r of rows(); track r.id) {
          <tr data-testid="enquiry-row" [attr.data-id]="r.id">
            <td data-testid="enquiry-row-no">{{ r.enquiryNo }}</td>
            <td>{{ r.customerName }}</td>
            <td><app-status-badge data-testid="enquiry-row-status" [value]="r.status" /></td>
            <td class="nowrap">{{ (r.deadlineAt) | when }}</td>
            <td>{{ r.wonLineCount }} of {{ r.lineCount }}</td>
            <td><button class="link" data-testid="enquiry-row-open" (click)="open(r.id)">Open</button></td>
          </tr>
        }
      </tbody>
    </table></div>
    @if (rows().length === 0 && loaded()) { <p class="muted" data-testid="enquiry-none">No enquiries.</p> }

    @if (session.can('ORDER_CREATE') && session.orgUnitId()) {
      <h2>New enquiry</h2>
      <form ngNativeValidate (ngSubmit)="create()" data-testid="enquiry-create-form">
        <div class="inline">
          <app-entity-picker [kind]="'customer'" placeholder="Customer" ariaLabel="Enquiry customer" testId="enquiry-customer"
                             [(value)]="customer" />
          <input name="reference" placeholder="Customer's reference" aria-label="Reference" data-testid="enquiry-reference"
                 [value]="reference()" (input)="reference.set($any($event.target).value)" />
        </div>
        <div class="inline">
          <app-entity-picker [kind]="'terminal'" placeholder="Origin" ariaLabel="Line origin" testId="enquiry-line-origin"
                             [value]="entry().originTerminalId" (valueChange)="setEntry('originTerminalId', $event)" />
          <app-entity-picker [kind]="'terminal'" placeholder="Destination" ariaLabel="Line destination"
                             testId="enquiry-line-destination" [value]="entry().destinationTerminalId"
                             (valueChange)="setEntry('destinationTerminalId', $event)" />
          <input name="loadType" placeholder="Load or vehicle type" aria-label="Load type" data-testid="enquiry-line-load-type"
                 [value]="entry().loadType" (input)="setEntry('loadType', $any($event.target).value)" />
          <input name="quantity" inputmode="decimal" placeholder="Quantity" aria-label="Quantity" data-testid="enquiry-line-quantity"
                 [value]="entry().quantity" (input)="setEntry('quantity', $any($event.target).value)" />
          <input name="uom" placeholder="Unit" aria-label="Unit" data-testid="enquiry-line-uom"
                 [value]="entry().uom" (input)="setEntry('uom', $any($event.target).value)" />
          <input name="weight" inputmode="decimal" placeholder="Weight (kg)" aria-label="Weight in kilograms"
                 data-testid="enquiry-line-weight" [value]="entry().weightKg"
                 (input)="setEntry('weightKg', $any($event.target).value)" />
          <label>Pickup <input type="date" name="pickup" data-testid="enquiry-line-pickup" [value]="entry().wantedPickupOn"
                               (input)="setEntry('wantedPickupOn', $any($event.target).value)" /></label>
          <button type="button" data-testid="enquiry-line-add" (click)="addLine()">Add line</button>
        </div>
        <ul data-testid="enquiry-draft-lines">
          @for (l of draftLines(); track $index) {
            <li data-testid="enquiry-draft-line">{{ l.quantity }} {{ l.uom }}
              <button type="button" class="link" data-testid="enquiry-draft-remove" (click)="removeDraft($index)">Remove</button></li>
          }
        </ul>
        <p><button type="submit" data-testid="enquiry-create">Create enquiry</button></p>
      </form>
    }

    @if (selected(); as e) {
      <section data-testid="enquiry-detail" [attr.data-id]="e.id">
        <h2>Enquiry <span data-testid="enquiry-no">{{ e.enquiryNo }}</span></h2>
        <dl>
          <div><dt>Status</dt><dd><app-status-badge data-testid="enquiry-status" [value]="e.status" /></dd></div>
          <div><dt>Respond by</dt><dd data-testid="enquiry-deadline">{{ (e.deadlineAt) | when }}</dd></div>
          @if (e.reference) { <div><dt>Reference</dt><dd>{{ e.reference }}</dd></div> }
          @if (e.cancelReason) { <div><dt>Cancelled because</dt><dd data-testid="enquiry-cancel-shown">{{ e.cancelReason }}</dd></div> }
          @if (e.orderId) {
            <div><dt>Order</dt><dd data-testid="enquiry-order" [attr.data-id]="e.orderId">{{ e.orderNo || e.orderId }}</dd></div>
          }
        </dl>
        @if (note()) { <p class="ok" role="status" data-testid="enquiry-note">{{ note() }}</p> }

        <div class="table-wrap"><table data-testid="enquiry-lines">
          <thead><tr><th>Line</th><th>Route</th><th>Quantity</th><th>Suggested</th><th>Quotes</th><th>Status</th><th></th></tr></thead>
          <tbody>
            @for (l of e.lines; track l.id) {
              <tr data-testid="enquiry-line-row" [attr.data-id]="l.id">
                <td>{{ l.lineNo }}</td>
                <td data-testid="enquiry-line-route">{{ l.originCode }} → {{ l.destinationCode }}</td>
                <td data-testid="enquiry-line-qty">{{ l.quantity }} {{ l.uom }}</td>
                <td data-testid="enquiry-line-suggested">
                  @if (l.suggestedLaneCode) { {{ l.suggestedLaneCode }}: {{ l.suggestedAmount || l.suggestedRate }} {{ l.currency }} }
                  @else { — }
                </td>
                <td>
                  <ul>
                    @for (q of l.quotes; track q.id) {
                      <li data-testid="enquiry-quote-row" [attr.data-id]="q.id">r{{ q.revision }} {{ q.amount }} {{ q.status }}</li>
                    }
                  </ul>
                </td>
                <td data-testid="enquiry-line-status"><app-status-badge [value]="l.status" />@if (l.wonQuantity) { ({{ l.wonQuantity }}) }@if (l.lostReason) { — {{ l.lostReason }} }</td>
                <td>
                  @if (canDecide(e, l)) {
                    <div class="inline">
                      <input inputmode="decimal" placeholder="Amount" aria-label="Quote amount" data-testid="enquiry-quote-amount"
                             [value]="typed(l.id).amount" (input)="setIn(l.id, 'amount', $any($event.target).value)"
                             (keydown.enter)="$event.preventDefault(); quote(e, l)" />
                      @if (held(l)) {
                        <button type="button" data-testid="enquiry-quote-revise" (click)="quote(e, l)">Revise quote</button>
                        <button type="button" data-testid="enquiry-quote-revoke" (click)="revoke(e, l)">Revoke quote</button>
                      } @else {
                        <button type="button" data-testid="enquiry-quote-issue" (click)="quote(e, l)">Issue quote</button>
                      }
                    </div>
                    <div class="inline">
                      <input placeholder="Reason" aria-label="Reason" data-testid="enquiry-line-reason"
                             [value]="typed(l.id).reason" (input)="setIn(l.id, 'reason', $any($event.target).value)" />
                      <input inputmode="decimal" placeholder="Quantity won" aria-label="Quantity won" data-testid="enquiry-line-won-quantity"
                             [value]="typed(l.id).wonQuantity" (input)="setIn(l.id, 'wonQuantity', $any($event.target).value)" />
                      <button type="button" data-testid="enquiry-outcome-won" (click)="decide(e, l, 'WON')">Won</button>
                      <button type="button" data-testid="enquiry-outcome-partial" (click)="decide(e, l, 'PARTIAL')">Partly won</button>
                      <button type="button" data-testid="enquiry-outcome-lost" (click)="decide(e, l, 'LOST')">Lost</button>
                    </div>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table></div>

        @if (session.can('ORDER_UPDATE')) {
          @if (e.status === 'OPEN') {
            <p><button type="button" data-testid="enquiry-rediscover" (click)="rediscover(e)">Look for contract rates again</button></p>
            <form ngNativeValidate class="inline" (ngSubmit)="cancel(e)" data-testid="enquiry-cancel-form">
              <input name="cancelReason" placeholder="Why cancel" aria-label="Cancel reason" data-testid="enquiry-cancel-reason"
                     [value]="cancelReason()" (input)="cancelReason.set($any($event.target).value)" />
              <button type="submit" data-testid="enquiry-cancel">Cancel enquiry</button>
            </form>
          }
          @if ((e.status === 'WON' || e.status === 'PARTIAL' || e.status === 'CONVERTED') && session.can('ORDER_CREATE')) {
            <p><button type="button" data-testid="enquiry-convert" (click)="convert(e)">Convert to a draft order</button></p>
          }
        }
      </section>
    }
  `,
})
export class EnquiryDeskPage {
  private readonly gateway = inject(LmsGateway);
  protected readonly session = inject(Session);
  protected readonly statuses = ENQUIRY_STATUSES;

  readonly filter = signal<EnquiryFilter>({ status: '', q: '' });
  readonly rows = signal<EnquiryRow[]>([]);
  readonly loaded = signal(false);
  readonly error = signal('');
  readonly note = signal('');
  readonly selected = signal<EnquiryDesk | null>(null);

  readonly customer = signal('');
  readonly reference = signal('');
  readonly entry = signal<EnquiryLineDraft>({ ...BLANK_LINE });
  readonly draftLines = signal<EnquiryLineDraft[]>([]);
  readonly cancelReason = signal('');
  private readonly inputs = signal<Record<string, LineInputs>>({});

  constructor() {
    void this.load();
  }

  setFilter(key: keyof EnquiryFilter, value: string): void {
    this.filter.update((f) => ({ ...f, [key]: value }));
  }

  setEntry(key: keyof EnquiryLineDraft, value: string): void {
    this.entry.update((e) => ({ ...e, [key]: value }));
  }

  typed(lineId: string): LineInputs {
    return this.inputs()[lineId] ?? { amount: '', reason: '', wonQuantity: '' };
  }

  setIn(lineId: string, key: keyof LineInputs, value: string): void {
    this.inputs.update((all) => ({ ...all, [lineId]: { ...this.typed(lineId), [key]: value } }));
  }

  /** The quote the customer holds on a line, if any. */
  held(line: EnquiryLineView): boolean {
    return line.quotes.some((q) => q.status === 'ISSUED');
  }

  canDecide(e: EnquiryDesk, line: EnquiryLineView): boolean {
    return e.status === 'OPEN' && line.status === 'OPEN' && this.session.can('ORDER_UPDATE');
  }

  async load(): Promise<void> {
    this.error.set('');
    try {
      this.rows.set((await this.gateway.enquiries(this.filter())).rows);
      this.loaded.set(true);
    } catch (e) {
      this.error.set(problemText(e));
    }
  }

  async open(id: string): Promise<void> {
    this.error.set('');
    this.note.set('');
    try {
      this.selected.set(await this.gateway.enquiry(id));
    } catch (e) {
      this.error.set(problemText(e));
    }
  }

  addLine(): void {
    const line = this.entry();
    if (!line.originTerminalId || !line.destinationTerminalId || !line.quantity.trim()) {
      this.error.set('A line needs an origin, a destination and a quantity.');
      return;
    }
    this.error.set('');
    this.draftLines.update((all) => [...all, line]);
    this.entry.set({ ...BLANK_LINE, originTerminalId: line.originTerminalId });
  }

  removeDraft(index: number): void {
    this.draftLines.update((all) => all.filter((_, i) => i !== index));
  }

  async create(): Promise<void> {
    const entered = this.entry();
    if (entered.destinationTerminalId && entered.quantity.trim()) {
      this.addLine();
    }
    const orgUnitId = this.session.orgUnitId();
    if (!orgUnitId || this.draftLines().length === 0) {
      this.error.set('An enquiry needs at least one line.');
      return;
    }
    try {
      const id = await this.gateway.createEnquiry(orgUnitId, {
        customerPartnerId: this.customer(), reference: this.reference(), notes: '', lines: this.draftLines(),
      });
      this.draftLines.set([]);
      this.entry.set({ ...BLANK_LINE });
      this.reference.set('');
      await this.load();
      await this.open(id);
    } catch (e) {
      this.error.set(problemText(e));
    }
  }

  /** Runs a change, then reads the enquiry and the list again: the version moved, and so may the status. */
  private async change(e: EnquiryDesk, done: string, action: () => Promise<unknown>): Promise<void> {
    this.error.set('');
    this.note.set('');
    try {
      await action();
      this.selected.set(await this.gateway.enquiry(e.id));
      this.note.set(done);
      await this.load();
    } catch (err) {
      this.error.set(problemText(err));
    }
  }

  quote(e: EnquiryDesk, line: EnquiryLineView): Promise<void> {
    const draft = { amount: this.typed(line.id).amount, note: '' };
    return this.change(e, 'Quote saved.', () => this.held(line)
      ? this.gateway.reviseEnquiryQuote(e.id, e.version, line.id, draft)
      : this.gateway.issueEnquiryQuote(e.id, e.version, line.id, draft));
  }

  revoke(e: EnquiryDesk, line: EnquiryLineView): Promise<void> {
    return this.change(e, 'Quote withdrawn.', () =>
      this.gateway.revokeEnquiryQuote(e.id, e.version, line.id, this.typed(line.id).reason));
  }

  decide(e: EnquiryDesk, line: EnquiryLineView, outcome: EnquiryOutcome): Promise<void> {
    const typed = this.typed(line.id);
    return this.change(e, 'Outcome recorded.', () => this.gateway.recordEnquiryOutcome(e.id, e.version, line.id, {
      outcome, wonQuantity: typed.wonQuantity, reason: typed.reason,
    }));
  }

  rediscover(e: EnquiryDesk): Promise<void> {
    return this.change(e, 'Contract rates looked up again.', () => this.gateway.discoverEnquiryRates(e.id, e.version));
  }

  cancel(e: EnquiryDesk): Promise<void> {
    return this.change(e, 'Enquiry cancelled.', () => this.gateway.cancelEnquiry(e.id, e.version, this.cancelReason()));
  }

  convert(e: EnquiryDesk): Promise<void> {
    return this.change(e, 'Converted to a draft order.', () => this.gateway.convertEnquiry(e.id));
  }
}
