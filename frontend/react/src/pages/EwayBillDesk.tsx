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
  EWAY_STATUSES, type EwayAction, type EwayBillRow, type EwayFilter, type EwayGenerateDraft, type EwayPartBResult,
} from '../gateway/ewayTypes';
import { useSession } from '../session';
import { PageHeader } from './PageHeader';
import { StatusBadge } from './StatusBadge';
import { ErrorBanner } from './ErrorBanner';
import { EntityPicker } from '../EntityPicker';
import { EwayRecord } from './EwayRecord';
import { useConfirm } from './ConfirmDialog';
import { confirmWording } from '../../../shared/confirm';

/** A refusal as the desk shows it: what the server said, and its problem code. */
function problemText(e: unknown): string {
  const problem = e as ApiProblem;
  return problem.code ? `${problem.message} (${problem.code})` : problem.message;
}

const ACTION_DONE: Record<EwayAction, string> = {
  PART_B: 'Part-B updated.',
  EXTEND: 'Validity extended.',
  CANCEL: 'E-way bill cancelled.',
  CHANGE_VEHICLE: 'Vehicle changed.',
};

/**
 * The e-way bill desk (statutory, per consignment): the bills with their validity and Part-B vehicle, those
 * about to lapse, a lookup by bill number, generating a consignment's bill through the unit's adapter, and
 * Part-B for a whole trip with one outcome per consignment. Every refusal shows its problem code.
 */
