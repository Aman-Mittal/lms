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
import type { EwayRecordDraft } from '../gateway/smallOpsTypes';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { ErrorBanner } from './ErrorBanner';

const BLANK: EwayRecordDraft = {
  consignmentId: '', billNo: '', generatedAt: '', validUntil: '', vehicleNo: '', distanceKm: '',
};

/**
 * Writes down a bill that was generated outside the platform (on the statutory portal, say) against its
 * consignment, with the validity it carries, so the desk watches it like any other.
 */
export function EwayRecord({ onRecorded }: { onRecorded: () => void }) {
  const { gateway } = useSession();
  const [draft, setDraft] = useState<EwayRecordDraft>(BLANK);
  const [done, setDone] = useState('');
  const [error, setError] = useState({ message: '', code: '' });
  const set = <K extends keyof EwayRecordDraft>(key: K, value: EwayRecordDraft[K]) => setDraft((d) => ({ ...d, [key]: value }));

  async function submit(event: FormEvent) {
    event.preventDefault();
    setDone('');
    setError({ message: '', code: '' });
    try {
      const billNo = draft.billNo.trim();
      await gateway.recordEwayBill(draft);
      setDraft(BLANK);
      setDone(billNo);
      onRecorded();
    } catch (e) {
      setError({ message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' });
    }
  }

  return (
    <>
      <h2>Record a bill issued elsewhere</h2>
      <form className="inline" data-testid="eway-record-form" onSubmit={(ev) => void submit(ev)}>
        <EntityPicker kind="consignment" placeholder="Consignment id" ariaLabel="Recorded bill consignment id"
          testId="eway-record-consignment" required value={draft.consignmentId} onChange={(v) => set('consignmentId', v)} />
        <input placeholder="Bill number" aria-label="Recorded bill number" data-testid="eway-record-no" required
          value={draft.billNo} onChange={(e) => set('billNo', e.target.value)} />
        <input type="datetime-local" aria-label="Recorded bill generated at" data-testid="eway-record-generated"
          value={draft.generatedAt} onChange={(e) => set('generatedAt', e.target.value)} />
        <input type="datetime-local" aria-label="Recorded bill valid until" data-testid="eway-record-valid-until" required
          value={draft.validUntil} onChange={(e) => set('validUntil', e.target.value)} />
        <input placeholder="Vehicle number (optional)" aria-label="Recorded bill vehicle number"
          data-testid="eway-record-vehicle" value={draft.vehicleNo} onChange={(e) => set('vehicleNo', e.target.value)} />
        <input inputMode="numeric" placeholder="Distance (km, optional)" aria-label="Recorded bill distance in kilometres"
          data-testid="eway-record-distance" value={draft.distanceKm} onChange={(e) => set('distanceKm', e.target.value)} />
        <button type="submit" data-testid="eway-record-submit">Record issued bill</button>
      </form>
      {done && <p className="ok" role="status" data-testid="eway-record-done">Bill {done} recorded.</p>}
      {error.message && <ErrorBanner message={error.message} code={error.code} testId="eway-record-error" codeTestId="eway-record-refusal" />}
    </>
  );
}
