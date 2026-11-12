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

import { Fragment, useEffect, useState, type FormEvent } from 'react';
import {
  PAYMENT_MODES, type PaymentModeChoice, type VendorInvoiceBillView, type VendorInvoiceView, type VendorPaymentView,
} from '../gateway/lmsGateway';
import { useSession } from '../session';
import { money, problemText, today } from './moneyProblem';

type Tab = 'invoices' | 'payments' | 'overdue';

/**
 * The vendor payables desk: invoices captured against approved freight bills, the payments made
 * against them, and what is overdue. Capturing and paying need PAYABLES_MANAGE.
 */
export function VendorPayables() {
  const { gateway, can } = useSession();
  const [tab, setTab] = useState<Tab>('invoices');
  const [invoices, setInvoices] = useState<VendorInvoiceView[]>([]);
  const [payments, setPayments] = useState<VendorPaymentView[]>([]);
  const [overdue, setOverdue] = useState<VendorInvoiceView[]>([]);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);

  // capture form
  const [bills, setBills] = useState('');
  const [vendorNo, setVendorNo] = useState('');
  const [date, setDate] = useState(today());
  const [amount, setAmount] = useState('');
  const [tax, setTax] = useState('');
  const [document, setDocument] = useState('');
  const [captured, setCaptured] = useState('');
  const [captureError, setCaptureError] = useState('');

  // the open invoice
  const [openId, setOpenId] = useState('');
  const [coveredBills, setCoveredBills] = useState<VendorInvoiceBillView[]>([]);
  const [invoicePayments, setInvoicePayments] = useState<VendorPaymentView[]>([]);
  const [payAmount, setPayAmount] = useState('');
  const [payDate, setPayDate] = useState(today());
  const [payMode, setPayMode] = useState<PaymentModeChoice>('BANK_TRANSFER');
  const [payReference, setPayReference] = useState('');
  const [payError, setPayError] = useState('');

  useEffect(() => {
    let ignore = false;
    const load = tab === 'invoices'
      ? gateway.listVendorInvoices({ status: '', overdue: false }).then((r) => { if (!ignore) { setInvoices(r); } })
      : tab === 'payments'
        ? gateway.listVendorPayments().then((r) => { if (!ignore) { setPayments(r); } })
        : gateway.listVendorInvoices({ status: '', overdue: true }).then((r) => { if (!ignore) { setOverdue(r); } });
    load.catch((e: unknown) => { if (!ignore) { setError(problemText(e)); } });
    return () => { ignore = true; };
  }, [gateway, tab, reload]);

  useEffect(() => {
    if (!openId) {
      return undefined;
    }
    let ignore = false;
    Promise.all([gateway.vendorInvoiceBills(openId), gateway.vendorInvoicePayments(openId)])
      .then(([b, p]) => { if (!ignore) { setCoveredBills(b); setInvoicePayments(p); } })
      .catch((e: unknown) => { if (!ignore) { setError(problemText(e)); } });
    return () => { ignore = true; };
  }, [gateway, openId, reload]);

  function show(next: Tab) {
    setError('');
    setTab(next);
  }

  function capture(event: FormEvent) {
    event.preventDefault();
    setCaptureError('');
    setCaptured('');
    gateway.captureVendorInvoice({
      billNos: bills.split(',').map((s) => s.trim()).filter((s) => s !== ''), vendorInvoiceNo: vendorNo,
      invoiceDate: date, amount, taxAmount: tax, documentRef: document,
    }).then(() => {
      setCaptured(`Captured invoice ${vendorNo.trim()}.`);
      setBills('');
      setVendorNo('');
      setAmount('');
      setTax('');
      setDocument('');
      setReload((n) => n + 1);
    }).catch((e: unknown) => setCaptureError(problemText(e)));
  }

  function toggle(id: string) {
    setPayError('');
    setPayAmount('');
    setPayReference('');
    setOpenId(openId === id ? '' : id);
  }

  function pay(event: FormEvent) {
    event.preventDefault();
    setPayError('');
    gateway.recordVendorPayment(openId, { amount: payAmount, paidOn: payDate, mode: payMode, reference: payReference })
      .then(() => {
        setPayAmount('');
        setPayReference('');
        setReload((n) => n + 1);
      }).catch((e: unknown) => setPayError(problemText(e)));
  }

  function invoiceRows(list: VendorInvoiceView[], rowTestId: string) {
    return list.map((i) => (
      <Fragment key={i.id}>
        <tr data-testid={rowTestId} onClick={() => toggle(i.id)} style={{ cursor: 'pointer' }}>
          <td>{i.invoiceNo}</td><td>{i.vendorInvoiceNo}</td><td>{i.vendorName}</td><td>{i.invoiceDate}</td>
          <td>{i.dueDate}</td><td>{money(i.totalAmount)}</td>
          <td data-testid="inv-outstanding">{money(i.outstanding)}</td>
          <td>
            <span className="badge" data-testid="inv-status">{i.status}</span>
            {i.overdue && <span className="badge" data-testid="inv-overdue">{`${i.daysOverdue} days overdue`}</span>}
          </td>
        </tr>
        {openId === i.id && (
          <tr>
            <td colSpan={8}>
              <section data-testid="inv-detail" aria-label={`Invoice ${i.invoiceNo}`}>
                <dl className="facts">
                  <div><dt>Approved</dt><dd>{money(i.approvedAmount)}</dd></div>
                  <div><dt>Total</dt><dd>{money(i.totalAmount)}</dd></div>
                  <div><dt>Advances deducted</dt><dd>{money(i.advancesDeducted)}</dd></div>
                  <div><dt>Net payable</dt><dd>{money(i.netPayable)}</dd></div>
                  <div><dt>Paid</dt><dd>{money(i.paidAmount)}</dd></div>
                  <div><dt>Outstanding</dt><dd data-testid="detail-outstanding">{money(i.outstanding)}</dd></div>
                  <div><dt>Status</dt><dd data-testid="detail-status">{i.status}</dd></div>
                </dl>
                <h3>Bills covered</h3>
                <ul>{coveredBills.map((b) => (
                  <li key={b.billId}>{`${b.billNo}${b.tripNo ? ` (trip ${b.tripNo})` : ''}: ${money(b.approvedAmount)}`}</li>
                ))}</ul>
                <h3>Payments</h3>
                {invoicePayments.length === 0 ? <p className="muted">Nothing paid yet.</p> : (
                  <ul>{invoicePayments.map((p) => (
                    <li key={p.id}>{`${p.paidOn} ${money(p.amount)} by ${p.mode}, ${p.reference}`}</li>
                  ))}</ul>
                )}
                {can('PAYABLES_MANAGE') && i.status !== 'PAID' && (
                  <form className="inline" data-testid="pay-form" onSubmit={pay} onClick={(e) => e.stopPropagation()}>
                    {payError && <p className="error" role="alert" data-testid="pay-error">{payError}</p>}
                    <input type="number" min="0" step="0.01" placeholder="Amount" aria-label="Payment amount"
                      data-testid="pay-amount" required value={payAmount} onChange={(e) => setPayAmount(e.target.value)} />
                    <input type="date" aria-label="Paid on" data-testid="pay-date" required value={payDate}
                      onChange={(e) => setPayDate(e.target.value)} />
                    <select aria-label="Payment mode" data-testid="pay-mode" value={payMode}
                      onChange={(e) => setPayMode(e.target.value as PaymentModeChoice)}>
                      {PAYMENT_MODES.map((m) => <option key={m} value={m}>{m}</option>)}
                    </select>
                    <input placeholder="Reference" aria-label="Payment reference" data-testid="pay-reference" required
                      value={payReference} onChange={(e) => setPayReference(e.target.value)} />
                    <button type="submit" data-testid="pay-submit">Record payment</button>
                  </form>
                )}
              </section>
            </td>
          </tr>
        )}
      </Fragment>
    ));
  }

  const head = (
    <thead>
      <tr><th>Ours</th><th>Vendor's</th><th>Vendor</th><th>Dated</th><th>Due</th><th>Total</th><th>Outstanding</th><th>Status</th></tr>
    </thead>
  );

  return (
    <>
      <h1>Vendor payables</h1>
      <p className="muted">What vendors have invoiced against approved freight bills, what has been paid and what is overdue.</p>
      {error && <p className="error" role="alert" data-testid="pay-desk-error">{error}</p>}

      <p className="actions" role="tablist" aria-label="Payables views">
        <button type="button" role="tab" className={tab === 'invoices' ? '' : 'link'} data-testid="pay-tab-invoices"
          aria-selected={tab === 'invoices'} onClick={() => show('invoices')}>Invoices</button>{' '}
        <button type="button" role="tab" className={tab === 'payments' ? '' : 'link'} data-testid="pay-tab-payments"
          aria-selected={tab === 'payments'} onClick={() => show('payments')}>Payments</button>{' '}
        <button type="button" role="tab" className={tab === 'overdue' ? '' : 'link'} data-testid="pay-tab-overdue"
          aria-selected={tab === 'overdue'} onClick={() => show('overdue')}>Overdue</button>
      </p>

      {tab === 'invoices' && (
        <section aria-label="Invoices">
          {can('PAYABLES_MANAGE') && (
            <form data-testid="inv-form" onSubmit={capture}>
              <h2>Capture an invoice</h2>
              {captureError && <p className="error" role="alert" data-testid="inv-error">{captureError}</p>}
              {captured && <p className="ok" role="status" data-testid="inv-result">{captured}</p>}
              <input placeholder="Freight bill numbers, comma separated" aria-label="Freight bill numbers"
                data-testid="inv-bills" required value={bills} onChange={(e) => setBills(e.target.value)} />
              <input placeholder="Vendor's invoice number" aria-label="Vendor invoice number" data-testid="inv-vendor-no"
                required value={vendorNo} onChange={(e) => setVendorNo(e.target.value)} />
              <input type="date" aria-label="Invoice date" data-testid="inv-date" required value={date}
                onChange={(e) => setDate(e.target.value)} />
              <input type="number" min="0" step="0.01" placeholder="Amount before tax" aria-label="Invoice amount"
                data-testid="inv-amount" required value={amount} onChange={(e) => setAmount(e.target.value)} />
              <input type="number" min="0" step="0.01" placeholder="Tax" aria-label="Tax" data-testid="inv-tax"
                value={tax} onChange={(e) => setTax(e.target.value)} />
              <input placeholder="Document reference" aria-label="Document reference" data-testid="inv-document"
                value={document} onChange={(e) => setDocument(e.target.value)} />
              <button type="submit" data-testid="inv-submit">Capture invoice</button>
            </form>
          )}
          <h2>Invoices</h2>
          {invoices.length === 0 ? <p className="muted">No vendor invoices.</p> : (
            <table data-testid="inv-list">{head}<tbody>{invoiceRows(invoices, 'inv-row')}</tbody></table>
          )}
        </section>
      )}

      {tab === 'payments' && (
        <section aria-label="Payments">
          <h2>Payments</h2>
          {payments.length === 0 ? <p className="muted">No payments yet.</p> : (
            <table data-testid="payment-list">
              <thead><tr><th>Paid</th><th>Invoice</th><th>Vendor</th><th>Amount</th><th>Mode</th><th>Reference</th></tr></thead>
              <tbody>
                {payments.map((p) => (
                  <tr key={p.id} data-testid="payment-row">
                    <td>{p.paidOn}</td><td>{p.invoiceNo}</td><td>{p.vendorName}</td><td>{money(p.amount)}</td>
                    <td>{p.mode}</td><td>{p.reference}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}

      {tab === 'overdue' && (
        <section aria-label="Overdue">
          <h2>Overdue</h2>
          {overdue.length === 0 ? <p className="muted">Nothing is overdue.</p> : (
            <table data-testid="overdue-list">{head}<tbody>{invoiceRows(overdue, 'overdue-row')}</tbody></table>
          )}
        </section>
      )}
    </>
  );
}
