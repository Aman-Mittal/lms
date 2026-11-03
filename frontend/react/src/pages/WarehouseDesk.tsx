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
import {
  ApiProblem, type AsnLineDraft, type OutboundLineDraft, type Row, type StockMoveDraft, type WarehouseDocument,
} from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { Qr } from './Qr';

type Tab = 'inbound' | 'stock' | 'outbound' | 'waves' | 'setup';
const TABS: Array<[Tab, string]> = [
  ['inbound', 'Inbound'], ['stock', 'Stock'], ['outbound', 'Outbound'], ['waves', 'Pick waves'], ['setup', 'Setup'],
];

/** A refusal as the desk shows it: the problem's stable code beside its message. */
interface Outcome {
  code: string;
  message: string;
}

function failure(e: unknown): Outcome {
  return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
}

function Refusal({ outcome }: { outcome: Outcome | null }) {
  if (!outcome) {
    return null;
  }
  return (
    <p className="error" role="alert">
      {outcome.code && <><code data-testid="wh-refusal">{outcome.code}</code>{' '}</>}
      {outcome.message}
    </p>
  );
}

/** What a desk shares with its sections: the warehouse, a way to read everything again, and look-up lists. */
interface Context {
  warehouseId: string;
  version: number;
  changed: () => void;
  bins: Row[];
  skus: Row[];
  stock: Row[];
}

/**
 * The warehouse floor: goods in and put away, what is on hand and in quarantine,
 * orders picked, packed and dispatched, pick waves, and the bins and SKUs it all runs on.
 */