export function EwayBillDesk() {
  const { gateway, can } = useSession();
  const confirm = useConfirm();
  const manage = can('EWAY_BILL_MANAGE');

  const [filter, setFilter] = useState<EwayFilter>({ status: '', q: '' });
  const [rows, setRows] = useState<EwayBillRow[]>([]);
  const [expiring, setExpiring] = useState<EwayBillRow[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState('');
  const [note, setNote] = useState('');
  const [selected, setSelected] = useState<EwayBillRow | null>(null);

  const [lookupNo, setLookupNo] = useState('');
  const [found, setFound] = useState<EwayBillRow | null>(null);
  const [lookupMissing, setLookupMissing] = useState(false);

  const [draft, setDraft] = useState<EwayGenerateDraft>({ consignmentId: '', documentRef: '', distanceKm: '', vehicleNo: '' });
  const [consignmentBills, setConsignmentBills] = useState<EwayBillRow[]>([]);

  const [vehicle, setVehicle] = useState('');
  const [reason, setReason] = useState('');
  const [tripId, setTripId] = useState('');
  const [tripVehicle, setTripVehicle] = useState('');
  const [tripResult, setTripResult] = useState<EwayPartBResult | null>(null);

  /** Reads the list and the expiring-soon list again. */
  async function load(next: EwayFilter = filter) {
    setError('');
    try {
      setRows((await gateway.ewayBills(next)).rows);
      setExpiring((await gateway.expiringEwayBills()).rows);
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
      setSelected(await gateway.ewayBill(id));
    } catch (e) {
      setError(problemText(e));
    }
  }

  async function lookup(event: FormEvent) {
    event.preventDefault();
    setError('');
    setFound(null);
    setLookupMissing(false);
    if (!lookupNo.trim()) {
      return;
    }
    try {
      setFound(await gateway.ewayBillByNumber(lookupNo));
    } catch (e) {
      if ((e as ApiProblem).status === 404) {
        setLookupMissing(true);
      } else {
        setError(problemText(e));
      }
    }
  }

  async function showConsignment(event?: FormEvent) {
    event?.preventDefault();
    setError('');
    if (!draft.consignmentId.trim()) {
      setError('Enter the consignment id.');
      return;
    }
    try {
      setConsignmentBills(await gateway.consignmentEwayBills(draft.consignmentId));
    } catch (e) {
      setError(problemText(e));
    }
  }

  async function generate() {
    setError('');
    setNote('');
    if (!draft.consignmentId.trim()) {
      setError('Enter the consignment id.');
      return;
    }
    try {
      const id = await gateway.generateEwayBill(draft);
      await load();
      await showConsignment();
      await open(id);
      setNote('E-way bill generated.');
    } catch (e) {
      setError(problemText(e));
    }
  }

  async function act(bill: EwayBillRow, action: EwayAction, why = reason) {
    setError('');
    setNote('');
    try {
      setSelected(await gateway.actOnEwayBill(bill.id, action, why, vehicle));
      setNote(ACTION_DONE[action]);
      await load();
      if (draft.consignmentId.trim()) {
        await showConsignment();
      }
    } catch (e) {
      setError(problemText(e));
    }
  }

  async function tripPartB(event: FormEvent) {
    event.preventDefault();
    setError('');
    setTripResult(null);
    if (!tripId.trim()) {
      setError('Enter the trip id.');
      return;
    }
    try {
      setTripResult(await gateway.tripEwayPartB(tripId, tripVehicle));
      await load();
    } catch (e) {
      setError(problemText(e));
    }
  }

  return (
    <>
      <PageHeader title="E-way bills" />
      <p className="muted">Generate a consignment's bill, put a vehicle on Part-B, extend or cancel it, and watch what is about to lapse.</p>

      <form className="inline" data-testid="eway-filter" onSubmit={(ev) => { ev.preventDefault(); void load(); }}>
        <select aria-label="Status" data-testid="eway-filter-status" value={filter.status}
          onChange={(ev) => setFilter((f) => ({ ...f, status: ev.target.value }))}>
          <option value="">Any status</option>
          {EWAY_STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <input name="q" placeholder="Bill number or consignment" aria-label="Search e-way bills" data-testid="eway-filter-q"
          value={filter.q} onChange={(ev) => setFilter((f) => ({ ...f, q: ev.target.value }))} />
        <button type="submit" data-testid="eway-filter-apply">Show</button>
      </form>
      {error && <ErrorBanner message={error} testId="eway-error" />}
      {note && <p className="ok" role="status" data-testid="eway-note">{note}</p>}

      <div className="table-wrap"><table data-testid="eway-list">
        <thead><tr><th>Bill</th><th>Consignment</th><th>Status</th><th>Valid until</th><th>Vehicle</th><th /></tr></thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id} data-testid="eway-row" data-id={r.id}>
              <td data-testid="eway-row-no">{r.billNo}</td>
              <td data-testid="eway-row-consignment">{r.trackingRef}</td>
              <td><StatusBadge data-testid="eway-row-status" value={r.status} /></td>
              <td data-testid="eway-row-valid-until">{r.validUntil}</td>
              <td data-testid="eway-row-vehicle">{r.vehicleNo || '—'}</td>
              <td><button className="link" data-testid="eway-row-open" onClick={() => void open(r.id)}>Open</button></td>
            </tr>
          ))}
        </tbody>
      </table></div>
      {rows.length === 0 && loaded && <p className="muted" data-testid="eway-none">No e-way bills.</p>}

      <h2>Expiring soon</h2>
      <div className="table-wrap"><table data-testid="eway-expiring">
        <thead><tr><th>Bill</th><th>Consignment</th><th>Valid until</th><th /></tr></thead>
        <tbody>
          {expiring.map((r) => (
            <tr key={r.id} data-testid="eway-expiring-row" data-id={r.id}>
              <td data-testid="eway-expiring-no">{r.billNo}</td>
              <td>{r.trackingRef}</td>
              <td data-testid="eway-expiring-valid-until">{r.validUntil}</td>
              <td><button className="link" data-testid="eway-expiring-open" onClick={() => void open(r.id)}>Open</button></td>
            </tr>
          ))}
        </tbody>
      </table></div>
      {expiring.length === 0 && loaded && <p className="muted" data-testid="eway-expiring-none">Nothing is about to lapse.</p>}

      <h2>Find a consignment by bill number</h2>
      <form className="inline" data-testid="eway-lookup-form" onSubmit={(ev) => void lookup(ev)}>
        <input name="lookupNo" placeholder="Bill number" aria-label="Bill number" data-testid="eway-lookup-no"
          value={lookupNo} onChange={(ev) => setLookupNo(ev.target.value)} />
        <button type="submit" data-testid="eway-lookup">Find</button>
      </form>
      {found && (
        <p data-testid="eway-lookup-result" data-id={found.id}>
          Bill <span data-testid="eway-lookup-bill">{found.billNo}</span> covers consignment{' '}
          <span data-testid="eway-lookup-consignment" data-id={found.consignmentId}>{found.trackingRef}</span>
          {' '}(<StatusBadge data-testid="eway-lookup-status" value={found.status} />){' '}
          <button className="link" data-testid="eway-lookup-open" onClick={() => void open(found.id)}>Open</button>
        </p>
      )}
      {lookupMissing && <p className="muted" data-testid="eway-lookup-missing">No bill has that number.</p>}

      <h2>A consignment's bills</h2>
      <form className="inline" data-testid="eway-consignment-form" onSubmit={(ev) => void showConsignment(ev)}>
        <EntityPicker kind="consignment" placeholder="Consignment id" ariaLabel="Consignment id" testId="eway-consignment-id"
          value={draft.consignmentId} onChange={(id) => setDraft((d) => ({ ...d, consignmentId: id }))} />
        <button type="submit" data-testid="eway-consignment-show">Show bills</button>
        {manage && (
          <>
            <input name="distance" inputMode="numeric" placeholder="Distance (km)" aria-label="Distance in kilometres"
              data-testid="eway-generate-distance" value={draft.distanceKm}
              onChange={(ev) => setDraft((d) => ({ ...d, distanceKm: ev.target.value }))} />
            <input name="document" placeholder="Document (optional)" aria-label="Document reference"
              data-testid="eway-generate-document" value={draft.documentRef}
              onChange={(ev) => setDraft((d) => ({ ...d, documentRef: ev.target.value }))} />
            <button type="button" data-testid="eway-generate" onClick={() => void generate()}>Generate bill</button>
          </>
        )}
      </form>
      <div className="table-wrap"><table data-testid="eway-consignment-bills">
        <thead><tr><th>Bill</th><th>Status</th><th>Valid until</th><th /></tr></thead>
        <tbody>
          {consignmentBills.map((r) => (
            <tr key={r.id} data-testid="eway-consignment-bill" data-id={r.id}>
              <td data-testid="eway-consignment-bill-no">{r.billNo}</td>
              <td><StatusBadge data-testid="eway-consignment-bill-status" value={r.status} /></td>
              <td className="nowrap">{r.validUntil}</td>
              <td><button className="link" data-testid="eway-consignment-bill-open" onClick={() => void open(r.id)}>Open</button></td>
            </tr>
          ))}
        </tbody>
      </table></div>

      {selected && (
        <section data-testid="eway-detail" data-id={selected.id}>
          <h2>E-way bill <span data-testid="eway-detail-no">{selected.billNo}</span></h2>
          <dl>
            <div><dt>Status</dt><dd><StatusBadge data-testid="eway-detail-status" value={selected.status} /></dd></div>
            <div><dt>Consignment</dt><dd data-testid="eway-detail-consignment" data-id={selected.consignmentId}>{selected.trackingRef}</dd></div>
            <div><dt>Valid until</dt><dd data-testid="eway-detail-valid-until">{selected.validUntil}</dd></div>
            <div><dt>Part-B vehicle</dt><dd data-testid="eway-detail-vehicle">{selected.vehicleNo || '—'}</dd></div>
            <div><dt>Extensions</dt><dd data-testid="eway-detail-extensions">{selected.extensionCount}</dd></div>
            {selected.expiringSoon && <div><dt>Expiry</dt><dd data-testid="eway-detail-expiring">Expiring soon</dd></div>}
            {selected.cancelReason && (
              <div><dt>Cancelled because</dt><dd data-testid="eway-detail-cancel-reason">{selected.cancelReason}</dd></div>
            )}
          </dl>
          {manage && (selected.status === 'ACTIVE' || selected.status === 'EXTENDED') && (
            <>
              <form className="inline" data-testid="eway-vehicle-form"
                onSubmit={(ev) => { ev.preventDefault(); void act(selected, 'PART_B'); }}>
                <input name="vehicle" placeholder="Vehicle number" aria-label="Vehicle number" data-testid="eway-vehicle"
                  value={vehicle} onChange={(ev) => setVehicle(ev.target.value)} />
                <button type="submit" data-testid="eway-part-b">Update Part-B</button>
                <button type="button" data-testid="eway-change-vehicle" onClick={() => void act(selected, 'CHANGE_VEHICLE')}>Change vehicle</button>
              </form>
              <form className="inline" data-testid="eway-reason-form" onSubmit={(ev) => ev.preventDefault()}>
                <input name="reason" placeholder="Reason" aria-label="Reason" data-testid="eway-reason"
                  value={reason} onChange={(ev) => setReason(ev.target.value)} />
                <button type="button" data-testid="eway-extend" onClick={() => void act(selected, 'EXTEND')}>Extend validity</button>
                <button type="button" data-testid="eway-cancel" onClick={() => confirm.ask({
                  wording: confirmWording('Cancel bill', selected.billNo),
                  reason: { label: 'Reason', testId: 'eway-cancel-reason', placeholder: 'Reason' },
                  run: (why) => act(selected, 'CANCEL', why),
                })}>Cancel bill</button>
              </form>
            </>
          )}
        </section>
      )}

      {manage && (
        <>
          <EwayRecord onRecorded={() => void load()} />
          <h2>Part-B for a trip</h2>
          <form className="inline" data-testid="eway-trip-form" onSubmit={(ev) => void tripPartB(ev)}>
            <EntityPicker kind="trip" placeholder="Trip id" ariaLabel="Trip id" testId="eway-trip-id"
              value={tripId} onChange={setTripId} />
            <input name="tripVehicle" placeholder="Vehicle (blank: the trip's)" aria-label="Trip vehicle number"
              data-testid="eway-trip-vehicle" value={tripVehicle} onChange={(ev) => setTripVehicle(ev.target.value)} />
            <button type="submit" data-testid="eway-trip-part-b">Update Part-B for the trip</button>
          </form>
          {tripResult && (
            <>
              <p data-testid="eway-trip-summary">
                {tripResult.updated} of {tripResult.items.length} consignments updated for vehicle {tripResult.vehicleNo}.
              </p>
              <div className="table-wrap"><table data-testid="eway-trip-results">
                <thead><tr><th>Consignment</th><th>Bill</th><th>Outcome</th><th>Code</th></tr></thead>
                <tbody>
                  {tripResult.items.map((i) => (
                    <tr key={i.consignmentId} data-testid="eway-trip-item" data-id={i.consignmentId}>
                      <td>{i.trackingRef}</td>
                      <td>{i.billNo || '—'}</td>
                      <td><StatusBadge data-testid="eway-trip-item-outcome" value={i.outcome} /></td>
                      <td data-testid="eway-trip-item-code">{i.code || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table></div>
            </>
          )}
        </>
      )}
      {confirm.dialog}
    </>
  );
}
