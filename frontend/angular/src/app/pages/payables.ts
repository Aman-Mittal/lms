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

import { Component, OnInit, inject, signal } from '@angular/core';
import {
  ApiProblem, InvoiceBillView, LmsGateway, PAYMENT_MODES, VendorInvoiceView, VendorPaymentView,
} from '../gateway/lms-gateway';

type Tab = 'invoices' | 'payments' | 'overdue';

function valueOf(event: Event): string {
  return (event.target as HTMLInputElement).value;
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * The vendor payables desk: a vendor's invoice captured against freight bills approved for
 * payment (matched with what they were approved at, so a wrong amount is refused), the payments
 * made against it with what is still outstanding, and what is overdue.
 */
@Component({
  selector: 'app-payables',
  template: `
    <h1>Vendor payables</h1>
    <p class="muted">Vendor invoices, payments made and what is overdue.</p>
    <p class="actions" role="tablist" aria-label="Payables views">
      <button type="button" role="tab" [class.link]="tab() !== 'invoices'" data-testid="pay-tab-invoices"
              [attr.aria-selected]="tab() === 'invoices'" (click)="show('invoices')">Invoices</button>
      <button type="button" role="tab" [class.link]="tab() !== 'payments'" data-testid="pay-tab-payments"
              [attr.aria-selected]="tab() === 'payments'" (click)="show('payments')">Payments</button>
      <button type="button" role="tab" [class.link]="tab() !== 'overdue'" data-testid="pay-tab-overdue"
              [attr.aria-selected]="tab() === 'overdue'" (click)="show('overdue')">Overdue</button>
    </p>

    @if (tab() === 'invoices') {
      @if (refusal(); as r) {
        <p class="error" role="alert" data-testid="inv-error">
          @if (r.code) { <code>{{ r.code }}</code> }
          {{ r.message }}
        </p>
      }
      @if (captured()) { <p class="ok" role="status" data-testid="inv-result">{{ captured() }}</p> }
      <form data-testid="inv-form" (submit)="capture($event)">
        <h2>Capture an invoice</h2>
        <input placeholder="Freight bill numbers, comma separated" aria-label="Freight bills" data-testid="inv-bills"
               required [value]="bills()" (input)="bills.set(valueOf($event))" />
        <input placeholder="Vendor's invoice number" aria-label="Vendor invoice number" data-testid="inv-vendor-no"
               required [value]="vendorNo()" (input)="vendorNo.set(valueOf($event))" />
        <input type="date" aria-label="Invoice date" data-testid="inv-date" required
               [value]="date()" (input)="date.set(valueOf($event))" />
        <input type="number" min="0" step="any" placeholder="Amount before tax" aria-label="Amount" data-testid="inv-amount"
               required [value]="amount()" (input)="amount.set(valueOf($event))" />
        <input type="number" min="0" step="any" placeholder="Tax" aria-label="Tax" data-testid="inv-tax"
               [value]="tax()" (input)="tax.set(valueOf($event))" />
        <input placeholder="Document reference" aria-label="Document reference" data-testid="inv-document"
               [value]="document()" (input)="document.set(valueOf($event))" />
        <button type="submit" data-testid="inv-submit">Capture invoice</button>
      </form>
      @if (invoices().length === 0) {
        <p class="muted" data-testid="inv-empty">No vendor invoices.</p>
      } @else {
        <table data-testid="inv-list">
          <thead><tr><th>Invoice</th><th>Vendor</th><th>Vendor's number</th><th>Due</th><th>Total</th><th>Outstanding</th><th>Status</th><th></th></tr></thead>
          <tbody>
            @for (i of invoices(); track i.id) {
              <tr data-testid="inv-row" [attr.data-id]="i.id" (click)="open(i.id)">
                <td><button type="button" class="link" [attr.aria-label]="'Open ' + i.invoiceNo">{{ i.invoiceNo }}</button></td>
                <td>{{ i.vendorName }}</td><td>{{ i.vendorInvoiceNo }}</td><td>{{ i.dueDate }}</td>
                <td>{{ i.totalAmount }} {{ i.currency }}</td>
                <td data-testid="inv-outstanding">{{ i.outstanding }}</td>
                <td><span class="badge" data-testid="inv-status">{{ i.status }}</span></td>
                <td>@if (i.overdue) { <span class="badge" data-testid="inv-overdue">Overdue {{ i.daysOverdue }} d</span> }</td>
              </tr>
            }
          </tbody>
        </table>
        @if (next()) { <button type="button" class="link" (click)="more()">More</button> }
      }
      @if (detail(); as d) {
        <section data-testid="inv-detail" [attr.aria-label]="'Invoice ' + d.invoiceNo">
          <h2>{{ d.invoiceNo }}</h2>
          <dl class="facts">
            <div><dt>Vendor</dt><dd>{{ d.vendorName }}</dd></div>
            <div><dt>Invoiced</dt><dd>{{ d.amount }} + tax {{ d.taxAmount }}</dd></div>
            <div><dt>Approved</dt><dd>{{ d.approvedAmount }}</dd></div>
            <div><dt>Advances deducted</dt><dd>{{ d.advancesDeducted }}</dd></div>
            <div><dt>Net payable</dt><dd>{{ d.netPayable }}</dd></div>
            <div><dt>Paid</dt><dd>{{ d.paidAmount }}</dd></div>
            <div><dt>Outstanding</dt><dd data-testid="detail-outstanding">{{ d.outstanding }}</dd></div>
            <div><dt>Status</dt><dd data-testid="detail-status">{{ d.status }}</dd></div>
          </dl>
          <h3>Covered bills</h3>
          <ul data-testid="detail-bills">
            @for (b of detailBills(); track b.billId) { <li>{{ b.billNo }} {{ b.tripNo }} approved {{ b.approvedAmount }}</li> }
          </ul>
          <h3>Payments</h3>
          <ul data-testid="detail-payments">
            @for (p of detailPayments(); track p.id) { <li>{{ p.paidOn }} {{ p.amount }} {{ p.mode }} {{ p.reference }}</li> }
          </ul>
          @if (payRefusal(); as r) {
            <p class="error" role="alert" data-testid="pay-error">
              @if (r.code) { <code>{{ r.code }}</code> }
              {{ r.message }}
            </p>
          }
          @if (d.status !== 'PAID') {
            <form data-testid="pay-form" (submit)="pay($event, d.id)">
              <h3>Record a payment</h3>
              <input type="number" min="0" step="any" placeholder="Amount" aria-label="Payment amount" data-testid="pay-amount"
                     required [value]="payAmount()" (input)="payAmount.set(valueOf($event))" />
              <input type="date" aria-label="Paid on" data-testid="pay-date" required
                     [value]="payDate()" (input)="payDate.set(valueOf($event))" />
              <select aria-label="Mode" data-testid="pay-mode" (change)="payMode.set(valueOf($event))">
                @for (m of modes; track m) { <option [value]="m" [selected]="m === payMode()">{{ m }}</option> }
              </select>
              <input placeholder="Reference" aria-label="Payment reference" data-testid="pay-reference" required
                     [value]="payReference()" (input)="payReference.set(valueOf($event))" />
              <button type="submit" data-testid="pay-submit">Record payment</button>
            </form>
          }
        </section>
      }
    }

    @if (tab() === 'payments') {
      @if (refusal(); as r) { <p class="error" role="alert">{{ r.message }}</p> }
      @if (payments().length === 0) {
        <p class="muted" data-testid="payments-empty">No payments.</p>
      } @else {
        <table data-testid="payment-list">
          <thead><tr><th>Paid on</th><th>Invoice</th><th>Vendor</th><th>Amount</th><th>Mode</th><th>Reference</th></tr></thead>
          <tbody>
            @for (p of payments(); track p.id) {
              <tr data-testid="payment-row">
                <td>{{ p.paidOn }}</td><td>{{ p.invoiceNo }}</td><td>{{ p.vendorName }}</td><td>{{ p.amount }}</td>
                <td>{{ p.mode }}</td><td>{{ p.reference }}</td>
              </tr>
            }
          </tbody>
        </table>
        @if (next()) { <button type="button" class="link" (click)="more()">More</button> }
      }
    }

    @if (tab() === 'overdue') {
      @if (refusal(); as r) { <p class="error" role="alert">{{ r.message }}</p> }
      @if (invoices().length === 0) {
        <p class="muted" data-testid="overdue-empty">Nothing is overdue.</p>
      } @else {
        <table data-testid="overdue-list">
          <thead><tr><th>Invoice</th><th>Vendor</th><th>Due</th><th>Days overdue</th><th>Outstanding</th></tr></thead>
          <tbody>
            @for (i of invoices(); track i.id) {
              <tr data-testid="overdue-row">
                <td>{{ i.invoiceNo }}</td><td>{{ i.vendorName }}</td><td>{{ i.dueDate }}</td><td>{{ i.daysOverdue }}</td>
                <td>{{ i.outstanding }}</td>
              </tr>
            }
          </tbody>
        </table>
        @if (next()) { <button type="button" class="link" (click)="more()">More</button> }
      }
    }
  `,
})
export class Payables implements OnInit {
  private readonly gateway = inject(LmsGateway);
  protected readonly valueOf = valueOf;
  readonly modes = PAYMENT_MODES;

  readonly tab = signal<Tab>('invoices');
  readonly refusal = signal<{ code: string; message: string } | null>(null);
  readonly payRefusal = signal<{ code: string; message: string } | null>(null);
  readonly captured = signal('');
  readonly invoices = signal<VendorInvoiceView[]>([]);
  readonly payments = signal<VendorPaymentView[]>([]);
  readonly next = signal<string | null>(null);

  readonly bills = signal('');
  readonly vendorNo = signal('');
  readonly date = signal(today());
  readonly amount = signal('');
  readonly tax = signal('0');
  readonly document = signal('');

  readonly detail = signal<VendorInvoiceView | null>(null);
  readonly detailBills = signal<InvoiceBillView[]>([]);
  readonly detailPayments = signal<VendorPaymentView[]>([]);
  readonly payAmount = signal('');
  readonly payDate = signal(today());
  readonly payMode = signal<string>('BANK_TRANSFER');
  readonly payReference = signal('');

  async ngOnInit(): Promise<void> {
    await this.show('invoices');
  }

  async show(tab: Tab): Promise<void> {
    this.tab.set(tab);
    this.refusal.set(null);
    await this.attempt(() => this.load(false));
  }

  async more(): Promise<void> {
    await this.attempt(() => this.load(true));
  }

  async capture(event: Event): Promise<void> {
    event.preventDefault();
    await this.attempt(async () => {
      await this.gateway.captureVendorInvoice({
        billNumbers: this.bills().split(',').map((s) => s.trim()).filter((s) => s !== ''),
        vendorInvoiceNo: this.vendorNo(), invoiceDate: this.date(), amount: Number(this.amount()),
        taxAmount: Number(this.tax() || '0'), documentRef: this.document(),
      });
      this.captured.set(`Captured invoice ${this.vendorNo()}.`);
      this.bills.set('');
      this.vendorNo.set('');
      this.amount.set('');
      await this.load(false);
    });
  }

  async open(id: string): Promise<void> {
    this.payRefusal.set(null);
    await this.attempt(async () => {
      await this.loadDetail(id);
    });
  }

  async pay(event: Event, id: string): Promise<void> {
    event.preventDefault();
    this.payRefusal.set(null);
    try {
      await this.gateway.recordVendorPayment(id, {
        amount: Number(this.payAmount()), paidOn: this.payDate(), mode: this.payMode(), reference: this.payReference(),
      });
      this.payAmount.set('');
      this.payReference.set('');
      await this.loadDetail(id);
      await this.load(false);
    } catch (e) {
      this.payRefusal.set({ code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message });
    }
  }

  private async loadDetail(id: string): Promise<void> {
    this.detail.set(await this.gateway.vendorInvoice(id));
    this.detailBills.set(await this.gateway.vendorInvoiceBills(id));
    this.detailPayments.set((await this.gateway.vendorPayments(id)).items);
  }

  private async load(append: boolean): Promise<void> {
    const cursor = append ? this.next() ?? undefined : undefined;
    if (this.tab() === 'payments') {
      const page = await this.gateway.vendorPayments(undefined, cursor);
      this.payments.set(append ? [...this.payments(), ...page.items] : page.items);
      this.next.set(page.nextCursor);
      return;
    }
    const page = await this.gateway.vendorInvoices({ status: '', overdue: this.tab() === 'overdue' }, cursor);
    this.invoices.set(append ? [...this.invoices(), ...page.items] : page.items);
    this.next.set(page.nextCursor);
    const open = this.detail();
    if (open && this.tab() === 'invoices') {
      await this.loadDetail(open.id);
    }
  }

  private async attempt(action: () => Promise<void>): Promise<void> {
    this.refusal.set(null);
    this.captured.set('');
    try {
      await action();
    } catch (e) {
      this.refusal.set({ code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message });
    }
  }
}
