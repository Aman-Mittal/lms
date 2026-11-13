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
  ApiProblem, POD_DOCUMENT_TYPES, provesDelivery, type DeliveryProofView, type PodDocumentType, type PodLineDraft,
} from '../gateway/lmsGateway';
import { useSession } from '../session';

/** A refusal as the desk shows it: what the server said, and its problem code. */
function problemText(e: unknown): string {
  const problem = e as ApiProblem;
  return problem.code ? `${problem.message} (${problem.code})` : problem.message;
}

/** What is typed against one order line; blank is not fed. */
interface LineFields {
  received: string;
  short: string;
  damaged: string;
  excess: string;
}

const NOT_FED: LineFields = { received: '', short: '', damaged: '', excess: '' };

/**
 * The proof of delivery desk: start a consignment's draft, feed it with what
 * was received against each order line, list its documents and unloading
 * times, then submit it with the consignee's code or key it from paper. A
 * submitted proof is changed only by asking for its reversal.
 */
export function PodDesk() {
  const { gateway, can } = useSession();
  const record = can('POD_RECORD');
  const paperKey = can('POD_PAPER_KEY');
  const reverse = can('POD_REVERSE');

  const [typed, setTyped] = useState('');
  const [consignmentId, setConsignmentId] = useState('');
  const [proof, setProof] = useState<DeliveryProofView | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState('');
  const [lines, setLines] = useState<Record<string, LineFields>>({});

  const [docType, setDocType] = useState<PodDocumentType>('FRONT');
  const [docRef, setDocRef] = useState('');
  const [reportedAt, setReportedAt] = useState('');
  const [startedAt, setStartedAt] = useState('');
  const [endedAt, setEndedAt] = useState('');
  const [timingsSaved, setTimingsSaved] = useState(false);
  const [otp, setOtp] = useState('');
  const [otpShown, setOtpShown] = useState('');
  const [recipient, setRecipient] = useState('');
  const [remark, setRemark] = useState('');
  const [paperRecipient, setPaperRecipient] = useState('');
  const [paperRemark, setPaperRemark] = useState('');
  const [notReceivedRemark, setNotReceivedRemark] = useState('');
  const [reversalReason, setReversalReason] = useState('');
  const [reversal, setReversal] = useState('');

  const proves = provesDelivery(proof?.status);
  /** A proof is fed and submitted only while it is a draft, by someone who may record. */
  const editable = record && !proves;

  async function refresh(id: string) {
    try {
      const read = await gateway.deliveryProof(id);
      setProof(read);
      const fed: Record<string, LineFields> = {};
      for (const l of read?.lines ?? []) {
        fed[l.orderLineId] = { received: l.receivedQty, short: l.shortQty, damaged: l.damagedQty, excess: l.excessQty };
      }
      setLines(fed);
      setLoaded(true);
    } catch (e) {
      setLoaded(false);
      setError(problemText(e));
    }
  }

  /** Runs a change, then reads the proof again so the desk shows what the server holds. */
  async function act(change: () => Promise<unknown>) {
    setError('');
    try {
      await change();
      await refresh(consignmentId);
      return true;
    } catch (e) {
      setError(problemText(e));
      return false;
    }
  }

  async function lookUp(event: FormEvent) {
    event.preventDefault();
    const id = typed.trim();
    setConsignmentId(id);
    setOtpShown('');
    setReversal('');
    setTimingsSaved(false);
    setError('');
    await refresh(id);
  }

  const field = (id: string): LineFields => lines[id] ?? NOT_FED;
  const setField = (id: string, which: keyof LineFields, value: string) =>
    setLines((all) => ({ ...all, [id]: { ...(all[id] ?? NOT_FED), [which]: value } }));

  function saveLines() {
    const drafts: PodLineDraft[] = (proof?.lines ?? [])
      .map((l) => ({ orderLineId: l.orderLineId, f: field(l.orderLineId) }))
      .filter(({ f }) => f.received.trim() || f.short.trim() || f.damaged.trim() || f.excess.trim())
      .map(({ orderLineId, f }) => ({
        orderLineId, receivedQty: f.received, shortQty: f.short, damagedQty: f.damaged, excessQty: f.excess,
      }));
    return act(() => gateway.feedProofLines(consignmentId, drafts));
  }

  async function addDocument(event: FormEvent) {
    event.preventDefault();
    const next = [...(proof?.documents ?? []), { type: docType, reference: docRef.trim() }];
    if (await act(() => gateway.setProofDocuments(consignmentId, next))) {
      setDocRef('');
    }
  }

  function removeDocument(index: number) {
    const next = (proof?.documents ?? []).filter((_, i) => i !== index);
    return act(() => gateway.setProofDocuments(consignmentId, next));
  }

  async function saveTimings(event: FormEvent) {
    event.preventDefault();
    setTimingsSaved(false);
    const ok = await act(() => gateway.setProofTimings(consignmentId,
      { reportedAt, unloadingStartedAt: startedAt, unloadingEndedAt: endedAt }));
    setTimingsSaved(ok);
  }

  async function issue() {
    await act(async () => {
      const issued = await gateway.issueDeliveryOtp(consignmentId);
      setOtpShown(issued.otp ?? '');
    });
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    await act(() => gateway.submitProof(consignmentId, { otp, recipientName: recipient, remark }));
  }

  async function keyPaper(event: FormEvent) {
    event.preventDefault();
    await act(() => gateway.keyPaperProof(consignmentId, { recipientName: paperRecipient, remark: paperRemark }));
  }

  async function notReceived(event: FormEvent) {
    event.preventDefault();
    await act(() => gateway.markProofNotReceived(consignmentId, notReceivedRemark.trim()));
  }

  async function askReversal(event: FormEvent) {
    event.preventDefault();
    setError('');
    setReversal('');
    try {
      await gateway.requestProofReversal(consignmentId, reversalReason.trim());
      setReversal('Reversal requested: awaiting approval.');
      setReversalReason('');
    } catch (e) {
      setError(problemText(e));
    }
  }

  return (
    <>
      <h1>Proof of delivery desk</h1>
      <p className="muted">Start the proof, feed it line by line, list the documents and times, then submit it.</p>
      <form className="inline" onSubmit={lookUp} data-testid="pod-lookup">
        <input name="consignment" placeholder="Consignment id" aria-label="Consignment id" data-testid="pod-consignment-id"
          value={typed} onChange={(e) => setTyped(e.target.value)} required />
        <button type="submit">Look up</button>
      </form>
      {error && <p className="error" role="alert" data-testid="pod-error">{error}</p>}
      {consignmentId && loaded && proof === null && (
        <>
          <p className="muted" data-testid="pod-none">No proof has been started for this consignment.</p>
          {record && <p><button data-testid="pod-start" onClick={() => act(() => gateway.ensureProof(consignmentId))}>Start proof</button></p>}
        </>
      )}
      {consignmentId && loaded && proof !== null && (
        <section data-testid="pod-proof">
          <dl>
            <div><dt>Proof</dt><dd data-testid="pod-status">{proof.status}</dd></div>
            {proof.paper && <div><dt>Keyed from paper</dt><dd data-testid="pod-paper">Yes</dd></div>}
            {proof.keyedBy && <div><dt>Keyed by</dt><dd data-testid="pod-keyed-by">{proof.keyedBy}</dd></div>}
            {proves && <div><dt>Received by</dt><dd data-testid="pod-recipient-shown">{proof.recipientName}</dd></div>}
            {proof.remark && <div><dt>Remark</dt><dd data-testid="pod-remark-shown">{proof.remark}</dd></div>}
          </dl>

          <h2>Lines</h2>
          <table data-testid="pod-lines">
            <thead><tr><th>Line</th><th>Material</th><th>Gross</th><th>Received</th><th>Short</th><th>Damaged</th><th>Excess</th></tr></thead>
            <tbody>
              {proof.lines.map((l) => (
                <tr key={l.orderLineId} data-testid="pod-line-row" data-id={l.orderLineId}>
                  <td>{l.lineNo}</td>
                  <td>{l.materialDescription}</td>
                  <td data-testid="pod-line-gross">{l.grossQty} {l.uom}</td>
                  {(['received', 'short', 'damaged', 'excess'] as const).map((which) => (
                    <td key={which}>
                      <input type="text" inputMode="decimal" aria-label={which[0].toUpperCase() + which.slice(1)}
                        data-testid={`pod-line-${which}`} disabled={!editable}
                        value={field(l.orderLineId)[which]} onChange={(e) => setField(l.orderLineId, which, e.target.value)} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          {editable && <p><button data-testid="pod-save-lines" onClick={saveLines}>Save lines</button></p>}

          <h2>Documents</h2>
          <ul data-testid="pod-documents">
            {proof.documents.map((d, i) => (
              <li key={i} data-testid="pod-doc-row"><span data-testid="pod-doc-text">{d.type}: {d.reference}</span>
                {editable && <> <button className="link" data-testid="pod-doc-remove" onClick={() => removeDocument(i)}>Remove</button></>}
              </li>
            ))}
          </ul>
          {editable && (
            <form className="inline" onSubmit={addDocument} data-testid="pod-doc-form">
              <select aria-label="Document type" data-testid="pod-doc-type" value={docType}
                onChange={(e) => setDocType(e.target.value as PodDocumentType)}>
                {POD_DOCUMENT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
              <input name="docRef" placeholder="Reference" aria-label="Document reference" data-testid="pod-doc-ref"
                value={docRef} onChange={(e) => setDocRef(e.target.value)} required />
              <button type="submit" data-testid="pod-doc-add">Add document</button>
            </form>
          )}

          <h2>Times</h2>
          {editable ? (
            <>
              <form className="inline" onSubmit={saveTimings} data-testid="pod-timings">
                <label>Reported <input type="datetime-local" data-testid="pod-reported-at" value={reportedAt}
                  onChange={(e) => setReportedAt(e.target.value)} /></label>
                <label>Unloading started <input type="datetime-local" data-testid="pod-unloading-start" value={startedAt}
                  onChange={(e) => setStartedAt(e.target.value)} /></label>
                <label>Unloading ended <input type="datetime-local" data-testid="pod-unloading-end" value={endedAt}
                  onChange={(e) => setEndedAt(e.target.value)} /></label>
                <button type="submit" data-testid="pod-save-timings">Save times</button>
              </form>
              {timingsSaved && <p className="ok" role="status" data-testid="pod-timings-saved">Times saved.</p>}
            </>
          ) : (
            <p data-testid="pod-timings-shown">{proof.reportedAt || '—'} / {proof.unloadingStartedAt || '—'} / {proof.unloadingEndedAt || '—'}</p>
          )}

          {editable && (
            <>
              <h2>Submit</h2>
              <p><button data-testid="pod-issue-otp" onClick={issue}>Issue a code</button>
                {otpShown && <span data-testid="pod-otp-code"> {otpShown}</span>}</p>
              <form className="inline" onSubmit={submit} data-testid="pod-submit-form">
                <input name="otp" placeholder="Code" aria-label="Code" data-testid="pod-otp" value={otp}
                  onChange={(e) => setOtp(e.target.value)} required />
                <input name="recipient" placeholder="Recipient name" aria-label="Recipient name" data-testid="pod-recipient"
                  value={recipient} onChange={(e) => setRecipient(e.target.value)} required />
                <input name="remark" placeholder="Remark" aria-label="Remark" data-testid="pod-remark"
                  value={remark} onChange={(e) => setRemark(e.target.value)} />
                <button type="submit" data-testid="pod-submit">Submit proof</button>
              </form>
              {paperKey && (
                <form className="inline" onSubmit={keyPaper} data-testid="pod-paper-form">
                  <input name="paperRecipient" placeholder="Recipient name on the paper" aria-label="Paper recipient name"
                    data-testid="pod-paper-recipient" value={paperRecipient} onChange={(e) => setPaperRecipient(e.target.value)} required />
                  <input name="paperRemark" placeholder="Remark" aria-label="Paper remark" data-testid="pod-paper-remark"
                    value={paperRemark} onChange={(e) => setPaperRemark(e.target.value)} />
                  <button type="submit" data-testid="pod-paper-submit">Key from paper</button>
                </form>
              )}
              <form className="inline" onSubmit={notReceived} data-testid="pod-not-received-form">
                <input name="notReceivedRemark" placeholder="Why nothing was received" aria-label="Not received remark"
                  data-testid="pod-not-received-remark" value={notReceivedRemark}
                  onChange={(e) => setNotReceivedRemark(e.target.value)} required />
                <button type="submit" data-testid="pod-not-received">Not received</button>
              </form>
            </>
          )}

          {proves && reverse && (
            <form className="inline" onSubmit={askReversal} data-testid="pod-reverse-form">
              <input name="reversalReason" placeholder="Why the proof is wrong" aria-label="Reason to reverse"
                data-testid="pod-reversal-reason" value={reversalReason} onChange={(e) => setReversalReason(e.target.value)} required />
              <button type="submit" data-testid="pod-request-reversal">Request reversal</button>
            </form>
          )}
          {reversal && <p className="ok" role="status" data-testid="pod-reversal-requested">{reversal}</p>}
        </section>
      )}
    </>
  );
}
