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
import {
  ApiProblem, MATERIAL_CLASSES, type CloseKindChoice, type HistoryEntryRow, type OrderDesk as Desk, type OrderHeaderDraft,
  type OrderLineDraft, type OrderLineView, type OrderRaiseDraft,
} from '../gateway/lmsGateway';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { CREDIT_LIMIT_EXCEEDED, CreditOverride, CreditPosition } from './Credit';

/** The editable part of a stored line, as an edit restates it. */
function draftOf(line: OrderLineView): OrderLineDraft {
  return {
    lineNo: line.lineNo, materialCode: line.materialCode, materialDescription: line.materialDescription,
    materialClass: line.materialClass, hazmatUnCode: line.hazmatUnCode, quantity: line.quantity, uom: line.uom,
    deadWeightKg: line.deadWeightKg, lengthM: line.lengthM, widthM: line.widthM, heightM: line.heightM,
    volumetricDivisor: line.volumetricDivisor, consigneePartnerId: line.consigneePartnerId,
    destinationTerminalId: line.destinationTerminalId,
  };
}

/** A new line, numbered after the last and going where the last one went. */
function nextLine(lines: OrderLineDraft[]): OrderLineDraft {
  const last = lines[lines.length - 1];
  return {
    lineNo: lines.reduce((max, l) => Math.max(max, l.lineNo), 0) + 1, materialCode: '', materialDescription: '',
    materialClass: 'GENERAL', hazmatUnCode: '', quantity: '1', uom: 'EA', deadWeightKg: '', lengthM: '', widthM: '',
    heightM: '', volumetricDivisor: '', consigneePartnerId: last?.consigneePartnerId ?? '',
    destinationTerminalId: last?.destinationTerminalId ?? '',
  };
}

/** What the editor starts from: the stored lines, or one blank line for an order that has none. */
function editableLines(lines: OrderLineView[]): OrderLineDraft[] {
  return lines.length > 0 ? lines.map(draftOf) : [nextLine([])];
}

/** Once consignments are cut from an order, its lines belong to them. */
const PLANNED = ['PARTIALLY_PLANNED', 'FULLY_PLANNED'];

/** An order may be closed from validation until it ends. */
const CLOSABLE = ['VALIDATED', ...PLANNED];

/** A refusal as the desk shows it: the problem's stable name, then what it says. */
interface Refusal {
  code: string;
  message: string;
  /** What the problem names, such as a credit refusal's exposure and limit. */
  errors: Record<string, string>;
}

/**
 * The order desk: raise an order, edit it while it is a draft, and amend it once validated
 * until freight is cut from it. Every edit sends back the version the desk was read at, so
 * two people editing one order cannot overwrite each other unseen.
 */
export function OrderDesk() {
  const { gateway, can, auth } = useSession();
  const [typed, setTyped] = useState('');
  const [orderId, setOrderId] = useState('');
  const [reload, setReload] = useState(0);

  const desk = useLoad(
    () => (orderId ? gateway.orderDesk(orderId) : Promise.resolve(null)),
    `${orderId}:${reload}`,
  );

  function openOrder(id: string) {
    setTyped(id);
    setOrderId(id.trim().toLowerCase());
    setReload((n) => n + 1);
  }

  function open(event: FormEvent) {
    event.preventDefault();
    openOrder(typed);
  }

  return (
    <>
      <h1>Order desk</h1>
      <p className="muted">
        Raise an order, edit it while it is a draft, and amend it once validated, until consignments
        are cut from it.
      </p>
      <form className="inline" onSubmit={open} data-testid="open-order">
        <EntityPicker kind="order" placeholder="Order" ariaLabel="Order id" testId="order-id"
          value={typed} onChange={setTyped} required />
        <button type="submit">Open</button>
      </form>
      {can('ORDER_CREATE') && auth?.orgUnitId && (
        <RaiseOrder orgUnitId={auth.orgUnitId} onRaised={openOrder} />
      )}
      {desk.error && <p className="error" role="alert">{desk.error}</p>}
      {desk.data && desk.data.order.id === orderId && (
        <OrderPanel key={orderId} desk={desk.data} onChanged={() => setReload((n) => n + 1)} />
      )}
    </>
  );
}

