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
import { ApiProblem } from '../gateway/lmsGateway';
import type { UploadOutcome } from '../gateway/enquiryTypes';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';

/**
 * Orders from a spreadsheet: choose a customer and a CSV or XLSX file, and see what became of every row.
 * Rows that share an order reference make one order; a row that cannot be read fails its own order only.
 * Sending the same file again creates nothing, and says so row by row.
 */
export function OrderUpload() {
  const { gateway, auth } = useSession();
  const [customer, setCustomer] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [outcome, setOutcome] = useState<UploadOutcome | null>(null);

  async function send(event: FormEvent) {
    event.preventDefault();
    const orgUnitId = auth?.orgUnitId ?? '';
    if (!file || !orgUnitId) {
      setError('Choose a file to upload.');
      return;
    }
    setBusy(true);
    setError('');
    setOutcome(null);
    try {
      setOutcome(await gateway.uploadOrderFile(orgUnitId, customer, file));
    } catch (e) {
      const problem = e as ApiProblem;
      setError(problem.code ? `${problem.message} (${problem.code})` : problem.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h1>Bulk order upload</h1>
      <p className="muted">
        One row is one order line. Columns are the customer&apos;s own where a column mapping is set, else
        order_ref, origin, destination, pickup_date, delivery_date, load_type, material_code, material, quantity,
        uom, weight_kg and consignee.
      </p>
      <form className="inline" onSubmit={(ev) => void send(ev)} data-testid="upload-form">
        <EntityPicker kind="customer" placeholder="Customer" ariaLabel="Customer of the file" testId="upload-customer"
          value={customer} onChange={setCustomer} required />
        <input type="file" name="file" accept=".csv,.xlsx" aria-label="Order file" data-testid="upload-file"
          onChange={(ev) => setFile(ev.target.files && ev.target.files.length > 0 ? ev.target.files[0] : null)} />
        <button type="submit" data-testid="upload-submit" disabled={busy}>Upload</button>
      </form>
      {error && <p className="error" role="alert" data-testid="upload-error">{error}</p>}
      {outcome && (
        <>
          <p role="status" data-testid="upload-summary">
            <span data-testid="upload-created">{outcome.created}</span> created,{' '}
            <span data-testid="upload-duplicates">{outcome.duplicates}</span> already there,{' '}
            <span data-testid="upload-failed">{outcome.failed}</span> failed.
          </p>
          <table data-testid="upload-rows">
            <thead><tr><th>Row</th><th>Order reference</th><th>Result</th><th>Order</th><th>Reasons</th></tr></thead>
            <tbody>
              {outcome.rows.map((r) => (
                <tr key={r.row} data-testid="upload-row" data-id={r.row}>
                  <td data-testid="upload-row-number">{r.row}</td>
                  <td data-testid="upload-row-ref">{r.orderRef}</td>
                  <td data-testid="upload-row-status">{r.status}</td>
                  <td data-testid="upload-row-order" data-id={r.orderId}>{r.orderId ? 'Made' : ''}</td>
                  <td data-testid="upload-row-reasons">{r.reasons.join('; ')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </>
  );
}
