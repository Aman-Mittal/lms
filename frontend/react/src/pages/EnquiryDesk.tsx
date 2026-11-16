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

import { useEffect, useState, type FormEvent } from 'react';
import { ApiProblem } from '../gateway/lmsGateway';
import {
  ENQUIRY_STATUSES, type EnquiryDesk, type EnquiryFilter, type EnquiryLineDraft, type EnquiryLineView,
  type EnquiryOutcome, type EnquiryRow,
} from '../gateway/enquiryTypes';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { PageHeader } from './PageHeader';
import { StatusBadge } from './StatusBadge';

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

const NO_INPUTS: LineInputs = { amount: '', reason: '', wonQuantity: '' };

/** The quote the customer holds on a line, if any. */
const held = (line: EnquiryLineView) => line.quotes.some((q) => q.status === 'ISSUED');

/**
 * The enquiry desk: a customer's request for a price, quoted line by line, decided line by line, and then
 * turned into a draft order. Every change carries the enquiry's version, so two people cannot overwrite
 * each other; converting does not, because asking twice must give the same order.
 */
export function EnquiryDeskPage() {
  const { gateway, can, auth } = useSession();
  const orgUnitId = auth?.orgUnitId ?? '';

  const [filter, setFilter] = useState<EnquiryFilter>({ status: '', q: '' });
  const [rows, setRows] = useState<EnquiryRow[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState('');
  const [note, setNote] = useState('');
  const [selected, setSelected] = useState<EnquiryDesk | null>(null);

  const [customer, setCustomer] = useState('');
  const [reference, setReference] = useState('');
  const [entry, setEntry] = useState<EnquiryLineDraft>({ ...BLANK_LINE });
  const [draftLines, setDraftLines] = useState<EnquiryLineDraft[]>([]);
  const [cancelReason, setCancelReason] = useState('');
  const [inputs, setInputs] = useState<Record<string, LineInputs>>({});

  async function load(next: EnquiryFilter = filter) {
    setError('');
    try {
      setRows((await gateway.enquiries(next)).rows);
      setLoaded(true);
    } catch (e) {
      setError(problemText(e));
    }
  }

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function open(id: string) {
    setError('');
    setNote('');
    try {
      setSelected(await gateway.enquiry(id));
    } catch (e) {
      setError(problemText(e));
    }
  }

  const setEntryField = (key: keyof EnquiryLineDraft) => (value: string) => setEntry((e) => ({ ...e, [key]: value }));
  const inputsOf = (lineId: string) => inputs[lineId] ?? NO_INPUTS;
  const setInput = (lineId: string, key: keyof LineInputs, value: string) =>
    setInputs((all) => ({ ...all, [lineId]: { ...(all[lineId] ?? NO_INPUTS), [key]: value } }));

  /** Adds the line being typed to the draft; false (with the reason shown) when it is not yet a line. */
  function addLine(): boolean {
    if (!entry.originTerminalId || !entry.destinationTerminalId || !entry.quantity.trim()) {
      setError('A line needs an origin, a destination and a quantity.');
      return false;
    }
    setError('');
    setDraftLines((all) => [...all, entry]);
    setEntry({ ...BLANK_LINE, originTerminalId: entry.originTerminalId });
    return true;
  }

  async function create(event: FormEvent) {
    event.preventDefault();
    let lines = draftLines;
    if (entry.destinationTerminalId && entry.quantity.trim()) {
      lines = [...draftLines, entry];
    }
    if (!orgUnitId || lines.length === 0) {
      setError('An enquiry needs at least one line.');
      return;
    }
    try {
      const id = await gateway.createEnquiry(orgUnitId, { customerPartnerId: customer, reference, notes: '', lines });
      setDraftLines([]);
      setEntry({ ...BLANK_LINE });
      setReference('');
      await load();
      await open(id);
    } catch (e) {
      setError(problemText(e));
    }
  }

  /** Runs a change, then reads the enquiry and the list again: the version moved, and so may the status. */
  async function change(e: EnquiryDesk, done: string, action: () => Promise<unknown>) {
    setError('');
    setNote('');
    try {
      await action();
      setSelected(await gateway.enquiry(e.id));
      setNote(done);
      await load();
    } catch (err) {
      setError(problemText(err));
    }
  }

  const quote = (e: EnquiryDesk, line: EnquiryLineView) => {
    const draft = { amount: inputsOf(line.id).amount, note: '' };
    return change(e, 'Quote saved.', () => held(line)
      ? gateway.reviseEnquiryQuote(e.id, e.version, line.id, draft)
      : gateway.issueEnquiryQuote(e.id, e.version, line.id, draft));
  };
  const revoke = (e: EnquiryDesk, line: EnquiryLineView) =>
    change(e, 'Quote withdrawn.', () => gateway.revokeEnquiryQuote(e.id, e.version, line.id, inputsOf(line.id).reason));
  const decide = (e: EnquiryDesk, line: EnquiryLineView, outcome: EnquiryOutcome) =>
    change(e, 'Outcome recorded.', () => gateway.recordEnquiryOutcome(e.id, e.version, line.id, {
      outcome, wonQuantity: inputsOf(line.id).wonQuantity, reason: inputsOf(line.id).reason,
    }));

  const canDecide = (e: EnquiryDesk, line: EnquiryLineView) =>
    e.status === 'OPEN' && line.status === 'OPEN' && can('ORDER_UPDATE');

  return (
    <>
      <PageHeader title="Enquiry desk" />
      <p className="muted">Quote each line, record who won it, and convert what was won into a draft order.</p>

      <form className="inline" onSubmit={(ev) => { ev.preventDefault(); void load(); }} data-testid="enquiry-filter">
        <select aria-label="Status" data-testid="enquiry-filter-status" value={filter.status}
          onChange={(ev) => setFilter({ ...filter, status: ev.target.value })}>
          <option value="">Any status</option>
          {ENQUIRY_STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <input name="q" placeholder="Number or reference" aria-label="Search enquiries" data-testid="enquiry-filter-q"
          value={filter.q} onChange={(ev) => setFilter({ ...filter, q: ev.target.value })} />
        <button type="submit" data-testid="enquiry-filter-apply">Show</button>
      </form>
      {error && <p className="error" role="alert" data-testid="enquiry-error">{error}</p>}

      <div className="table-wrap"><table data-testid="enquiry-list">
        <thead><tr><th>Enquiry</th><th>Customer</th><th>Status</th><th>Respond by</th><th>Lines won</th><th /></tr></thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id} data-testid="enquiry-row" data-id={r.id}>
              <td data-testid="enquiry-row-no">{r.enquiryNo}</td>
              <td>{r.customerName}</td>
              <td><StatusBadge data-testid="enquiry-row-status" value={r.status} /></td>
              <td className="nowrap">{r.deadlineAt}</td>
              <td>{r.wonLineCount} of {r.lineCount}</td>
              <td><button className="link" data-testid="enquiry-row-open" onClick={() => void open(r.id)}>Open</button></td>
            </tr>
          ))}
        </tbody>
      </table></div>
      {rows.length === 0 && loaded && <p className="muted" data-testid="enquiry-none">No enquiries.</p>}

      {can('ORDER_CREATE') && orgUnitId && (
        <>
          <h2>New enquiry</h2>
          <form onSubmit={(ev) => void create(ev)} data-testid="enquiry-create-form">
            <div className="inline">
              <EntityPicker kind="customer" placeholder="Customer" ariaLabel="Enquiry customer" testId="enquiry-customer"
                value={customer} onChange={setCustomer} />
              <input name="reference" placeholder="Customer's reference" aria-label="Reference" data-testid="enquiry-reference"
                value={reference} onChange={(ev) => setReference(ev.target.value)} />
            </div>
            <div className="inline">
              <EntityPicker kind="terminal" placeholder="Origin" ariaLabel="Line origin" testId="enquiry-line-origin"
                value={entry.originTerminalId} onChange={setEntryField('originTerminalId')} />
              <EntityPicker kind="terminal" placeholder="Destination" ariaLabel="Line destination"
                testId="enquiry-line-destination" value={entry.destinationTerminalId}
                onChange={setEntryField('destinationTerminalId')} />
              <input name="loadType" placeholder="Load or vehicle type" aria-label="Load type" data-testid="enquiry-line-load-type"
                value={entry.loadType} onChange={(ev) => setEntryField('loadType')(ev.target.value)} />
              <input name="quantity" inputMode="decimal" placeholder="Quantity" aria-label="Quantity" data-testid="enquiry-line-quantity"
                value={entry.quantity} onChange={(ev) => setEntryField('quantity')(ev.target.value)} />
              <input name="uom" placeholder="Unit" aria-label="Unit" data-testid="enquiry-line-uom"
                value={entry.uom} onChange={(ev) => setEntryField('uom')(ev.target.value)} />
              <input name="weight" inputMode="decimal" placeholder="Weight (kg)" aria-label="Weight in kilograms"
                data-testid="enquiry-line-weight" value={entry.weightKg}
                onChange={(ev) => setEntryField('weightKg')(ev.target.value)} />
              <label>Pickup <input type="date" name="pickup" data-testid="enquiry-line-pickup" value={entry.wantedPickupOn}
                onChange={(ev) => setEntryField('wantedPickupOn')(ev.target.value)} /></label>
              <button type="button" data-testid="enquiry-line-add" onClick={addLine}>Add line</button>
            </div>
            <ul data-testid="enquiry-draft-lines">
              {draftLines.map((l, index) => (
                <li key={index} data-testid="enquiry-draft-line">{l.quantity} {l.uom}{' '}
                  <button type="button" className="link" data-testid="enquiry-draft-remove"
                    onClick={() => setDraftLines((all) => all.filter((_, i) => i !== index))}>Remove</button></li>
              ))}
            </ul>
            <p><button type="submit" data-testid="enquiry-create">Create enquiry</button></p>
          </form>
        </>
      )}

      {selected && (
        <section data-testid="enquiry-detail" data-id={selected.id}>
          <h2>Enquiry <span data-testid="enquiry-no">{selected.enquiryNo}</span></h2>
          <dl>
            <div><dt>Status</dt><dd><StatusBadge data-testid="enquiry-status" value={selected.status} /></dd></div>
            <div><dt>Respond by</dt><dd data-testid="enquiry-deadline">{selected.deadlineAt}</dd></div>
            {selected.reference && <div><dt>Reference</dt><dd>{selected.reference}</dd></div>}
            {selected.cancelReason && (
              <div><dt>Cancelled because</dt><dd data-testid="enquiry-cancel-shown">{selected.cancelReason}</dd></div>
            )}
            {selected.orderId && (
              <div><dt>Order</dt><dd data-testid="enquiry-order" data-id={selected.orderId}>{selected.orderNo || selected.orderId}</dd></div>
            )}
          </dl>
          {note && <p className="ok" role="status" data-testid="enquiry-note">{note}</p>}

          <div className="table-wrap"><table data-testid="enquiry-lines">
            <thead><tr><th>Line</th><th>Route</th><th>Quantity</th><th>Suggested</th><th>Quotes</th><th>Status</th><th /></tr></thead>
            <tbody>
              {selected.lines.map((l) => (
                <tr key={l.id} data-testid="enquiry-line-row" data-id={l.id}>
                  <td>{l.lineNo}</td>
                  <td data-testid="enquiry-line-route">{l.originCode} → {l.destinationCode}</td>
                  <td data-testid="enquiry-line-qty">{l.quantity} {l.uom}</td>
                  <td data-testid="enquiry-line-suggested">
                    {l.suggestedLaneCode ? `${l.suggestedLaneCode}: ${l.suggestedAmount || l.suggestedRate} ${l.currency}` : '—'}
                  </td>
                  <td>
                    <ul>
                      {l.quotes.map((q) => (
                        <li key={q.id} data-testid="enquiry-quote-row" data-id={q.id}>r{q.revision} {q.amount} {q.status}</li>
                      ))}
                    </ul>
                  </td>
                  <td data-testid="enquiry-line-status">
                    <StatusBadge value={l.status} />{l.wonQuantity ? ` (${l.wonQuantity})` : ''}{l.lostReason ? ` — ${l.lostReason}` : ''}
                  </td>
                  <td>
                    {canDecide(selected, l) && (
                      <>
                        <div className="inline">
                          <input inputMode="decimal" placeholder="Amount" aria-label="Quote amount" data-testid="enquiry-quote-amount"
                            value={inputsOf(l.id).amount} onChange={(ev) => setInput(l.id, 'amount', ev.target.value)}
                            onKeyDown={(ev) => { if (ev.key === 'Enter') { ev.preventDefault(); void quote(selected, l); } }} />
                          {held(l) ? (
                            <>
                              <button type="button" data-testid="enquiry-quote-revise" onClick={() => void quote(selected, l)}>Revise quote</button>
                              <button type="button" data-testid="enquiry-quote-revoke" onClick={() => void revoke(selected, l)}>Revoke quote</button>
                            </>
                          ) : (
                            <button type="button" data-testid="enquiry-quote-issue" onClick={() => void quote(selected, l)}>Issue quote</button>
                          )}
                        </div>
                        <div className="inline">
                          <input placeholder="Reason" aria-label="Reason" data-testid="enquiry-line-reason"
                            value={inputsOf(l.id).reason} onChange={(ev) => setInput(l.id, 'reason', ev.target.value)} />
                          <input inputMode="decimal" placeholder="Quantity won" aria-label="Quantity won"
                            data-testid="enquiry-line-won-quantity" value={inputsOf(l.id).wonQuantity}
                            onChange={(ev) => setInput(l.id, 'wonQuantity', ev.target.value)} />
                          <button type="button" data-testid="enquiry-outcome-won" onClick={() => void decide(selected, l, 'WON')}>Won</button>
                          <button type="button" data-testid="enquiry-outcome-partial" onClick={() => void decide(selected, l, 'PARTIAL')}>Partly won</button>
                          <button type="button" data-testid="enquiry-outcome-lost" onClick={() => void decide(selected, l, 'LOST')}>Lost</button>
                        </div>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table></div>

          {can('ORDER_UPDATE') && (
            <>
              {selected.status === 'OPEN' && (
                <>
                  <p><button type="button" data-testid="enquiry-rediscover"
                    onClick={() => void change(selected, 'Contract rates looked up again.', () => gateway.discoverEnquiryRates(selected.id, selected.version))}>
                    Look for contract rates again</button></p>
                  <form className="inline" data-testid="enquiry-cancel-form"
                    onSubmit={(ev) => { ev.preventDefault(); void change(selected, 'Enquiry cancelled.', () => gateway.cancelEnquiry(selected.id, selected.version, cancelReason)); }}>
                    <input name="cancelReason" placeholder="Why cancel" aria-label="Cancel reason" data-testid="enquiry-cancel-reason"
                      value={cancelReason} onChange={(ev) => setCancelReason(ev.target.value)} />
                    <button type="submit" data-testid="enquiry-cancel">Cancel enquiry</button>
                  </form>
                </>
              )}
              {(selected.status === 'WON' || selected.status === 'PARTIAL' || selected.status === 'CONVERTED') && can('ORDER_CREATE') && (
                <p><button type="button" data-testid="enquiry-convert"
                  onClick={() => void change(selected, 'Converted to a draft order.', () => gateway.convertEnquiry(selected.id))}>
                  Convert to a draft order</button></p>
              )}
            </>
          )}
        </section>
      )}
    </>
  );
}