function RaiseOrder({ orgUnitId, onRaised }: { orgUnitId: string; onRaised: (id: string) => void }) {
  const { gateway } = useSession();
  const blank: OrderRaiseDraft = {
    orderNo: '', customerPartnerId: '', originTerminalId: '', requestedPickupAt: '', requestedDeliveryAt: '',
  };
  const [draft, setDraft] = useState(blank);
  const [error, setError] = useState('');

  async function raise(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      const id = await gateway.raiseOrder(orgUnitId, draft);
      setDraft(blank);
      onRaised(id);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const set = (field: keyof OrderRaiseDraft) => (value: string) => setDraft((d) => ({ ...d, [field]: value }));

  return (
    <form className="inline" onSubmit={raise} data-testid="raise-order">
      <input placeholder="Order number (blank: next in sequence)" aria-label="Order number" value={draft.orderNo}
        onChange={(e) => set('orderNo')(e.target.value)} />
      <EntityPicker kind="customer" placeholder="Customer" ariaLabel="New order customer id"
        value={draft.customerPartnerId} onChange={set('customerPartnerId')} required />
      <EntityPicker kind="terminal" placeholder="Origin terminal" ariaLabel="New order origin terminal id"
        value={draft.originTerminalId} onChange={set('originTerminalId')} required />
      <input placeholder="Pickup (ISO time, optional)" aria-label="New order pickup" value={draft.requestedPickupAt}
        onChange={(e) => set('requestedPickupAt')(e.target.value)} />
      <button type="submit">Raise order</button>
      {error && <span className="error" role="alert"> {error}</span>}
    </form>
  );
}

function OrderPanel({ desk, onChanged }: { desk: Desk; onChanged: () => void }) {
  const { gateway, can } = useSession();
  const { order, version } = desk;
  const [header, setHeader] = useState<OrderHeaderDraft>(desk.header);
  const [lines, setLines] = useState<OrderLineDraft[]>(editableLines(desk.lines));
  const [refusal, setRefusal] = useState<Refusal | null>(null);
  const [done, setDone] = useState('');
  const [reason, setReason] = useState('');
  const [closeReason, setCloseReason] = useState('');
  const [slip, setSlip] = useState(false);
  const [tab, setTab] = useState<'details' | 'history'>('details');

  // A fresh read replaces what was being edited; a refused edit leaves it, to be tried again.
  useEffect(() => {
    setHeader(desk.header);
    setLines(editableLines(desk.lines));
  }, [desk]);

  const draft = order.status === 'DRAFT';
  const validated = order.status === 'VALIDATED';
  const editable = can('ORDER_UPDATE') && (draft || validated);
  const closable = can('ORDER_UPDATE') && CLOSABLE.includes(order.status);

  /** Closes the order, or one line of it; the reason is cleared once the close is done. */
  function close(outcome: string, kind: CloseKindChoice, lineId?: string) {
    void act(outcome, async () => {
      await (lineId
        ? gateway.closeOrderLine(order.id, lineId, kind, closeReason)
        : gateway.closeOrder(order.id, kind, closeReason));
      setCloseReason('');
    });
  }

  async function act(outcome: string, action: () => Promise<unknown>) {
    setRefusal(null);
    setDone('');
    try {
      await action();
      setDone(outcome);
      onChanged();
    } catch (e) {
      setRefusal({
        code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message,
        errors: e instanceof ApiProblem ? e.errors : {},
      });
    }
  }

  /** An override is on record: a draft is validated again; a refused edit is the operator's to repeat. */
  function overridden() {
    if (draft) {
      void act('Validated under a credit override.', () => gateway.validateOrder(order.id));
    } else {
      setRefusal(null);
      setDone('Override granted; repeat the change.');
    }
  }

  function setLine(index: number, field: keyof OrderLineDraft, value: string) {
    setLines((now) => now.map((l, i) => (i === index ? { ...l, [field]: field === 'lineNo' ? Number(value) : value } : l)));
  }

  const setHeaderField = (field: keyof OrderHeaderDraft) => (value: string) =>
    setHeader((h) => ({ ...h, [field]: value }));

  return (
    <section data-testid="order-panel">
      <h2>{order.orderNo}</h2>
      <dl>
        <div><dt>Status</dt><dd data-testid="order-status">{order.status}</dd></div>
        <div><dt>Version</dt><dd data-testid="order-version">{version}</dd></div>
        <div><dt>Customer</dt><dd data-testid="order-customer">{order.customerName}</dd></div>
        <div><dt>Origin</dt><dd>{order.originTerminalCode}</dd></div>
        <div><dt>Mode</dt><dd>{order.freightMode}</dd></div>
        <div><dt>Lines</dt><dd data-testid="order-line-count">{order.lineCount}</dd></div>
        <div><dt>Weight kg</dt><dd data-testid="order-weight">{order.totalDeadWeightKg}</dd></div>
        <div><dt>Pickup</dt><dd>{order.requestedPickupAt || '—'}</dd></div>
        {order.closeKind && (
          <div>
            <dt>Closed</dt>
            <dd data-testid="order-close-kind">{order.closeKind}</dd>
            {order.closeReason && <dd className="muted" data-testid="order-close-reason">{order.closeReason}</dd>}
          </div>
        )}
      </dl>
      <p className="actions">
        {draft && can('ORDER_UPDATE') && (
          <button type="button" data-testid="validate-order"
            onClick={() => act('Validated.', () => gateway.validateOrder(order.id))}>Validate</button>
        )}{' '}
        {validated && can('LOAD_CREATE') && (
          <button type="button" data-testid="generate-consignments"
            onClick={() => act('Consignments cut.', () => gateway.generateConsignments(order.id))}>
            Generate consignments
          </button>
        )}{' '}
        <button type="button" className="link" data-testid="print-order" onClick={() => setSlip(true)}>Print order</button>{' '}
        <button type="button" className="link" data-testid="reload-order"
          onClick={() => { setRefusal(null); setDone(''); onChanged(); }}>Reload</button>
      </p>
      {done && <p className="ok" role="status" data-testid="order-done">{done}</p>}
      {refusal && (
        <p className="error" role="alert" data-testid="order-error">
          {refusal.code && <><code data-testid="order-refusal">{refusal.code}</code>{' '}</>}
          {refusal.message}
        </p>
      )}
      {refusal?.code === CREDIT_LIMIT_EXCEEDED && (
        <CreditOverride partnerId={desk.header.customerPartnerId} subjectType="ORDER" orderId={order.id}
          errors={refusal.errors} onGranted={overridden} />
      )}
      {desk.header.customerPartnerId && can('PARTNER_READ') && (
        <CreditPosition partnerId={desk.header.customerPartnerId} refreshKey={desk} />
      )}

      <p className="actions" role="tablist" aria-label="Order views">
        <button type="button" role="tab" className={tab === 'details' ? '' : 'link'} data-testid="tab-details"
          aria-selected={tab === 'details'} onClick={() => setTab('details')}>Lines</button>{' '}
        <button type="button" role="tab" className={tab === 'history' ? '' : 'link'} data-testid="tab-history"
          aria-selected={tab === 'history'} onClick={() => setTab('history')}>History</button>
      </p>
      {tab === 'history' && <OrderHistory key={version} orderId={order.id} />}
      <div hidden={tab !== 'details'} data-testid="order-details">
      <div className="table-scroll">
        <table data-testid="order-lines">
          <thead>
            <tr><th>#</th><th>Material</th><th>Class</th><th>Qty</th><th>Weight kg</th><th>L × W × H m</th><th>Consignee</th><th>Destination</th><th>Planned</th><th>Closed</th><th /></tr>
          </thead>
          <tbody>
            {desk.lines.map((l) => (
              <tr key={l.id} data-testid="order-line">
                <td>{l.lineNo}</td>
                <td>{l.materialCode} · {l.materialDescription}</td>
                <td>{l.materialClass}{l.hazmatUnCode && ` (UN ${l.hazmatUnCode})`}</td>
                <td>{l.quantity} {l.uom}</td>
                <td>{l.deadWeightKg}</td>
                <td>{l.lengthM ? `${l.lengthM} × ${l.widthM} × ${l.heightM}` : '—'}</td>
                <td>{l.consigneeName}</td>
                <td>{l.destinationTerminalCode}</td>
                <td>{l.planned ? 'Yes' : ''}</td>
                <td data-testid="line-closed">{l.cancelled ? 'CANCELLED' : l.closeKind}</td>
                <td>
                  {closable && !l.closeKind && !l.cancelled && (
                    <button type="button" className="link" data-testid="close-line"
                      onClick={() => close(`Line ${l.lineNo} closed.`, 'SHORT', l.id)}>
                      Short-close line
                    </button>
                  )}{' '}
                  {closable && !l.closeKind && !l.cancelled && can('ORDER_FORCE_CLOSE') && (
                    <button type="button" className="link" data-testid="force-close-line"
                      onClick={() => close(`Line ${l.lineNo} closed.`, 'FORCE', l.id)}>
                      Force-close line
                    </button>
                  )}
                  {draft && can('ORDER_UPDATE') && desk.lines.length > 1 && (
                    <button type="button" className="link" data-testid="remove-line"
                      onClick={() => act(`Line ${l.lineNo} removed.`, () => gateway.removeOrderLine(order.id, version, l.id))}>
                      Remove
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {closable && (
        <section data-testid="close-order" aria-label="Close the order">
          <h3>Close the order</h3>
          <p className="muted">
            A close needs every line planned and delivered. A short close gives up what is not yet planned once
            cut freight has left; a forced close ends the order whatever its freight is doing. Both need a reason,
            which also serves the line actions above. Consignments already cut carry on.
          </p>
          <div className="inline">
            <input aria-label="Close reason" placeholder="Why it is closed" value={closeReason}
              onChange={(e) => setCloseReason(e.target.value)} />
            <button type="button" data-testid="close-normal" onClick={() => close('Order closed.', 'NORMAL')}>
              Close
            </button>{' '}
            <button type="button" data-testid="close-short" onClick={() => close('Order short-closed.', 'SHORT')}>
              Short close
            </button>{' '}
            {can('ORDER_FORCE_CLOSE') && (
              <button type="button" data-testid="close-force" onClick={() => close('Order force-closed.', 'FORCE')}>
                Force close
              </button>
            )}
          </div>
        </section>
      )}
      {PLANNED.includes(order.status) && (
        <p className="muted" data-testid="not-amendable">
          Consignments have been cut from this order, so it can no longer be amended
          (<code>order-has-consignments</code>).
        </p>
      )}
      {editable && (
        <section data-testid={draft ? 'edit-draft' : 'amend-order'}>
          <h3>{draft ? 'Edit the draft' : 'Amend the order'}</h3>
          {validated && (
            <p className="muted">
              An amendment validates again and keeps the order validated. Once consignments are cut it is refused.
            </p>
          )}
          <div className="inline" data-testid="header-editor">
            <EntityPicker kind="customer" ariaLabel="Customer id" placeholder="Customer"
              value={header.customerPartnerId} onChange={setHeaderField('customerPartnerId')} />
            <EntityPicker kind="terminal" ariaLabel="Origin terminal id" placeholder="Origin terminal"
              value={header.originTerminalId} onChange={setHeaderField('originTerminalId')} />
            <input aria-label="Pickup" placeholder="Pickup (ISO time)" value={header.requestedPickupAt}
              onChange={(e) => setHeaderField('requestedPickupAt')(e.target.value)} />
            <input aria-label="Delivery" placeholder="Delivery (ISO time)" value={header.requestedDeliveryAt}
              onChange={(e) => setHeaderField('requestedDeliveryAt')(e.target.value)} />
          </div>
          <div className="table-scroll">
            <table data-testid="lines-editor">
              <thead>
                <tr><th>#</th><th>Code</th><th>Description</th><th>Class</th><th>Qty</th><th>UoM</th><th>Weight kg</th><th>L</th><th>W</th><th>H</th><th>Consignee</th><th>Destination</th><th /></tr>
              </thead>
              <tbody>
                {lines.map((l, i) => (
                  <tr key={i} data-testid="edit-line">
                    <td>{l.lineNo}</td>
                    <td><input aria-label={`Line ${l.lineNo} material code`} value={l.materialCode}
                      onChange={(e) => setLine(i, 'materialCode', e.target.value)} /></td>
                    <td><input aria-label={`Line ${l.lineNo} description`} value={l.materialDescription}
                      onChange={(e) => setLine(i, 'materialDescription', e.target.value)} /></td>
                    <td>
                      <select aria-label={`Line ${l.lineNo} class`} value={l.materialClass}
                        onChange={(e) => setLine(i, 'materialClass', e.target.value)}>
                        {MATERIAL_CLASSES.map((c) => <option key={c} value={c}>{c}</option>)}
                      </select>
                    </td>
                    <td><input aria-label={`Line ${l.lineNo} quantity`} value={l.quantity} size={5}
                      onChange={(e) => setLine(i, 'quantity', e.target.value)} /></td>
                    <td><input aria-label={`Line ${l.lineNo} unit`} value={l.uom} size={4}
                      onChange={(e) => setLine(i, 'uom', e.target.value)} /></td>
                    <td><input aria-label={`Line ${l.lineNo} weight kg`} value={l.deadWeightKg} size={7}
                      onChange={(e) => setLine(i, 'deadWeightKg', e.target.value)} /></td>
                    <td><input aria-label={`Line ${l.lineNo} length m`} value={l.lengthM} size={4}
                      onChange={(e) => setLine(i, 'lengthM', e.target.value)} /></td>
                    <td><input aria-label={`Line ${l.lineNo} width m`} value={l.widthM} size={4}
                      onChange={(e) => setLine(i, 'widthM', e.target.value)} /></td>
                    <td><input aria-label={`Line ${l.lineNo} height m`} value={l.heightM} size={4}
                      onChange={(e) => setLine(i, 'heightM', e.target.value)} /></td>
                    <td><EntityPicker kind="partner" ariaLabel={`Line ${l.lineNo} consignee id`}
                      value={l.consigneePartnerId} onChange={(id) => setLine(i, 'consigneePartnerId', id)} /></td>
                    <td><EntityPicker kind="terminal" ariaLabel={`Line ${l.lineNo} destination id`}
                      value={l.destinationTerminalId} onChange={(id) => setLine(i, 'destinationTerminalId', id)} /></td>
                    <td>
                      {lines.length > 1 && (
                        <button type="button" className="link" aria-label={`Drop line ${l.lineNo}`}
                          onClick={() => setLines((now) => now.filter((_, j) => j !== i))}>Drop</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="actions">
            <button type="button" className="link" data-testid="add-line"
              onClick={() => setLines((now) => [...now, nextLine(now)])}>Add a line</button>
          </p>
          <p className="actions">
            {draft ? (
              <>
                <button type="button" data-testid="save-header"
                  onClick={() => act('Header saved.', () => gateway.reviseOrderHeader(order.id, version, header))}>
                  Save header
                </button>{' '}
                <button type="button" data-testid="save-lines" disabled={lines.length === 0}
                  onClick={() => act('Lines saved.', () => gateway.replaceOrderLines(order.id, version, lines))}>
                  Save lines
                </button>
              </>
            ) : (
              <button type="button" data-testid="amend"
                onClick={() => act('Order amended.', () => gateway.amendOrder(order.id, version, header, lines))}>
                Amend order
              </button>
            )}
          </p>
          <form className="inline" data-testid="cancel-order"
            onSubmit={(e) => { e.preventDefault(); void act('Order cancelled.', () => gateway.cancelOrder(order.id, reason)); }}>
            <input aria-label="Cancellation reason" placeholder="Why it is cancelled (optional)" value={reason}
              onChange={(e) => setReason(e.target.value)} />
            <button type="submit">Cancel order</button>
          </form>
        </section>
      )}

      </div>

      {slip && (
        <section className="slip" data-testid="order-slip" aria-label="Order">
          <h3>Order · {order.orderNo}</h3>
          <p>
            {order.customerName} · from {order.originTerminalCode} · {order.status} · {order.totalDeadWeightKg} kg
          </p>
          <table>
            <thead><tr><th>#</th><th>Material</th><th>Qty</th><th>Weight kg</th><th>Consignee</th><th>Destination</th></tr></thead>
            <tbody>
              {desk.lines.map((l) => (
                <tr key={l.id} data-testid="slip-line">
                  <td>{l.lineNo}</td><td>{l.materialCode} · {l.materialDescription}</td><td>{l.quantity} {l.uom}</td>
                  <td>{l.deadWeightKg}</td><td>{l.consigneeName}</td><td>{l.destinationTerminalCode}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="actions no-print">
            <button data-testid="print-now" onClick={() => window.print()}>Print</button>{' '}
            <button className="link" onClick={() => setSlip(false)}>Close</button>
          </p>
        </section>
      )}
    </section>
  );
}

/** What has happened to the order, newest first, read a page at a time. */
function OrderHistory({ orderId }: { orderId: string }) {
  const { gateway } = useSession();
  const [entries, setEntries] = useState<HistoryEntryRow[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  async function load(cursor?: string) {
    setLoading(true);
    setError('');
    try {
      const page = await gateway.orderHistory(orderId, cursor);
      setEntries((now) => (cursor ? [...now, ...page.entries] : page.entries));
      setNext(page.nextCursor);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, [orderId]);

  return (
    <section data-testid="order-history" aria-label="Order history">
      <h3>History</h3>
      {error && <p className="error" role="alert">{error}</p>}
      {!loading && !error && entries.length === 0 && <p className="muted">Nothing recorded yet.</p>}
      {entries.length > 0 && (
        <div className="table-scroll">
          <table>
            <thead><tr><th>When (UTC)</th><th>What</th><th>Status</th><th>By</th><th>Reason</th></tr></thead>
            <tbody>
              {entries.map((e) => (
                <tr key={e.id} data-testid="history-entry">
                  <td>{e.occurredAt}</td>
                  <td data-testid="history-action">{e.action}</td>
                  <td>{e.change}</td>
                  <td>{e.actor || 'platform'}</td>
                  <td>{e.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {next && (
        <p className="actions">
          <button type="button" className="link" data-testid="history-older" disabled={loading}
            onClick={() => void load(next)}>Older</button>
        </p>
      )}
    </section>
  );
}
