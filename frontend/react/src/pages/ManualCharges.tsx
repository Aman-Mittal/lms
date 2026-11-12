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
  CHARGE_SIDES, CHARGE_SUBJECT_TYPES, type ChargeEntryView, type ChargeSideChoice, type ChargeSubjectType,
  type ManualChargeView,
} from '../gateway/lmsGateway';
import { useSession } from '../session';
import { money, problemText } from './moneyProblem';

const STATUSES = ['PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CANCELLED'];

/**
 * Charges raised by hand against a trip, consignment or order. A charge above the tenant's threshold
 * waits for an approval; one not yet billed can be cancelled, with a reason.
 */
export function ManualCharges() {
  const { gateway, can } = useSession();
  const [catalogue, setCatalogue] = useState<ChargeEntryView[]>([]);
  const [rows, setRows] = useState<ManualChargeView[]>([]);
  const [status, setStatus] = useState('');
  const [error, setError] = useState('');
  const [result, setResult] = useState('');
  const [reload, setReload] = useState(0);

  const [catalogueId, setCatalogueId] = useState('');
  const [subjectType, setSubjectType] = useState<ChargeSubjectType>('TRIP');
  const [subject, setSubject] = useState('');
  const [direction, setDirection] = useState<ChargeSideChoice | ''>('');
  const [customer, setCustomer] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [rate, setRate] = useState('');
  const [reason, setReason] = useState('');
  const [evidence, setEvidence] = useState('');

  const [cancelling, setCancelling] = useState('');
  const [cancelReason, setCancelReason] = useState('');

  useEffect(() => {
    let ignore = false;
    gateway.listChargeCatalogue({ active: true, q: '' })
      .then((entries) => { if (!ignore) { setCatalogue(entries); } })
      .catch((e: unknown) => { if (!ignore) { setError(problemText(e)); } });
    return () => { ignore = true; };
  }, [gateway]);

  useEffect(() => {
    let ignore = false;
    gateway.listManualCharges({ status, tripId: '' })
      .then((list) => { if (!ignore) { setRows(list); } })
      .catch((e: unknown) => { if (!ignore) { setError(problemText(e)); } });
    return () => { ignore = true; };
  }, [gateway, status, reload]);

  const chosen = catalogue.find((c) => c.id === catalogueId);

  function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    setResult('');
    gateway.recordManualCharge({
      catalogueId, subjectType, subjectId: subject, direction, customerId: customer, quantity, rate, reason,
      evidenceRef: evidence,
    }).then((r) => {
      setResult(r.pending ? 'Awaiting approval: the amount is above the approval threshold.' : 'Raised.');
      setReason('');
      setEvidence('');
      setReload((n) => n + 1);
    }).catch((e: unknown) => setError(problemText(e)));
  }

  function cancel(event: FormEvent, id: string) {
    event.preventDefault();
    setError('');
    gateway.cancelManualCharge(id, cancelReason).then(() => {
      setCancelling('');
      setReload((n) => n + 1);
    }).catch((e: unknown) => setError(problemText(e)));
  }

  return (
    <>
      <h1>Manual charges</h1>
      <p className="muted">Charges raised by hand against a trip, consignment or order, billed to the customer or paid to the vendor.</p>
      {error && <p className="error" role="alert" data-testid="mc-error">{error}</p>}

      {can('MANUAL_CHARGE_RECORD') && (
        <form data-testid="mc-form" onSubmit={submit}>
          <h2>Raise a charge</h2>
          <select aria-label="Charge" data-testid="mc-catalogue" required value={catalogueId}
            onChange={(e) => setCatalogueId(e.target.value)}>
            <option value="">Charge</option>
            {catalogue.map((c) => <option key={c.id} value={c.id}>{`${c.code} - ${c.name}`}</option>)}
          </select>
          <select aria-label="Subject type" data-testid="mc-subject-type" value={subjectType}
            onChange={(e) => setSubjectType(e.target.value as ChargeSubjectType)}>
            {CHARGE_SUBJECT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
          <input placeholder="Trip, consignment or order id" aria-label="Subject id" data-testid="mc-subject" required
            value={subject} onChange={(e) => setSubject(e.target.value)} />
          <select aria-label="Direction" data-testid="mc-direction" value={direction}
            onChange={(e) => setDirection(e.target.value as ChargeSideChoice | '')}>
            <option value="">Direction (from the charge)</option>
            {CHARGE_SIDES.map((d) => <option key={d} value={d}>{d}</option>)}
          </select>
          <input placeholder="Customer id (optional)" aria-label="Customer id" data-testid="mc-customer"
            value={customer} onChange={(e) => setCustomer(e.target.value)} />
          <input type="number" min="0" step="any" placeholder="Quantity" aria-label="Quantity" data-testid="mc-quantity"
            required value={quantity} onChange={(e) => setQuantity(e.target.value)} />
          <input type="number" min="0" step="0.01" aria-label="Rate" data-testid="mc-rate"
            placeholder={chosen ? `Rate (default ${money(chosen.defaultRate)})` : 'Rate (optional)'}
            value={rate} onChange={(e) => setRate(e.target.value)} />
          <input placeholder="Why" aria-label="Reason" data-testid="mc-reason" required value={reason}
            onChange={(e) => setReason(e.target.value)} />
          <input placeholder="Evidence reference" aria-label="Evidence reference" data-testid="mc-evidence"
            value={evidence} onChange={(e) => setEvidence(e.target.value)} />
          <button type="submit" data-testid="mc-submit">Raise charge</button>
          {result && <p className="ok" role="status" data-testid="mc-result">{result}</p>}
        </form>
      )}

      <h2>Charges raised</h2>
      <select aria-label="Status filter" data-testid="mc-filter-status" value={status}
        onChange={(e) => setStatus(e.target.value)}>
        <option value="">All statuses</option>
        {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
      </select>
      {rows.length === 0 ? (
        <p className="muted">No charges.</p>
      ) : (
        <table data-testid="mc-list">
          <thead>
            <tr><th>No.</th><th>Charge</th><th>Against</th><th>Direction</th><th>Amount</th><th>Status</th><th>Billed</th><th /></tr>
          </thead>
          <tbody>
            {rows.map((m) => (
              <tr key={m.id} data-testid="mc-row" data-id={m.id}>
                <td>{m.chargeNo}</td><td>{m.chargeCode}</td><td>{`${m.subjectType} ${m.subjectRef}`}</td>
                <td data-testid="mc-direction-cell">{m.direction}</td>
                <td data-testid="mc-amount">{money(m.amount)}</td>
                <td><span className="badge" data-testid="mc-status">{m.status}</span></td>
                <td>{m.billed ? 'Yes' : 'No'}</td>
                <td>
                  {can('MANUAL_CHARGE_RECORD') && !m.billed && (m.status === 'PENDING_APPROVAL' || m.status === 'APPROVED') && (
                    cancelling === m.id ? (
                      <form className="inline" onSubmit={(e) => cancel(e, m.id)}>
                        <input placeholder="Why" aria-label="Cancel reason" data-testid="mc-cancel-reason" required
                          value={cancelReason} onChange={(e) => setCancelReason(e.target.value)} />
                        <button type="submit" data-testid="mc-cancel-confirm">Cancel charge</button>
                        <button type="button" className="link" onClick={() => setCancelling('')}>Keep</button>
                      </form>
                    ) : (
                      <button type="button" className="link" data-testid="mc-cancel" aria-label={`Cancel ${m.chargeNo}`}
                        onClick={() => { setCancelling(m.id); setCancelReason(''); }}>Cancel</button>
                    )
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
