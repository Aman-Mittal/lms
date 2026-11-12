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
  ADVANCE_MODES, type AdvanceModeChoice, type ManualChargeView, type VendorAdvanceView,
} from '../gateway/lmsGateway';
import { useSession } from '../session';
import { money, problemText } from './moneyProblem';

/**
 * A trip's money: the advances paid to its vendor (requested, paid, cancelled before payment) and the
 * manual charges raised against it, read only. Each control is drawn only for a person who holds its right.
 */
export function TripMoney({ tripId }: { tripId: string }) {
  const { gateway, can } = useSession();
  const [advances, setAdvances] = useState<VendorAdvanceView[]>([]);
  const [charges, setCharges] = useState<ManualChargeView[]>([]);
  const [reload, setReload] = useState(0);
  const [error, setError] = useState('');
  const [result, setResult] = useState('');

  const [mode, setMode] = useState<AdvanceModeChoice>('BANK');
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [paying, setPaying] = useState('');
  const [payReference, setPayReference] = useState('');
  const [cancelling, setCancelling] = useState('');
  const [cancelReason, setCancelReason] = useState('');

  const canSee = can('ADVANCE_REQUEST') || can('ADVANCE_APPROVE') || can('PAYABLES_MANAGE') || can('INVOICE_READ');
  const canSeeCharges = can('MANUAL_CHARGE_RECORD') || can('MANUAL_CHARGE_APPROVE') || can('INVOICE_READ');

  useEffect(() => {
    let ignore = false;
    if (canSee) {
      gateway.listVendorAdvances(tripId).then((r) => { if (!ignore) { setAdvances(r); } })
        .catch((e: unknown) => { if (!ignore) { setError(problemText(e)); } });
    }
    if (canSeeCharges) {
      gateway.listManualCharges({ status: '', tripId }).then((r) => { if (!ignore) { setCharges(r); } })
        .catch((e: unknown) => { if (!ignore) { setError(problemText(e)); } });
    }
    return () => { ignore = true; };
  }, [gateway, tripId, reload, canSee, canSeeCharges]);

  function act(action: () => Promise<void>, after?: () => void) {
    setError('');
    action().then(() => { after?.(); setReload((n) => n + 1); }).catch((e: unknown) => setError(problemText(e)));
  }

  function request(event: FormEvent) {
    event.preventDefault();
    setResult('');
    act(async () => {
      const r = await gateway.requestVendorAdvance({ tripId, mode, amount, reason });
      setResult(r.pending ? 'Awaiting approval.' : 'Requested.');
    }, () => { setAmount(''); setReason(''); });
  }

  if (!canSee && !canSeeCharges) {
    return null;
  }
  return (
    <section data-testid="trip-money" aria-label="Trip money">
      <h2>Money</h2>
      {error && <p className="error" role="alert" data-testid="adv-error">{error}</p>}
      {canSee && (
        <>
          <h3>Advances</h3>
          {can('ADVANCE_REQUEST') && (
            <form className="inline" data-testid="adv-form" onSubmit={request}>
              <select aria-label="Advance mode" data-testid="adv-mode" value={mode}
                onChange={(e) => setMode(e.target.value as AdvanceModeChoice)}>
                {ADVANCE_MODES.map((m) => <option key={m} value={m}>{m}</option>)}
              </select>
              <input type="number" min="0" step="0.01" placeholder="Amount" aria-label="Advance amount"
                data-testid="adv-amount" required value={amount} onChange={(e) => setAmount(e.target.value)} />
              <input placeholder="Why" aria-label="Advance reason" data-testid="adv-reason" required value={reason}
                onChange={(e) => setReason(e.target.value)} />
              <button type="submit" data-testid="adv-submit">Request advance</button>
              {result && <span className="ok" role="status" data-testid="adv-result">{result}</span>}
            </form>
          )}
          {advances.length === 0 ? <p className="muted">No advances on this trip.</p> : (
            <table data-testid="advances">
              <thead><tr><th>No.</th><th>Mode</th><th>Amount</th><th>Status</th><th>Reference</th><th /></tr></thead>
              <tbody>
                {advances.map((a) => (
                  <tr key={a.id} data-testid="adv-row" data-id={a.id}>
                    <td>{a.advanceNo}</td><td>{a.mode}</td><td data-testid="adv-amount-cell">{money(a.amount)}</td>
                    <td><span className="badge" data-testid="adv-status">{a.status}</span></td>
                    <td>{a.paidReference}</td>
                    <td>
                      {can('PAYABLES_MANAGE') && a.status === 'APPROVED' && (paying === a.id ? (
                        <form className="inline" onSubmit={(e) => {
                          e.preventDefault();
                          act(() => gateway.payVendorAdvance(a.id, payReference, new Date().toISOString().substring(0, 10)),
                            () => setPaying(''));
                        }}>
                          <input placeholder="Reference" aria-label="Payment reference" data-testid="adv-pay-reference"
                            required value={payReference} onChange={(e) => setPayReference(e.target.value)} />
                          <button type="submit" data-testid="adv-pay-confirm">Record payment</button>
                        </form>
                      ) : (
                        <button type="button" className="link" data-testid="adv-pay" aria-label={`Pay ${a.advanceNo}`}
                          onClick={() => { setPaying(a.id); setPayReference(''); }}>Pay</button>
                      ))}
                      {can('ADVANCE_REQUEST') && (a.status === 'PENDING_APPROVAL' || a.status === 'APPROVED') && (cancelling === a.id ? (
                        <form className="inline" onSubmit={(e) => {
                          e.preventDefault();
                          act(() => gateway.cancelVendorAdvance(a.id, cancelReason), () => setCancelling(''));
                        }}>
                          <input placeholder="Why" aria-label="Cancel reason" data-testid="adv-cancel-reason" required
                            value={cancelReason} onChange={(e) => setCancelReason(e.target.value)} />
                          <button type="submit" data-testid="adv-cancel-confirm">Cancel advance</button>
                        </form>
                      ) : (
                        <button type="button" className="link" data-testid="adv-cancel" aria-label={`Cancel ${a.advanceNo}`}
                          onClick={() => { setCancelling(a.id); setCancelReason(''); }}>Cancel</button>
                      ))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
      {canSeeCharges && (
        <>
          <h3>Manual charges</h3>
          {charges.length === 0 ? <p className="muted">No manual charges on this trip.</p> : (
            <table data-testid="trip-charges">
              <thead><tr><th>No.</th><th>Charge</th><th>Direction</th><th>Amount</th><th>Status</th></tr></thead>
              <tbody>
                {charges.map((c) => (
                  <tr key={c.id} data-testid="trip-charge-row">
                    <td>{c.chargeNo}</td><td>{c.chargeCode}</td><td>{c.direction}</td><td>{money(c.amount)}</td>
                    <td>{c.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  );
}
