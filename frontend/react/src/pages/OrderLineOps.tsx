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
import { ApiProblem, MATERIAL_CLASSES, type OrderLineDraft, type OrderLineView } from '../gateway/lmsGateway';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { ErrorBanner } from './ErrorBanner';
import { useConfirm } from './ConfirmDialog';
import { confirmWording } from '../../../shared/confirm';

interface Refused {
  message: string;
  code: string;
}

const NONE: Refused = { message: '', code: '' };

/** Once consignments are cut from an order, a line can still be cancelled on its own. */
const CANCELLABLE = ['VALIDATED', 'PARTIALLY_PLANNED', 'FULLY_PLANNED'];

function blankLine(lines: OrderLineView[]): OrderLineDraft {
  const last = lines[lines.length - 1];
  return {
    lineNo: lines.reduce((max, l) => Math.max(max, l.lineNo), 0) + 1, materialCode: '', materialDescription: '',
    materialClass: 'GENERAL', hazmatUnCode: '', quantity: '1', uom: 'EA', deadWeightKg: '', lengthM: '', widthM: '',
    heightM: '', volumetricDivisor: '', consigneePartnerId: last?.consigneePartnerId ?? '',
    destinationTerminalId: last?.destinationTerminalId ?? '',
  };
}

/**
 * Two single-line operations on the order desk: appending one line to a draft without restating the others, and
 * cancelling one line of a validated order (with the consignment it is on) under the order's version.
 */
export function OrderLineOps({ orderId, version, status, lines, onChanged }: {
  orderId: string; version: string; status: string; lines: OrderLineView[]; onChanged: () => void;
}) {
  const { gateway, can } = useSession();
  const [typed, setTyped] = useState<OrderLineDraft | null>(null);
  const confirm = useConfirm();
  const [done, setDone] = useState('');
  const [error, setError] = useState<Refused>(NONE);
  const draft = typed ?? blankLine(lines);
  const set = <K extends keyof OrderLineDraft>(key: K, value: OrderLineDraft[K]) => setTyped({ ...draft, [key]: value });
  const canAppend = status === 'DRAFT' && can('ORDER_UPDATE');
  const cancellable = can('ORDER_UPDATE') && CANCELLABLE.includes(status) ? lines.filter((l) => !l.cancelled && !l.closeKind) : [];

  function fail(e: unknown) {
    setError({ message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' });
  }

  async function append(event: FormEvent) {
    event.preventDefault();
    setDone('');
    setError(NONE);
    try {
      await gateway.addOrderLine(orderId, draft);
      setTyped(null);
      setDone(`Line ${draft.lineNo} appended.`);
      onChanged();
    } catch (e) {
      fail(e);
    }
  }

  async function cancelLine(line: OrderLineView, reason: string) {
    setDone('');
    setError(NONE);
    try {
      const result = await gateway.cancelOrderLine(orderId, version, line.id, reason);
      setDone(`Line ${line.lineNo} cancelled; ${result.cancelledConsignments} consignment(s) cancelled.`);
      onChanged();
    } catch (e) {
      fail(e);
    }
  }

  return (
    <>
      {canAppend && (
        <form className="inline" data-testid="append-line" onSubmit={(ev) => void append(ev)}>
          <h3>Append one line</h3>
          <input type="number" min="1" aria-label="Appended line number" data-testid="append-line-no" required
            value={draft.lineNo} onChange={(e) => set('lineNo', Number(e.target.value))} />
          <input placeholder="Material code" aria-label="Appended material code" data-testid="append-material" required
            value={draft.materialCode} onChange={(e) => set('materialCode', e.target.value)} />
          <input placeholder="Description" aria-label="Appended material description" data-testid="append-description"
            required value={draft.materialDescription} onChange={(e) => set('materialDescription', e.target.value)} />
          <select aria-label="Appended material class" data-testid="append-class" value={draft.materialClass}
            onChange={(e) => set('materialClass', e.target.value)}>
            {MATERIAL_CLASSES.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
          <input inputMode="decimal" placeholder="Quantity" aria-label="Appended quantity" data-testid="append-quantity"
            required value={draft.quantity} onChange={(e) => set('quantity', e.target.value)} />
          <input placeholder="Unit" aria-label="Appended unit" data-testid="append-uom" required value={draft.uom}
            onChange={(e) => set('uom', e.target.value)} />
          <input inputMode="decimal" placeholder="Weight kg" aria-label="Appended weight kg" data-testid="append-weight"
            required value={draft.deadWeightKg} onChange={(e) => set('deadWeightKg', e.target.value)} />
          <EntityPicker kind="terminal" placeholder="Destination terminal" ariaLabel="Appended destination terminal id"
            testId="append-destination" required value={draft.destinationTerminalId}
            onChange={(v) => set('destinationTerminalId', v)} />
          <EntityPicker kind="partner" placeholder="Consignee (optional)" ariaLabel="Appended consignee id"
            testId="append-consignee" value={draft.consigneePartnerId} onChange={(v) => set('consigneePartnerId', v)} />
          <button type="submit" data-testid="append-submit">Append line</button>
        </form>
      )}
      {cancellable.length > 0 && (
        <section data-testid="cancel-lines" aria-label="Cancel a line">
          <h3>Cancel a line</h3>
          <p className="muted">
            Cancels the line and the consignment it is on. The order&apos;s last live line cannot be cancelled: cancel or
            close the order instead.
          </p>
          <div className="inline">
            {cancellable.map((l) => (
              <button key={l.id} type="button" data-testid="cancel-line" data-line={l.lineNo} onClick={() => confirm.ask({
                wording: confirmWording('Cancel line', String(l.lineNo), 'The consignment it is on is cancelled too.'),
                reason: { label: 'Why a line is cancelled', testId: 'cancel-line-reason', placeholder: 'Why (optional)' },
                run: (why) => cancelLine(l, why),
              })}>
                Cancel line {l.lineNo}
              </button>
            ))}
          </div>
        </section>
      )}
      {confirm.dialog}
      {done && <p className="ok" role="status" data-testid="line-op-done">{done}</p>}
      {error.message && <ErrorBanner message={error.message} code={error.code} testId="line-op-error" codeTestId="line-op-refusal" />}
    </>
  );
}