export function WarehouseDesk() {
  const { gateway } = useSession();
  const [typed, setTyped] = useState('');
  const [warehouseId, setWarehouseId] = useState('');
  const choices = useLoad(() => gateway.warehouseChoices(), 'warehouses');

  function open(event: FormEvent) {
    event.preventDefault();
    setWarehouseId(typed.trim().toLowerCase());
  }

  return (
    <>
      <h1>Warehouse operations</h1>
      <p className="muted">Open a warehouse to receive, store, pick, pack and dispatch its goods.</p>
      <form className="inline" onSubmit={open} data-testid="open-warehouse">
        <input placeholder="Warehouse id" aria-label="Warehouse id" data-testid="warehouse-id" list="warehouse-options"
          value={typed} onChange={(e) => setTyped(e.target.value)} required />
        <datalist id="warehouse-options">
          {(choices.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}
        </datalist>
        <button type="submit">Open</button>
      </form>
      {warehouseId && <Warehouse key={warehouseId} warehouseId={warehouseId} />}
    </>
  );
}

function Warehouse({ warehouseId }: { warehouseId: string }) {
  const { gateway } = useSession();
  const [tab, setTab] = useState<Tab>('inbound');
  const [version, setVersion] = useState(0);
  const changed = () => setVersion((n) => n + 1);
  const bins = useLoad(() => gateway.warehouseBins(warehouseId), version);
  const skus = useLoad(() => gateway.skus(), version);
  const stock = useLoad(() => gateway.stock(warehouseId), version);
  const error = bins.error || skus.error || stock.error;
  const context: Context = {
    warehouseId, version, changed, bins: bins.data ?? [], skus: skus.data ?? [], stock: stock.data ?? [],
  };

  return (
    <section data-testid="warehouse-desk">
      {error && <p className="error" role="alert">{error}</p>}
      <datalist id="bin-options">
        {context.bins.map((b) => <option key={b.id} value={b.id}>{b.code}</option>)}
      </datalist>
      <datalist id="sku-options">
        {context.skus.map((s) => <option key={s.id} value={s.id}>{s.code} · {s.description}</option>)}
      </datalist>
      <nav className="inline" aria-label="Warehouse sections">
        {TABS.map(([key, title]) => (
          <button key={key} className={tab === key ? undefined : 'link'} aria-pressed={tab === key}
            data-testid={`tab-${key}`} onClick={() => setTab(key)}>{title}</button>
        ))}
      </nav>
      {tab === 'inbound' && <Inbound {...context} />}
      {tab === 'stock' && <Stock {...context} />}
      {tab === 'outbound' && <Outbound {...context} />}
      {tab === 'waves' && <Waves {...context} />}
      {tab === 'setup' && <Setup {...context} />}
    </section>
  );
}

interface TableProps {
  rows: Row[];
  columns: Array<[string, string]>;
  rowTestId: string;
  selected?: string | null;
  onOpen?: (id: string) => void;
}

function Table({ rows, columns, rowTestId, selected, onOpen }: TableProps) {
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>{columns.map(([field, header]) => <th key={field}>{header}</th>)}</tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={row.id || i} data-testid={rowTestId} tabIndex={onOpen ? 0 : undefined}
              className={onOpen && row.id === selected ? 'selected' : undefined}
              onClick={onOpen ? () => onOpen(row.id) : undefined}
              onKeyDown={onOpen ? (e) => { if (e.key === 'Enter') { onOpen(row.id); } } : undefined}>
              {columns.map(([field]) => <td key={field}>{row[field]}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Runs a desk action, then reports what happened: a confirmation, or the refusal by its code. */
function useAction() {
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [done, setDone] = useState('');
  async function run(action: () => Promise<unknown>, confirmation: string): Promise<boolean> {
    setOutcome(null);
    setDone('');
    try {
      await action();
      setDone(confirmation);
      return true;
    } catch (e) {
      setOutcome(failure(e));
      return false;
    }
  }
  const report = (
    <>
      {done && <p data-testid="wh-done">{done}</p>}
      <Refusal outcome={outcome} />
    </>
  );
  return { run, report };
}

// ---------------------------------------------------------------- inbound

function Inbound(context: Context) {
  const { gateway, can } = useSession();
  const { warehouseId, version, changed } = context;
  const [asnId, setAsnId] = useState<string | null>(null);
  const asns = useLoad(() => gateway.asns(warehouseId), version);

  return (
    <>
      <h2>Advance shipping notices</h2>
      {asns.error && <p className="error" role="alert">{asns.error}</p>}
      {asns.data && asns.data.length === 0 ? (
        <p className="muted" data-testid="no-asns">Nothing is expected at this warehouse.</p>
      ) : (
        <Table rows={asns.data ?? []} rowTestId="asn-row" selected={asnId} onOpen={setAsnId} columns={[
          ['asnNo', 'ASN'], ['status', 'Status'], ['crossDock', 'Cross-dock'], ['receivedAt', 'Received'],
        ]} />
      )}
      {asnId && <AsnPanel key={asnId} asnId={asnId} version={version} onChanged={changed} />}
      {can('WAREHOUSE_OPERATE') && <CreateAsn warehouseId={warehouseId} onCreated={(id) => { setAsnId(id); changed(); }} />}
      {can('WAREHOUSE_OPERATE') && <Putaway {...context} />}
    </>
  );
}

function LineEditor<T extends AsnLineDraft | OutboundLineDraft>(
  { lines, onChange, blank, withLot }: { lines: T[]; onChange: (lines: T[]) => void; blank: T; withLot: boolean },
) {
  const set = (i: number, field: keyof T, value: string) =>
    onChange(lines.map((l, j) => (j === i ? { ...l, [field]: value } : l)));
  return (
    <>
      {lines.map((l, i) => (
        <div className="inline" key={i}>
          <input aria-label={`Line ${i + 1} SKU`} placeholder="SKU id" list="sku-options" value={l.skuId}
            onChange={(e) => set(i, 'skuId', e.target.value)} required />
          {withLot && (
            <input aria-label={`Line ${i + 1} lot`} placeholder="Lot (optional)" value={(l as AsnLineDraft).lotNo}
              onChange={(e) => set(i, 'lotNo' as keyof T, e.target.value)} />
          )}
          <input aria-label={`Line ${i + 1} quantity`} placeholder="Quantity" inputMode="numeric" value={l.quantity}
            onChange={(e) => set(i, 'quantity', e.target.value)} required />
        </div>
      ))}
      <button type="button" className="link" onClick={() => onChange([...lines, { ...blank }])}>Add line</button>
    </>
  );
}

const BLANK_ASN_LINE: AsnLineDraft = { skuId: '', lotNo: '', quantity: '' };

function CreateAsn({ warehouseId, onCreated }: { warehouseId: string; onCreated: (id: string) => void }) {
  const { gateway } = useSession();
  const [asnNo, setAsnNo] = useState('');
  const [supplier, setSupplier] = useState('');
  const [lines, setLines] = useState<AsnLineDraft[]>([{ ...BLANK_ASN_LINE }]);
  const { run, report } = useAction();

  async function submit(event: FormEvent) {
    event.preventDefault();
    let id = '';
    const ok = await run(async () => {
      id = await gateway.createAsn({ warehouseId, asnNo, supplierPartnerId: supplier, lines });
    }, `ASN ${asnNo} is expected.`);
    if (ok) {
      setAsnNo('');
      setSupplier('');
      setLines([{ ...BLANK_ASN_LINE }]);
      onCreated(id);
    }
  }

  return (
    <>
      <h3>Expect a delivery</h3>
      <form onSubmit={submit} data-testid="create-asn">
        <div className="inline">
          <input aria-label="ASN number" placeholder="ASN number" value={asnNo} onChange={(e) => setAsnNo(e.target.value)} required />
          <input aria-label="Supplier id" placeholder="Supplier id (optional)" value={supplier}
            onChange={(e) => setSupplier(e.target.value)} />
        </div>
        <LineEditor lines={lines} onChange={setLines} blank={BLANK_ASN_LINE} withLot />
        <p><button type="submit">Create ASN</button></p>
      </form>
      {report}
    </>
  );
}

function AsnPanel({ asnId, version, onChanged }: { asnId: string; version: number; onChanged: () => void }) {
  const { gateway, can } = useSession();
  const detail = useLoad(() => gateway.asn(asnId), `${asnId}:${version}`);
  const [counts, setCounts] = useState<Record<number, { received: string; damaged: string }>>({});
  const [crossDock, setCrossDock] = useState(false);
  const { run, report } = useAction();

  if (detail.error) {
    return <p className="error" role="alert">{detail.error}</p>;
  }
  if (!detail.data) {
    return null;
  }
  const { header, lines } = detail.data;
  const countOf = (l: Row) => counts[Number(l.lineNo)] ?? { received: l.expectedQty, damaged: '0' };
  const setCount = (l: Row, field: 'received' | 'damaged', value: string) =>
    setCounts({ ...counts, [Number(l.lineNo)]: { ...countOf(l), [field]: value } });

  async function receive(event: FormEvent) {
    event.preventDefault();
    if (await run(() => gateway.receiveAsn(asnId, lines.map((l) => ({
      lineNo: Number(l.lineNo), receivedQty: countOf(l).received, damagedQty: countOf(l).damaged,
    })), crossDock), `ASN ${header.asnNo} received.`)) {
      onChanged();
    }
  }

  return (
    <section data-testid="asn-panel" aria-label="ASN">
      <h3>{header.asnNo} <span className="muted" data-testid="asn-status">{header.status}</span></h3>
      <Table rows={lines} rowTestId="asn-line" columns={[
        ['lineNo', '#'], ['skuCode', 'SKU'], ['lotNo', 'Lot'], ['expectedQty', 'Expected'], ['receivedQty', 'Received'],
        ['damagedQty', 'Damaged'], ['shortQty', 'Short'], ['excessQty', 'Excess'],
      ]} />
      {header.status === 'EXPECTED' && can('WAREHOUSE_OPERATE') && (
        <form onSubmit={receive} data-testid="receive-asn">
          {lines.map((l) => (
            <div className="inline" key={l.lineNo}>
              <span>Line {l.lineNo} · {l.skuCode}</span>
              <input aria-label={`Line ${l.lineNo} received`} inputMode="numeric" value={countOf(l).received}
                onChange={(e) => setCount(l, 'received', e.target.value)} required />
              <input aria-label={`Line ${l.lineNo} damaged`} inputMode="numeric" value={countOf(l).damaged}
                onChange={(e) => setCount(l, 'damaged', e.target.value)} />
            </div>
          ))}
          <label><input type="checkbox" checked={crossDock} onChange={(e) => setCrossDock(e.target.checked)} /> Cross-dock</label>
          <p><button type="submit">Receive</button></p>
        </form>
      )}
      {report}
    </section>
  );
}

const BLANK_MOVE: StockMoveDraft = { binId: '', skuId: '', lotNo: '', quantity: '', reason: '' };

function Putaway({ changed }: Context) {
  const { gateway } = useSession();
  const [move, setMove] = useState<StockMoveDraft>({ ...BLANK_MOVE });
  const { run, report } = useAction();

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (await run(() => gateway.putaway(move), `Put away ${move.quantity}.`)) {
      setMove({ ...BLANK_MOVE });
      changed();
    }
  }

  return (
    <>
      <h3>Put away</h3>
      <form className="inline" onSubmit={submit} data-testid="putaway">
        <MoveFields move={move} onChange={setMove} withBin withReason={false} />
        <button type="submit">Put away</button>
      </form>
      {report}
    </>
  );
}

function MoveFields({ move, onChange, withBin, withReason }:
  { move: StockMoveDraft; onChange: (move: StockMoveDraft) => void; withBin: boolean; withReason: boolean }) {
  const set = (field: keyof StockMoveDraft, value: string) => onChange({ ...move, [field]: value });
  return (
    <>
      {withBin && (
        <input aria-label="Bin" placeholder="Bin id" list="bin-options" value={move.binId}
          onChange={(e) => set('binId', e.target.value)} required />
      )}
      <input aria-label="SKU" placeholder="SKU id" list="sku-options" value={move.skuId}
        onChange={(e) => set('skuId', e.target.value)} required />
      <input aria-label="Lot" placeholder="Lot (optional)" value={move.lotNo} onChange={(e) => set('lotNo', e.target.value)} />
      <input aria-label="Quantity" placeholder="Quantity" inputMode="numeric" value={move.quantity}
        onChange={(e) => set('quantity', e.target.value)} required />
      {withReason && (
        <input aria-label="Reason" placeholder="Reason" value={move.reason}
          onChange={(e) => set('reason', e.target.value)} required />
      )}
    </>
  );
}

// ---------------------------------------------------------------- stock

function Stock(context: Context) {
  const { gateway, can } = useSession();
  const [skuId, setSkuId] = useState('');
  const [shown, setShown] = useState('');
  const movements = useLoad(() => (shown ? gateway.stockMovements(shown) : Promise.resolve([])), `${shown}:${context.version}`);

  return (
    <>
      <h2>On hand</h2>
      {context.stock.length === 0 ? (
        <p className="muted" data-testid="no-stock">Nothing is on hand here.</p>
      ) : (
        <Table rows={context.stock} rowTestId="stock-row" columns={[
          ['locationType', 'Where'], ['locationCode', 'Location'], ['skuCode', 'SKU'], ['lotNo', 'Lot'], ['onHand', 'On hand'],
        ]} />
      )}
      <h3>Movements</h3>
      <form className="inline" onSubmit={(e) => { e.preventDefault(); setShown(skuId.trim()); }} data-testid="movements">
        <input aria-label="SKU for movements" placeholder="SKU id" list="sku-options" value={skuId}
          onChange={(e) => setSkuId(e.target.value)} required />
        <button type="submit">Show movements</button>
      </form>
      {movements.error && <p className="error" role="alert">{movements.error}</p>}
      {shown && movements.data && (
        <Table rows={movements.data} rowTestId="movement-row" columns={[
          ['occurredAt', 'When'], ['movementType', 'Movement'], ['locationType', 'Where'], ['lotNo', 'Lot'],
          ['quantity', 'Quantity'], ['reason', 'Reason'], ['actor', 'By'],
        ]} />
      )}
      {can('INVENTORY_ADJUST') && (
        <>
          <StockMove {...context} kind="adjust" title="Adjust a bin" testId="adjust-stock" button="Adjust" />
          <StockMove {...context} kind="release" title="Release from quarantine" testId="release-quarantine" button="Release" />
          <StockMove {...context} kind="write-off" title="Write off quarantine" testId="write-off-quarantine" button="Write off" />
        </>
      )}
    </>
  );
}

interface StockMoveProps extends Context {
  kind: 'adjust' | 'release' | 'write-off';
  title: string;
  testId: string;
  button: string;
}

function StockMove({ kind, title, testId, button, warehouseId, changed }: StockMoveProps) {
  const { gateway } = useSession();
  const [move, setMove] = useState<StockMoveDraft>({ ...BLANK_MOVE });
  const { run, report } = useAction();

  async function submit(event: FormEvent) {
    event.preventDefault();
    const act = kind === 'adjust' ? () => gateway.adjustStock(move)
      : kind === 'release' ? () => gateway.releaseQuarantine(move)
        : () => gateway.writeOffQuarantine(warehouseId, move);
    if (await run(act, `${button}: ${move.quantity} recorded.`)) {
      setMove({ ...BLANK_MOVE });
      changed();
    }
  }

  return (
    <>
      <h3>{title}</h3>
      <form className="inline" onSubmit={submit} data-testid={testId}>
        <MoveFields move={move} onChange={setMove} withBin={kind !== 'write-off'} withReason />
        <button type="submit">{button}</button>
      </form>
      {report}
    </>
  );
}

// ---------------------------------------------------------------- outbound

function Outbound(context: Context) {
  const { gateway, can } = useSession();
  const { warehouseId, version, changed } = context;
  const [orderId, setOrderId] = useState<string | null>(null);
  const [unitId, setUnitId] = useState<string | null>(null);
  const orders = useLoad(() => gateway.outboundOrders(warehouseId), version);
  const units = useLoad(() => gateway.handlingUnits(warehouseId), version);

  return (
    <>
      <h2>Outbound orders</h2>
      {orders.error && <p className="error" role="alert">{orders.error}</p>}
      {orders.data && orders.data.length === 0 ? (
        <p className="muted" data-testid="no-orders">No order is going out of this warehouse.</p>
      ) : (
        <Table rows={orders.data ?? []} rowTestId="outbound-row" selected={orderId} onOpen={setOrderId} columns={[
          ['orderNo', 'Order'], ['status', 'Status'], ['shipTo', 'Ship to'], ['orderedUnits', 'Ordered'],
          ['pickedUnits', 'Picked'], ['backOrderedUnits', 'Back-ordered'],
        ]} />
      )}
      {orderId && (
        <OrderPanel key={orderId} orderId={orderId} stock={context.stock} version={version}
          onPacked={(id) => { setUnitId(id); changed(); }} onChanged={changed} />
      )}
      {can('WAREHOUSE_OPERATE') && <CreateOutbound warehouseId={warehouseId} onCreated={(id) => { setOrderId(id); changed(); }} />}

      <h2>Handling units</h2>
      {units.error && <p className="error" role="alert">{units.error}</p>}
      {units.data && units.data.length === 0 ? (
        <p className="muted" data-testid="no-units">Nothing has been packed here yet.</p>
      ) : (
        <Table rows={units.data ?? []} rowTestId="unit-row" selected={unitId} onOpen={setUnitId} columns={[
          ['huNo', 'Unit'], ['status', 'Status'], ['source', 'Source'], ['dispatchRef', 'Dispatch ref'], ['dispatchedAt', 'Dispatched'],
        ]} />
      )}
      {unitId && <UnitPanel key={unitId} unitId={unitId} version={version} onChanged={changed} />}
    </>
  );
}

const BLANK_OUT_LINE: OutboundLineDraft = { skuId: '', quantity: '' };

function CreateOutbound({ warehouseId, onCreated }: { warehouseId: string; onCreated: (id: string) => void }) {
  const { gateway } = useSession();
  const [orderNo, setOrderNo] = useState('');
  const [shipTo, setShipTo] = useState('');
  const [lines, setLines] = useState<OutboundLineDraft[]>([{ ...BLANK_OUT_LINE }]);
  const { run, report } = useAction();

  async function submit(event: FormEvent) {
    event.preventDefault();
    let id = '';
    const ok = await run(async () => {
      id = await gateway.createOutboundOrder({ warehouseId, orderNo, shipTo, lines });
    }, `Order ${orderNo} is open.`);
    if (ok) {
      setOrderNo('');
      setShipTo('');
      setLines([{ ...BLANK_OUT_LINE }]);
      onCreated(id);
    }
  }

  return (
    <>
      <h3>Send goods out</h3>
      <form onSubmit={submit} data-testid="create-outbound">
        <div className="inline">
          <input aria-label="Order number" placeholder="Order number" value={orderNo}
            onChange={(e) => setOrderNo(e.target.value)} required />
          <input aria-label="Ship to" placeholder="Ship to (optional)" value={shipTo} onChange={(e) => setShipTo(e.target.value)} />
        </div>
        <LineEditor lines={lines} onChange={setLines} blank={BLANK_OUT_LINE} withLot={false} />
        <p><button type="submit">Create order</button></p>
      </form>
      {report}
    </>
  );
}

/** The bins a SKU can be picked from, with what each holds. */
function binsHolding(stock: Row[], skuId: string): string {
  return stock.filter((s) => s.locationType === 'BIN' && s.skuId === skuId)
    .map((s) => `${s.locationCode} (${s.onHand})`).join(', ');
}

interface OrderPanelProps {
  orderId: string;
  stock: Row[];
  version: number;
  onPacked: (unitId: string) => void;
  onChanged: () => void;
}

function OrderPanel({ orderId, stock, version, onPacked, onChanged }: OrderPanelProps) {
  const { gateway, can } = useSession();
  const detail = useLoad(() => gateway.outboundOrder(orderId), `${orderId}:${version}`);
  const [slip, setSlip] = useState(false);
  const { run, report } = useAction();

  if (detail.error) {
    return <p className="error" role="alert">{detail.error}</p>;
  }
  if (!detail.data) {
    return null;
  }
  const { header, lines }: WarehouseDocument = detail.data;
  const status = header.status;
  const operate = can('WAREHOUSE_OPERATE');

  async function pack() {
    let unit = '';
    if (await run(async () => { unit = await gateway.packOutboundOrder(orderId); }, `Order ${header.orderNo} packed.`)) {
      onPacked(unit);
    }
  }

  return (
    <section data-testid="outbound-panel" aria-label="Outbound order">
      <h3>{header.orderNo} <span className="muted" data-testid="outbound-status">{status}</span></h3>
      <Table rows={lines} rowTestId="outbound-line" columns={[
        ['lineNo', '#'], ['skuCode', 'SKU'], ['orderedQty', 'Ordered'], ['pickedQty', 'Picked'], ['shortQty', 'Short'],
      ]} />
      <p className="actions">
        {operate && (status === 'OPEN' || status === 'BACK_ORDERED') && (
          <button data-testid="pick-order"
            onClick={async () => { if (await run(() => gateway.pickOutboundOrder(orderId), `Order ${header.orderNo} picked.`)) { onChanged(); } }}>
            Pick
          </button>
        )}{' '}
        {operate && (status === 'PICKED' || status === 'SHORT_PICKED') && (
          <button data-testid="pack-order" onClick={pack}>Pack</button>
        )}{' '}
        {operate && status === 'BACK_ORDERED' && (
          <button data-testid="cancel-back-order"
            onClick={async () => { if (await run(() => gateway.cancelBackOrder(orderId), 'Back order cancelled.')) { onChanged(); } }}>
            Cancel back order
          </button>
        )}{' '}
        <button className="link" data-testid="print-pick-list" onClick={() => setSlip(true)}>Print pick list</button>
      </p>
      {report}
      {slip && (
        <section className="slip manifest" data-testid="pick-list" aria-label="Pick list to print">
          <h3>Pick list {header.orderNo}</h3>
          <p>{header.shipTo ? `Ship to ${header.shipTo} · ` : ''}raised {header.createdAt}</p>
          <table>
            <thead><tr><th>#</th><th>SKU</th><th>Quantity</th><th>From bins</th><th>Picked</th></tr></thead>
            <tbody>
              {lines.map((l) => (
                <tr key={l.lineNo} data-testid="pick-list-row">
                  <td>{l.lineNo}</td><td>{l.skuCode}</td><td>{l.orderedQty}</td>
                  <td>{binsHolding(stock, l.skuId)}</td><td>&nbsp;</td>
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

function UnitPanel({ unitId, version, onChanged }: { unitId: string; version: number; onChanged: () => void }) {
  const { gateway, can } = useSession();
  const detail = useLoad(() => gateway.handlingUnit(unitId), `${unitId}:${version}`);
  const terminals = useLoad(() => gateway.terminalChoices(), 'terminals');
  const [label, setLabel] = useState(false);
  const [reference, setReference] = useState('');
  const [destination, setDestination] = useState('');
  const [consignee, setConsignee] = useState('');
  const [weight, setWeight] = useState('');
  const { run, report } = useAction();

  if (detail.error) {
    return <p className="error" role="alert">{detail.error}</p>;
  }
  if (!detail.data) {
    return null;
  }
  const { header, lines } = detail.data;

  async function dispatch(event: FormEvent) {
    event.preventDefault();
    if (await run(() => gateway.dispatchHandlingUnit(unitId, {
      reference, destinationTerminalId: destination, consigneePartnerId: consignee, grossWeightKg: weight,
    }), `Unit ${header.huNo} dispatched.`)) {
      onChanged();
    }
  }

  return (
    <section data-testid="unit-panel" aria-label="Handling unit">
      <h3>{header.huNo} <span className="muted" data-testid="unit-status">{header.status}</span></h3>
      <Table rows={lines} rowTestId="unit-content" columns={[['skuCode', 'SKU'], ['lotNo', 'Lot'], ['quantity', 'Quantity']]} />
      <p><button className="link" data-testid="print-unit-label" onClick={() => setLabel(true)}>Print label</button></p>
      {label && (
        <section className="slip" data-testid="unit-label" aria-label="Label to print">
          <div className="slip-body">
            <dl>
              <div><dt>Unit</dt><dd>{header.huNo}</dd></div>
              <div><dt>Source</dt><dd>{header.source}</dd></div>
              <div><dt>Contents</dt><dd>{lines.map((l) => `${l.skuCode} × ${l.quantity}`).join(', ')}</dd></div>
              {header.grossWeightKg && <div><dt>Gross</dt><dd>{header.grossWeightKg} kg</dd></div>}
            </dl>
            <Qr payload={`LMS:HU:${header.huNo}`} />
          </div>
          <p className="actions no-print">
            <button data-testid="print-now" onClick={() => window.print()}>Print</button>{' '}
            <button className="link" onClick={() => setLabel(false)}>Close</button>
          </p>
        </section>
      )}
      {header.status === 'PACKED' && can('WAREHOUSE_OPERATE') && (
        <form onSubmit={dispatch} data-testid="dispatch-unit">
          <div className="inline">
            <input aria-label="Dispatch reference" placeholder="Dispatch reference" value={reference}
              onChange={(e) => setReference(e.target.value)} />
            <input aria-label="Destination terminal" placeholder="Destination terminal (optional)" list="terminal-options"
              value={destination} onChange={(e) => setDestination(e.target.value)} />
            <datalist id="terminal-options">
              {(terminals.data ?? []).map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
            </datalist>
            <input aria-label="Consignee id" placeholder="Consignee id (optional)" value={consignee}
              onChange={(e) => setConsignee(e.target.value)} />
            <input aria-label="Gross weight kg" placeholder="Gross kg (optional)" inputMode="decimal" value={weight}
              onChange={(e) => setWeight(e.target.value)} />
          </div>
          <p className="muted">A destination, a consignee and a weight hand the unit to transport as a consignment.</p>
          <p><button type="submit">Dispatch</button></p>
        </form>
      )}
      {report}
    </section>
  );
}

// ---------------------------------------------------------------- pick waves

function Waves({ warehouseId, version, changed }: Context) {
  const { gateway, can } = useSession();
  const [waveId, setWaveId] = useState<string | null>(null);
  const [chosen, setChosen] = useState<string[]>([]);
  const waves = useLoad(() => gateway.pickWaves(warehouseId), version);
  const orders = useLoad(() => gateway.outboundOrders(warehouseId), version);
  const open = (orders.data ?? []).filter((o) => o.status === 'OPEN');
  const { run, report } = useAction();

  async function release(event: FormEvent) {
    event.preventDefault();
    let id = '';
    if (await run(async () => { id = await gateway.releasePickWave(warehouseId, chosen); },
      `Wave released with ${chosen.length} orders.`)) {
      setChosen([]);
      setWaveId(id);
      changed();
    }
  }

  return (
    <>
      <h2>Pick waves</h2>
      {waves.error && <p className="error" role="alert">{waves.error}</p>}
      {waves.data && waves.data.length === 0 ? (
        <p className="muted" data-testid="no-waves">No wave has been released here.</p>
      ) : (
        <Table rows={waves.data ?? []} rowTestId="wave-row" selected={waveId} onOpen={setWaveId} columns={[
          ['waveNo', 'Wave'], ['status', 'Status'], ['orderCount', 'Orders'], ['pickedAt', 'Picked'],
        ]} />
      )}
      {waveId && <WavePanel key={waveId} waveId={waveId} version={version} onChanged={changed} />}
      {can('WAREHOUSE_OPERATE') && (
        <>
          <h3>Release a wave</h3>
          {open.length === 0 ? (
            <p className="muted">No open order is waiting to be picked.</p>
          ) : (
            <form onSubmit={release} data-testid="release-wave">
              {open.map((o) => (
                <label key={o.id} className="inline">
                  <input type="checkbox" aria-label={`Wave ${o.orderNo}`} checked={chosen.includes(o.id)}
                    onChange={(e) => setChosen(e.target.checked ? [...chosen, o.id] : chosen.filter((c) => c !== o.id))} />
                  {o.orderNo} · {o.orderedUnits} units
                </label>
              ))}
              <p><button type="submit" disabled={chosen.length === 0}>Release wave</button></p>
            </form>
          )}
          {report}
        </>
      )}
    </>
  );
}

function WavePanel({ waveId, version, onChanged }: { waveId: string; version: number; onChanged: () => void }) {
  const { gateway, can } = useSession();
  const detail = useLoad(() => gateway.pickWave(waveId), `${waveId}:${version}`);
  const { run, report } = useAction();

  if (detail.error) {
    return <p className="error" role="alert">{detail.error}</p>;
  }
  if (!detail.data) {
    return null;
  }
  const { header, lines } = detail.data;
  return (
    <section data-testid="wave-panel" aria-label="Pick wave">
      <h3>{header.waveNo} <span className="muted" data-testid="wave-status">{header.status}</span></h3>
      <Table rows={lines} rowTestId="wave-order" columns={[
        ['seq', '#'], ['orderNo', 'Order'], ['status', 'Status'], ['orderedUnits', 'Ordered'], ['pickedUnits', 'Picked'],
        ['shortUnits', 'Short'],
      ]} />
      {header.status === 'RELEASED' && can('WAREHOUSE_OPERATE') && (
        <p>
          <button data-testid="pick-wave"
            onClick={async () => { if (await run(() => gateway.pickPickWave(waveId), `Wave ${header.waveNo} picked.`)) { onChanged(); } }}>
            Pick wave
          </button>
        </p>
      )}
      {report}
    </section>
  );
}

// ---------------------------------------------------------------- setup

function Setup({ warehouseId, bins, skus, changed }: Context) {
  const { gateway, can } = useSession();
  const [binCode, setBinCode] = useState('');
  const [capacity, setCapacity] = useState('');
  const [sku, setSku] = useState({ code: '', description: '', ownerPartnerId: '', uom: '' });
  const binAction = useAction();
  const skuAction = useAction();

  async function createBin(event: FormEvent) {
    event.preventDefault();
    if (await binAction.run(() => gateway.createBin(warehouseId, binCode, Number(capacity)), `Bin ${binCode} created.`)) {
      setBinCode('');
      setCapacity('');
      changed();
    }
  }

  async function createSku(event: FormEvent) {
    event.preventDefault();
    if (await skuAction.run(() => gateway.createSku(sku), `SKU ${sku.code} created.`)) {
      setSku({ code: '', description: '', ownerPartnerId: '', uom: '' });
      changed();
    }
  }

  return (
    <>
      <h2>Bins</h2>
      {bins.length === 0 ? (
        <p className="muted" data-testid="no-bins">This warehouse has no bins yet.</p>
      ) : (
        <Table rows={bins} rowTestId="bin-row" columns={[
          ['code', 'Bin'], ['capacityUnits', 'Capacity'], ['occupiedUnits', 'Occupied'],
        ]} />
      )}
      {can('WAREHOUSE_SETUP') && (
        <>
          <form className="inline" onSubmit={createBin} data-testid="create-bin">
            <input aria-label="Bin code" placeholder="Bin code" value={binCode} onChange={(e) => setBinCode(e.target.value)} required />
            <input aria-label="Capacity units" placeholder="Capacity units" inputMode="numeric" value={capacity}
              onChange={(e) => setCapacity(e.target.value)} required />
            <button type="submit">Create bin</button>
          </form>
          {binAction.report}
        </>
      )}
      <h2>SKUs</h2>
      <Table rows={skus} rowTestId="sku-row" columns={[['code', 'SKU'], ['description', 'Description'], ['uom', 'Unit']]} />
      {can('WAREHOUSE_SETUP') && (
        <>
          <form className="inline" onSubmit={createSku} data-testid="create-sku">
            <input aria-label="SKU code" placeholder="SKU code" value={sku.code}
              onChange={(e) => setSku({ ...sku, code: e.target.value })} required />
            <input aria-label="SKU description" placeholder="Description" value={sku.description}
              onChange={(e) => setSku({ ...sku, description: e.target.value })} required />
            <input aria-label="Owner id" placeholder="Owner client id (optional)" value={sku.ownerPartnerId}
              onChange={(e) => setSku({ ...sku, ownerPartnerId: e.target.value })} />
            <input aria-label="Unit of measure" placeholder="Unit (optional)" value={sku.uom}
              onChange={(e) => setSku({ ...sku, uom: e.target.value })} />
            <button type="submit">Create SKU</button>
          </form>
          {skuAction.report}
        </>
      )}
    </>
  );
}
