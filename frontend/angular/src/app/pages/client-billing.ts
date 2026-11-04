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

import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiProblem, ClientAgeingView, ClientInvoiceDetail, LmsGateway, Row } from '../gateway/lms-gateway';
import { Session } from '../session';
import { EntityPicker } from './entity-picker';
import { CREDIT_LIMIT_EXCEEDED, CreditOverride, CreditPositionPanel } from './credit';

/** Today as a `YYYY-MM-DD` day, in UTC like the API. */
function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/** The month before this one, as `YYYY-MM`: the latest a billing run can close. */
function lastMonth(): string {
  const d = new Date();
  d.setUTCDate(1);
  d.setUTCMonth(d.getUTCMonth() - 1);
  return d.toISOString().slice(0, 7);
}

/** A plain table of rows, one column per field. */
@Component({
  selector: 'app-billing-table',
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
export class BillingTable {
  readonly rows = input.required<Row[]>();
  readonly columns = input.required<Array<[string, string]>>();
  readonly rowTestId = input.required<string>();
}

const CONTRACT_COLUMNS: Array<[string, string]> = [
  ['code', 'Contract'], ['effectiveFrom', 'From'], ['effectiveTo', 'To'], ['currency', 'Currency'],
  ['transportRatePerKg', 'Transport / kg'], ['handlingInRatePerPallet', 'Handling in / pallet'],
  ['handlingOutRatePerPallet', 'Handling out / pallet'], ['storageRatePerPalletDay', 'Storage / pallet-day'],
  ['billing', 'Transport billed'],
];
const CHARGE_COLUMNS: Array<[string, string]> = [
  ['occurredOn', 'Day'], ['serviceType', 'Service'], ['serviceCode', 'Code'], ['quantity', 'Quantity'],
  ['unit', 'Unit'], ['reference', 'Reference'], ['invoiced', 'Invoiced'], ['late', 'Late'],
  ['billable', 'Billable'],
];
const LINE_COLUMNS: Array<[string, string]> = [
  ['lineNo', '#'], ['serviceType', 'Service'], ['narrative', 'Narrative'], ['quantity', 'Quantity'],
  ['unit', 'Unit'], ['rate', 'Rate'], ['amount', 'Amount'], ['taxAmount', 'Tax'], ['lineTotal', 'Total'],
  ['creditedAmount', 'Credited'],
];
const STATEMENT_COLUMNS: Array<[string, string]> = [
  ['entryType', 'Entry'], ['documentNo', 'Document'], ['issuedAt', 'When'], ['invoiceNo', 'Against'],
  ['debit', 'Debit'], ['credit', 'Credit'], ['balance', 'Balance'],
];

/**
 * One client's desk: contracts, the month's charges (warehouse hand-offs among them),
 * billing runs, invoices with credit notes, payments, the statement and the ageing.
 */
@Component({
  selector: 'app-client-desk',
  imports: [FormsModule, BillingTable, CreditPositionPanel, CreditOverride],
  template: `
    <section data-testid="client-desk">
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      @if (session.can('PARTNER_READ')) {
        <app-credit-position [partnerId]="clientId()" [refreshKey]="invoices()" />
      }

      <h2>Contracts</h2>
      @if (loaded() && contracts().length === 0) {
        <p class="muted" data-testid="no-contract">This client holds no contract, so nothing is billed to it.</p>
      } @else {
        <app-billing-table [rows]="contracts()" [columns]="contractColumns" rowTestId="contract-row" />
      }

      <h2>Charges</h2>
      <label>
        Month
        <input type="month" data-testid="charges-month" [value]="month()" (input)="pickMonth($any($event.target).value)" />
      </label>
      @if (loaded() && charges().length === 0) {
        <p class="muted" data-testid="no-charges">Nothing was charged in this month.</p>
      } @else {
        <div class="table-scroll">
          <app-billing-table [rows]="charges()" [columns]="chargeColumns" rowTestId="charge-row" />
        </div>
      }

      @if (session.can('CLIENT_BILLING_RUN')) {
        <h2>Billing run</h2>
        <form class="inline" (ngSubmit)="run()" data-testid="billing-run">
          <input name="billingMonth" type="month" aria-label="Month to bill" data-testid="billing-month"
                 [value]="billingMonth()" (input)="billingMonth.set($any($event.target).value)" required />
          <button type="submit" data-testid="run-billing">Run billing</button>
        </form>
        @if (billed()) { <p data-testid="billing-result">Billed {{ billed() }}.</p> }
        @if (runError()) {
          <p class="error" role="alert" data-testid="billing-error">
            @if (runCode()) { <code data-testid="billing-refusal">{{ runCode() }}</code> }
            {{ runError() }}
          </p>
          @if (runCode() === creditRefused) {
            <app-credit-override [partnerId]="clientId()" [subjectType]="'CLIENT_INVOICE'"
                                 [period]="billingMonth() + '-01'" [errors]="runErrors()" (granted)="run()" />
          }
        }

        <h2>Billable without proof</h2>
        <form class="inline" (ngSubmit)="markBillable()" data-testid="mark-billable">
          <input name="markConsignment" aria-label="Consignment id" placeholder="Consignment id"
                 [value]="markConsignment()" (input)="markConsignment.set($any($event.target).value)" required />
          <input name="markReason" aria-label="Reason it is billable" placeholder="Reason"
                 [value]="markReason()" (input)="markReason.set($any($event.target).value)" required />
          <button type="submit">Mark billable</button>
        </form>
        @if (marked()) { <p data-testid="marked-billable">Marked billable.</p> }
        @if (markError()) { <p class="error" role="alert">{{ markError() }}</p> }
      }

      <h2>Invoices</h2>
      @if (loaded() && invoices().length === 0) {
        <p class="muted" data-testid="no-invoices">No invoice has been issued yet.</p>
      } @else {
        <table>
          <thead>
            <tr><th>Invoice</th><th>Period</th><th>Total</th><th>Credited</th><th>Currency</th></tr>
          </thead>
          <tbody>
            @for (i of invoices(); track i['id']) {
              <tr data-testid="invoice-row" tabindex="0" [class.selected]="i['id'] === invoiceId()"
                  (click)="openInvoice(i['id'])" (keydown.enter)="openInvoice(i['id'])">
                <td>{{ i['invoiceNo'] }}</td><td>{{ i['periodStart'] }} – {{ i['periodEnd'] }}</td>
                <td>{{ i['totalAmount'] }}</td><td>{{ i['creditedAmount'] }}</td><td>{{ i['currency'] }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
      @if (invoiceError()) { <p class="error" role="alert">{{ invoiceError() }}</p> }
      @if (invoice(); as d) {
        <section data-testid="invoice-panel" aria-label="Invoice">
          <header>
            <h3>{{ d.invoice['invoiceNo'] }}</h3>
            <button class="link" (click)="closeInvoice()" aria-label="Close invoice">Close</button>
          </header>
          <dl>
            <div><dt>Period</dt><dd>{{ d.invoice['periodStart'] }} – {{ d.invoice['periodEnd'] }}</dd></div>
            <div><dt>Taxable</dt><dd>{{ d.invoice['taxableAmount'] }}</dd></div>
            <div><dt>Tax</dt><dd>{{ d.invoice['taxAmount'] }}</dd></div>
            <div><dt>Total</dt><dd data-testid="invoice-total">{{ d.invoice['totalAmount'] }} {{ d.invoice['currency'] }}</dd></div>
            <div><dt>Credited</dt><dd data-testid="invoice-credited">{{ d.invoice['creditedAmount'] }}</dd></div>
          </dl>
          <app-billing-table [rows]="d.lines" [columns]="lineColumns" rowTestId="invoice-line" />
          <p><button class="link" data-testid="print-invoice" (click)="slip.set(true)">Print invoice</button></p>
          @if (slip()) {
            <section class="slip manifest" data-testid="invoice-slip" aria-label="Invoice to print">
              <h3>Invoice {{ d.invoice['invoiceNo'] }}</h3>
              <p>{{ d.invoice['clientName'] }} · {{ d.invoice['periodStart'] }} – {{ d.invoice['periodEnd'] }} · issued {{ d.invoice['issuedAt'] }}</p>
              <app-billing-table [rows]="d.lines" [columns]="lineColumns" rowTestId="slip-line" />
              <p>Taxable {{ d.invoice['taxableAmount'] }} · tax {{ d.invoice['taxAmount'] }} · total {{ d.invoice['totalAmount'] }} {{ d.invoice['currency'] }}</p>
              <p class="actions no-print">
                <button data-testid="print-now" (click)="print()">Print</button>
                <button class="link" (click)="slip.set(false)">Close</button>
              </p>
            </section>
          }
          @if (session.can('CLIENT_CREDIT_NOTE_ISSUE')) {
            <form class="inline" (ngSubmit)="credit()" data-testid="credit-note">
              <input name="reason" placeholder="Reason" aria-label="Reason for the credit"
                     [value]="creditReason()" (input)="creditReason.set($any($event.target).value)" required />
              <input name="creditAmount" placeholder="Amount before tax" aria-label="Amount to credit" inputmode="decimal"
                     [value]="creditAmount()" (input)="creditAmount.set($any($event.target).value)" required />
              <button type="submit">Issue credit note</button>
            </form>
          }
          @if (creditIssued()) { <p data-testid="credit-issued">Credit note {{ creditIssued() }} issued.</p> }
          @if (creditError()) { <p class="error" role="alert">{{ creditError() }}</p> }
        </section>
      }

      <h2>Statement</h2>
      @if (statement().length === 0) {
        <p class="muted">Nothing on the statement yet.</p>
      } @else {
        <app-billing-table [rows]="statement()" [columns]="statementColumns" rowTestId="statement-row" />
      }
      <p>Balance <strong data-testid="statement-balance">{{ balance() }}</strong></p>
      @if (session.can('CLIENT_BILLING_RUN')) {
        <h3>Record a payment</h3>
        <form class="inline" (ngSubmit)="pay()" data-testid="record-payment">
          <input name="amount" placeholder="Amount received" aria-label="Amount received" inputmode="decimal"
                 [value]="payAmount()" (input)="payAmount.set($any($event.target).value)" required />
          <input name="receivedOn" type="date" aria-label="Received on"
                 [value]="payOn()" (input)="payOn.set($any($event.target).value)" required />
          <input name="reference" placeholder="Remittance reference" aria-label="Remittance reference"
                 [value]="payReference()" (input)="payReference.set($any($event.target).value)" required />
          <button type="submit">Record payment</button>
        </form>
        @if (paid()) { <p data-testid="payment-recorded">Payment {{ paid() }} recorded.</p> }
        @if (payError()) { <p class="error" role="alert">{{ payError() }}</p> }
      }

      <h2>Ageing</h2>
      @if (ageing(); as a) {
        <dl>
          <div><dt>Outstanding</dt><dd data-testid="ageing-outstanding">{{ a.outstanding }} {{ a.currency }}</dd></div>
          <div><dt>Unapplied credit</dt><dd>{{ a.unappliedCredit }}</dd></div>
          @for (b of buckets; track b) {
            <div><dt>{{ b }} days</dt><dd [attr.data-testid]="'ageing-' + b">{{ a.buckets[b] }}</dd></div>
          }
        </dl>
      }
    </section>
  `,
})
export class ClientDesk implements OnInit {
  private readonly gateway = inject(LmsGateway);
  readonly session = inject(Session);

  readonly clientId = input.required<string>();

  readonly contractColumns = CONTRACT_COLUMNS;
  readonly chargeColumns = CHARGE_COLUMNS;
  readonly lineColumns = LINE_COLUMNS;
  readonly statementColumns = STATEMENT_COLUMNS;
  readonly buckets = ['0-30', '31-60', '61-90', '90+'];

  readonly loaded = signal(false);
  readonly error = signal('');
  readonly contracts = signal<Row[]>([]);
  readonly month = signal(today().slice(0, 7));
  readonly charges = signal<Row[]>([]);
  readonly invoices = signal<Row[]>([]);
  readonly statement = signal<Row[]>([]);
  readonly ageing = signal<ClientAgeingView | null>(null);
  readonly balance = computed(() => {
    const entries = this.statement();
    return entries.length ? entries[entries.length - 1]['balance'] : '0';
  });

  readonly billingMonth = signal(lastMonth());
  readonly markConsignment = signal('');
  readonly markReason = signal('');
  readonly marked = signal(false);
  readonly markError = signal('');
  readonly billed = signal('');
  readonly runError = signal('');
  readonly runCode = signal('');
  readonly runErrors = signal<Record<string, string>>({});
  readonly creditRefused = CREDIT_LIMIT_EXCEEDED;

  readonly invoiceId = signal<string | null>(null);
  readonly invoice = signal<ClientInvoiceDetail | null>(null);
  readonly invoiceError = signal('');
  readonly slip = signal(false);
  readonly creditReason = signal('');
  readonly creditAmount = signal('');
  readonly creditIssued = signal('');
  readonly creditError = signal('');

  readonly payAmount = signal('');
  readonly payOn = signal(today());
  readonly payReference = signal('');
  readonly paid = signal('');
  readonly payError = signal('');

  ngOnInit(): void {
    void this.refresh();
  }

  /** Reads everything again; an open invoice is read again too. */
  async refresh(): Promise<void> {
    const id = this.clientId();
    this.error.set('');
    try {
      const [contracts, charges, invoices, statement, ageing] = await Promise.all([
        this.gateway.clientContracts(id), this.gateway.clientCharges(id, `${this.month()}-01`),
        this.gateway.clientInvoices(id), this.gateway.clientStatement(id), this.gateway.clientAgeing(id),
      ]);
      this.contracts.set(contracts);
      this.charges.set(charges);
      this.invoices.set(invoices);
      this.statement.set(statement);
      this.ageing.set(ageing);
      this.loaded.set(true);
    } catch (e) {
      this.error.set((e as Error).message);
    }
    const open = this.invoiceId();
    if (open) {
      await this.readInvoice(open);
    }
  }

  /** Marks a consignment billable without its proof of delivery; the reason is audited. */
  async markBillable(): Promise<void> {
    this.markError.set('');
    this.marked.set(false);
    try {
      await this.gateway.markConsignmentBillable(this.markConsignment().trim(), this.markReason());
      this.marked.set(true);
      this.markReason.set('');
      await this.refresh();
    } catch (e) {
      this.markError.set((e as Error).message);
    }
  }

  async pickMonth(value: string): Promise<void> {
    if (!value) {
      return;
    }
    this.month.set(value);
    try {
      this.charges.set(await this.gateway.clientCharges(this.clientId(), `${value}-01`));
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  async run(): Promise<void> {
    this.runError.set('');
    this.runCode.set('');
    this.runErrors.set({});
    this.billed.set('');
    try {
      const id = await this.gateway.runClientBilling(this.clientId(), `${this.billingMonth()}-01`);
      this.billed.set(this.billingMonth());
      this.invoiceId.set(id);
      await this.refresh();
    } catch (e) {
      this.runError.set((e as Error).message);
      this.runCode.set(e instanceof ApiProblem ? e.code : '');
      this.runErrors.set(e instanceof ApiProblem ? e.errors : {});
    }
  }

  async openInvoice(id: string): Promise<void> {
    if (id !== this.invoiceId()) {
      this.slip.set(false);
      this.creditIssued.set('');
      this.creditError.set('');
    }
    this.invoiceId.set(id);
    await this.readInvoice(id);
  }

  closeInvoice(): void {
    this.invoiceId.set(null);
    this.invoice.set(null);
  }

  private async readInvoice(id: string): Promise<void> {
    this.invoiceError.set('');
    try {
      this.invoice.set(await this.gateway.clientInvoice(id));
    } catch (e) {
      this.invoice.set(null);
      this.invoiceError.set((e as Error).message);
    }
  }

  async credit(): Promise<void> {
    const id = this.invoiceId();
    if (!id) {
      return;
    }
    this.creditError.set('');
    try {
      this.creditIssued.set(await this.gateway.issueClientCreditNote(id, {
        reason: this.creditReason(), amount: Number(this.creditAmount()),
      }));
      this.creditReason.set('');
      this.creditAmount.set('');
      await this.refresh();
    } catch (e) {
      this.creditError.set((e as Error).message);
    }
  }

  async pay(): Promise<void> {
    this.payError.set('');
    this.paid.set('');
    const reference = this.payReference();
    try {
      await this.gateway.recordClientPayment(this.clientId(), {
        amount: Number(this.payAmount()), receivedOn: this.payOn(), reference,
      });
      this.paid.set(reference);
      this.payAmount.set('');
      this.payReference.set('');
      await this.refresh();
    } catch (e) {
      this.payError.set((e as Error).message);
    }
  }

  print(): void {
    window.print();
  }
}

/** Opens a 3PL client's billing desk by its partner id. */
@Component({
  selector: 'app-client-billing',
  imports: [FormsModule, ClientDesk, EntityPicker],
  template: `
    <h1>Client billing</h1>
    <p class="muted">
      Open a client to see what it has been charged, bill a month that has ended, and follow
      what it owes.
    </p>
    <!-- A signal, not ngModel: ngModel connects after the screen is shown, and would wipe an id typed before it. -->
    <form class="inline" (ngSubmit)="open()" data-testid="open-client">
      <app-entity-picker [kind]="'customer'" placeholder="Client" ariaLabel="Client id" testId="client-id"
                         [required]="true" [(value)]="typed" />
      <button type="submit">Open</button>
    </form>
    @for (id of opened(); track id) {
      <app-client-desk [clientId]="id" />
    }
  `,
})
export class ClientBilling {
  readonly typed = signal('');
  readonly clientId = signal('');
  /** The open client as a one-item list, so a new client gets a fresh desk. */
  readonly opened = computed(() => (this.clientId() ? [this.clientId()] : []));

  open(): void {
    this.clientId.set(this.typed().trim().toLowerCase());
  }
}
