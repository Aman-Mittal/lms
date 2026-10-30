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
import type { DeliveryProofView, OtpIssued } from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { Qr } from './Qr';

/** The delivery desk: issue a one-time code, record the proof, print the slip. */
export function DeliveryDesk() {
  const { gateway } = useSession();
  const [typed, setTyped] = useState('');
  const [consignmentId, setConsignmentId] = useState('');
  const [reload, setReload] = useState(0);
  // Held here, not in the desk: the desk is rebuilt after each reload, and the
  // code just issued must outlive that.
  const [issued, setIssued] = useState<OtpIssued | null>(null);

  const proof = useLoad(
    () => (consignmentId ? gateway.deliveryProof(consignmentId) : Promise.resolve(null)),
    `${consignmentId}:${reload}`,
  );

  function lookUp(event: FormEvent) {
    event.preventDefault();
    setConsignmentId(typed.trim());
    setIssued(null);
    setReload((n) => n + 1);
  }

  return (
    <>
      <h1>Delivery desk</h1>
      <p className="muted">Issue the consignee's one-time code, then record the proof when they give it back.</p>
      <form className="inline" onSubmit={lookUp} data-testid="lookup">
        <input placeholder="Consignment id" aria-label="Consignment id" data-testid="consignment-id"
          value={typed} onChange={(e) => setTyped(e.target.value)} required />
        <button type="submit">Look up</button>
      </form>
      {proof.error && <p className="error" role="alert">{proof.error}</p>}
      {consignmentId && !proof.loading && !proof.error && (
        <Desk key={`${consignmentId}:${reload}`} consignmentId={consignmentId} proof={proof.data}
          issued={issued} onIssued={setIssued} onChanged={() => setReload((n) => n + 1)} />
      )}
    </>
  );
}

interface DeskProps {
  consignmentId: string;
  proof: DeliveryProofView | null;
  issued: OtpIssued | null;
  onIssued: (issued: OtpIssued) => void;
  onChanged: () => void;
}

function Desk({ consignmentId, proof, issued, onIssued, onChanged }: DeskProps) {
  const { gateway, can } = useSession();
  const record = can('POD_RECORD');
  const [error, setError] = useState('');
  const [otp, setOtp] = useState('');
  const [recipient, setRecipient] = useState('');
  const [photo, setPhoto] = useState('');
  const [slip, setSlip] = useState(false);
  const confirmed = proof?.status === 'CONFIRMED';

  async function issue() {
    setError('');
    try {
      onIssued(await gateway.issueDeliveryOtp(consignmentId));
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      await gateway.recordDeliveryProof(consignmentId, { otp, recipientName: recipient, photoRef: photo });
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <section data-testid="proof">
      {proof === null ? (
        <p className="muted" data-testid="no-proof">No code has been issued for this consignment yet.</p>
      ) : (
        <dl>
          <div><dt>Proof</dt><dd data-testid="proof-status">{proof.status}</dd></div>
          {confirmed ? (
            <>
              <div><dt>Received by</dt><dd data-testid="proof-recipient">{proof.recipientName}</dd></div>
              <div><dt>Photo</dt><dd>{proof.photoRef || '—'}</dd></div>
              <div><dt>Delivered</dt><dd>{proof.deliveredAt}</dd></div>
            </>
          ) : (
            <div><dt>Code valid until</dt><dd>{proof.otpExpiresAt}</dd></div>
          )}
        </dl>
      )}
      {error && <p className="error" role="alert">{error}</p>}
      {record && !confirmed && (
        <>
          <p><button data-testid="issue-otp" onClick={issue}>Issue a code</button></p>
          {issued && (
            <p data-testid="otp-issued">
              Code issued, valid until {issued.expiresAt}.
              {issued.otp && <> Demo code: <strong data-testid="otp-code">{issued.otp}</strong></>}
            </p>
          )}
          {proof !== null && (
            <form className="inline" onSubmit={submit} data-testid="record-proof">
              <input placeholder="Code" aria-label="Code" data-testid="proof-otp" value={otp}
                onChange={(e) => setOtp(e.target.value)} required />
              <input placeholder="Recipient name" aria-label="Recipient name" value={recipient}
                onChange={(e) => setRecipient(e.target.value)} required />
              <input placeholder="Photo reference" aria-label="Photo reference" value={photo}
                onChange={(e) => setPhoto(e.target.value)} />
              <button type="submit">Record proof</button>
            </form>
          )}
        </>
      )}
      {confirmed && proof && (
        <>
          <p><button className="link" data-testid="print-proof" onClick={() => setSlip(true)}>Print slip</button></p>
          {slip && (
            <section className="slip" data-testid="proof-slip" aria-label="Proof of delivery slip">
              <h3>Proof of delivery</h3>
              <div className="slip-body">
                <dl>
                  <div><dt>Consignment</dt><dd>{proof.consignmentId}</dd></div>
                  <div><dt>Received by</dt><dd>{proof.recipientName}</dd></div>
                  <div><dt>Delivered</dt><dd>{proof.deliveredAt}</dd></div>
                  <div><dt>Photo</dt><dd>{proof.photoRef || '—'}</dd></div>
                </dl>
                <Qr payload={`LMS:CONSIGNMENT:${proof.consignmentId}`} />
              </div>
              <p className="actions no-print">
                <button data-testid="print-now" onClick={() => window.print()}>Print</button>
              </p>
            </section>
          )}
        </>
      )}
    </section>
  );
}
