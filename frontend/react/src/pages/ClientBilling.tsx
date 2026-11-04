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

import { useState, type FormEvent } from 'react';
import { ApiProblem, type Row } from '../gateway/lmsGateway';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { CREDIT_LIMIT_EXCEEDED, CreditOverride, CreditPosition } from './Credit';

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

/**
 * A 3PL client's billing: its contracts, the month's charges (warehouse hand-offs
 * among them), billing runs, invoices with credit notes, payments, the statement
 * and the ageing.
 */
export function ClientBilling() {
  const [typed, setTyped] = useState('');
  const [clientId, setClientId] = useState('');

  function open(event: FormEvent) {
    event.preventDefault();
    setClientId(typed.trim().toLowerCase());
  }

  return (
    <>
      <h1>Client billing</h1>
      <p className="muted">
        Open a client to see what it has been charged, bill a month that has ended, and follow
        what it owes.
      </p>
      <form className="inline" onSubmit={open} data-testid="open-client">
        <EntityPicker kind="customer" placeholder="Client" ariaLabel="Client id" testId="client-id"
          value={typed} onChange={setTyped} required />
        <button type="submit">Open</button>
      </form>
      {clientId && <ClientDesk key={clientId} clientId={clientId} />}
    </>
  );
}

function ClientDesk({ clientId }: { clientId: string }) {
  const { gateway, can } = useSession();
  const [reload, setReload] = useState(0);
  const [month, setMonth] = useState(today().slice(0, 7));
  const [invoiceId, setInvoiceId] = useState<string | null>(null);
  const changed = () => setReload((n) => n + 1);

  const contracts = useLoad(() => gateway.clientContracts(clientId), reload);
  const charges = useLoad(() => gateway.clientCharges(clientId, `${month}-01`), `${month}:${reload}`);
  const invoices = useLoad(() => gateway.clientInvoices(clientId), reload);
  const statement = useLoad(() => gateway.clientStatement(clientId), reload);
  const ageing = useLoad(() => gateway.clientAgeing(clientId), reload);
  const error = contracts.error || charges.error || invoices.error || statement.error || ageing.error;
  const entries = statement.data ?? [];

  return (
    <section data-testid="client-desk">
      {error && <p className="error" role="alert">{error}</p>}
      {can('PARTNER_READ') && <CreditPosition partnerId={clientId} refreshKey={reload} />}

      <h2>Contracts</h2>
      {contracts.data && contracts.data.length === 0 ? (
        <p className="muted" data-testid="no-contract">This client holds no contract, so nothing is billed to it.</p>
      ) : (
        <Table rows={contracts.data ?? []} rowTestId="contract-row" columns={[
          ['code', 'Contract'], ['effectiveFrom', 'From'], ['effectiveTo', 'To'], ['currency', 'Currency'],
          ['transportRatePerKg', 'Transport / kg'], ['handlingInRatePerPallet', 'Handling in / pallet'],
          ['handlingOutRatePerPallet', 'Handling out / pallet'], ['storageRatePerPalletDay', 'Storage / pallet-day'],
          ['billing', 'Transport billed'],
        ]} />
      )}

      <h2>Charges</h2>
      <label>
        Month{' '}
        <input type="month" data-testid="charges-month" value={month}
          onChange={(e) => { if (e.target.value) { setMonth(e.target.value); } }} />
      </label>
      {charges.data && charges.data.length === 0 ? (
        <p className="muted" data-testid="no-charges">Nothing was charged in this month.</p>
      ) : (
        <div className="table-scroll">
          <Table rows={charges.data ?? []} rowTestId="charge-row" columns={[
            ['occurredOn', 'Day'], ['serviceType', 'Service'], ['serviceCode', 'Code'], ['quantity', 'Quantity'],
            ['unit', 'Unit'], ['reference', 'Reference'], ['invoiced', 'Invoiced'], ['late', 'Late'],
            ['billable', 'Billable'],
          ]} />
        </div>
      )}

      {can('CLIENT_BILLING_RUN') && (
        <RunBilling clientId={clientId} onBilled={(id) => { setInvoiceId(id); changed(); }} />
      )}
      {can('CLIENT_BILLING_RUN') && <MarkBillable onMarked={changed} />}

      <h2>Invoices</h2>
      {invoices.data && invoices.data.length === 0 ? (
        <p className="muted" data-testid="no-invoices">No invoice has been issued yet.</p>
      ) : (
        <table>
          <thead>
            <tr><th>Invoice</th><th>Period</th><th>Total</th><th>Credited</th><th>Currency</th></tr>
          </thead>
          <tbody>
            {(invoices.data ?? []).map((i) => (
              <tr key={i.id} data-testid="invoice-row" tabIndex={0}
                className={i.id === invoiceId ? 'selected' : undefined}
                onClick={() => setInvoiceId(i.id)}
                onKeyDown={(e) => { if (e.key === 'Enter') { setInvoiceId(i.id); } }}>
                <td>{i.invoiceNo}</td><td>{i.periodStart} – {i.periodEnd}</td><td>{i.totalAmount}</td>
                <td>{i.creditedAmount}</td><td>{i.currency}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {invoiceId && (
        <InvoicePanel key={invoiceId} invoiceId={invoiceId} version={reload} onChanged={changed}
          onClose={() => setInvoiceId(null)} />
      )}

      <h2>Statement</h2>
      {entries.length === 0 ? (
        <p className="muted">Nothing on the statement yet.</p>
      ) : (
        <Table rows={entries} rowTestId="statement-row" columns={[
          ['entryType', 'Entry'], ['documentNo', 'Document'], ['issuedAt', 'When'], ['invoiceNo', 'Against'],
          ['debit', 'Debit'], ['credit', 'Credit'], ['balance', 'Balance'],
        ]} />
      )}
      <p>Balance <strong data-testid="statement-balance">{entries.length ? entries[entries.length - 1].balance : '0'}</strong></p>
      {can('CLIENT_BILLING_RUN') && <RecordPayment clientId={clientId} onRecorded={changed} />}

      <h2>Ageing</h2>
      {ageing.data && (
        <dl>
          <div><dt>Outstanding</dt><dd data-testid="ageing-outstanding">{ageing.data.outstanding} {ageing.data.currency}</dd></div>
          <div><dt>Unapplied credit</dt><dd>{ageing.data.unappliedCredit}</dd></div>
          {Object.entries(ageing.data.buckets).map(([bucket, amount]) => (
            <div key={bucket}><dt>{bucket} days</dt><dd data-testid={`ageing-${bucket}`}>{amount}</dd></div>
          ))}
        </dl>
      )}
    </section>
  );
}

function Table({ rows, columns, rowTestId }: { rows: Row[]; columns: Array<[string, string]>; rowTestId: string }) {
  return (
    <table>
      <thead>
        <tr>{columns.map(([field, header]) => <th key={field}>{header}</th>)}</tr>
      </thead>
      <tbody>
        {rows.map((row, i) => (
          <tr key={row.id || i} data-testid={rowTestId}>
            {columns.map(([field]) => <td key={field}>{row[field]}</td>)}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function RunBilling({ clientId, onBilled }: { clientId: string; onBilled: (invoiceId: string) => void }) {
  const { gateway } = useSession();
  const [month, setMonth] = useState(lastMonth());
  const [error, setError] = useState('');
  const [code, setCode] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [billed, setBilled] = useState('');

  async function bill() {
    setError('');
    setCode('');
    setErrors({});
    setBilled('');
    try {
      onBilled(await gateway.runClientBilling(clientId, `${month}-01`));
      setBilled(month);
    } catch (e) {
      setError((e as Error).message);
      setCode(e instanceof ApiProblem ? e.code : '');
      setErrors(e instanceof ApiProblem ? e.errors : {});
    }
  }

  async function run(event: FormEvent) {
    event.preventDefault();
    await bill();
  }

  return (
    <>
      <h2>Billing run</h2>
      <form className="inline" onSubmit={run} data-testid="billing-run">
        <input type="month" aria-label="Month to bill" data-testid="billing-month" value={month}
          onChange={(e) => setMonth(e.target.value)} required />
        <button type="submit" data-testid="run-billing">Run billing</button>
      </form>
      {billed && <p data-testid="billing-result">Billed {billed}.</p>}
      {error && (
        <p className="error" role="alert" data-testid="billing-error">
          {code && <><code data-testid="billing-refusal">{code}</code>{' '}</>}
          {error}
        </p>
      )}
      {code === CREDIT_LIMIT_EXCEEDED && (
        <CreditOverride partnerId={clientId} subjectType="CLIENT_INVOICE" period={`${month}-01`} errors={errors}
          onGranted={() => { void bill(); }} />
      )}
    </>
  );
}

/**
 * Marks a consignment billable without its proof of delivery -- a paper copy was
 * signed, a code was never used. The reason is audited.
 */
function MarkBillable({ onMarked }: { onMarked: () => void }) {
  const { gateway } = useSession();
  const [consignmentId, setConsignmentId] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const [marked, setMarked] = useState(false);

  async function mark(event: FormEvent) {
    event.preventDefault();
    setError('');
    setMarked(false);
    try {
      await gateway.markConsignmentBillable(consignmentId.trim(), reason);
      setMarked(true);
      setReason('');
      onMarked();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <h2>Billable without proof</h2>
      <form className="inline" onSubmit={mark} data-testid="mark-billable">
        <input aria-label="Consignment id" placeholder="Consignment id" value={consignmentId}
          onChange={(e) => setConsignmentId(e.target.value)} required />
        <input aria-label="Reason it is billable" placeholder="Reason" value={reason}
          onChange={(e) => setReason(e.target.value)} required />
        <button type="submit">Mark billable</button>
      </form>
      {marked && <p data-testid="marked-billable">Marked billable.</p>}
      {error && <p className="error" role="alert">{error}</p>}
    </>
  );
}

interface InvoicePanelProps {
  invoiceId: string;
  /** Changes whenever the desk re-reads, so the invoice is read again too. */
  version: number;
  onChanged: () => void;
  onClose: () => void;
}

function InvoicePanel({ invoiceId, version, onChanged, onClose }: InvoicePanelProps) {
  const { gateway, can } = useSession();
  const detail = useLoad(() => gateway.clientInvoice(invoiceId), `${invoiceId}:${version}`);
  const [slip, setSlip] = useState(false);
  const [reason, setReason] = useState('');
  const [amount, setAmount] = useState('');
  const [error, setError] = useState('');
  const [issued, setIssued] = useState('');

  async function credit(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      setIssued(await gateway.issueClientCreditNote(invoiceId, { reason, amount: Number(amount) }));
      setReason('');
      setAmount('');
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  if (detail.error) {
    return <p className="error" role="alert">{detail.error}</p>;
  }
  if (!detail.data) {
    return null;
  }
  const { invoice, lines } = detail.data;
  const lineColumns: Array<[string, string]> = [
    ['lineNo', '#'], ['serviceType', 'Service'], ['narrative', 'Narrative'], ['quantity', 'Quantity'],
    ['unit', 'Unit'], ['rate', 'Rate'], ['amount', 'Amount'], ['taxAmount', 'Tax'], ['lineTotal', 'Total'],
    ['creditedAmount', 'Credited'],
  ];

  return (
    <section data-testid="invoice-panel" aria-label="Invoice">
      <header>
        <h3>{invoice.invoiceNo}</h3>
        <button className="link" onClick={onClose} aria-label="Close invoice">Close</button>
      </header>
      <dl>
        <div><dt>Period</dt><dd>{invoice.periodStart} – {invoice.periodEnd}</dd></div>
        <div><dt>Taxable</dt><dd>{invoice.taxableAmount}</dd></div>
        <div><dt>Tax</dt><dd>{invoice.taxAmount}</dd></div>
        <div><dt>Total</dt><dd data-testid="invoice-total">{invoice.totalAmount} {invoice.currency}</dd></div>
        <div><dt>Credited</dt><dd data-testid="invoice-credited">{invoice.creditedAmount}</dd></div>
      </dl>
      <Table rows={lines} rowTestId="invoice-line" columns={lineColumns} />
      <p><button className="link" data-testid="print-invoice" onClick={() => setSlip(true)}>Print invoice</button></p>
      {slip && (
        <section className="slip manifest" data-testid="invoice-slip" aria-label="Invoice to print">
          <h3>Invoice {invoice.invoiceNo}</h3>
          <p>{invoice.clientName} · {invoice.periodStart} – {invoice.periodEnd} · issued {invoice.issuedAt}</p>
          <Table rows={lines} rowTestId="slip-line" columns={lineColumns} />
          <p>Taxable {invoice.taxableAmount} · tax {invoice.taxAmount} · total {invoice.totalAmount} {invoice.currency}</p>
          <p className="actions no-print">
            <button data-testid="print-now" onClick={() => window.print()}>Print</button>{' '}
            <button className="link" onClick={() => setSlip(false)}>Close</button>
          </p>
        </section>
      )}
      {can('CLIENT_CREDIT_NOTE_ISSUE') && (
        <form className="inline" onSubmit={credit} data-testid="credit-note">
          <input placeholder="Reason" aria-label="Reason for the credit" value={reason}
            onChange={(e) => setReason(e.target.value)} required />
          <input placeholder="Amount before tax" aria-label="Amount to credit" inputMode="decimal" value={amount}
            onChange={(e) => setAmount(e.target.value)} required />
          <button type="submit">Issue credit note</button>
        </form>
      )}
      {issued && <p data-testid="credit-issued">Credit note {issued} issued.</p>}
      {error && <p className="error" role="alert">{error}</p>}
    </section>
  );
}

function RecordPayment({ clientId, onRecorded }: { clientId: string; onRecorded: () => void }) {
  const { gateway } = useSession();
  const [amount, setAmount] = useState('');
  const [receivedOn, setReceivedOn] = useState(today());
  const [reference, setReference] = useState('');
  const [error, setError] = useState('');
  const [recorded, setRecorded] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    setRecorded('');
    try {
      await gateway.recordClientPayment(clientId, { amount: Number(amount), receivedOn, reference });
      setRecorded(reference);
      setAmount('');
      setReference('');
      onRecorded();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <h3>Record a payment</h3>
      <form className="inline" onSubmit={submit} data-testid="record-payment">
        <input placeholder="Amount received" aria-label="Amount received" inputMode="decimal" value={amount}
          onChange={(e) => setAmount(e.target.value)} required />
        <input type="date" aria-label="Received on" value={receivedOn}
          onChange={(e) => setReceivedOn(e.target.value)} required />
        <input placeholder="Remittance reference" aria-label="Remittance reference" value={reference}
          onChange={(e) => setReference(e.target.value)} required />
        <button type="submit">Record payment</button>
      </form>
      {recorded && <p data-testid="payment-recorded">Payment {recorded} recorded.</p>}
      {error && <p className="error" role="alert">{error}</p>}
    </>
  );
}
