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

import { useEffect, useRef, useState, type FormEvent } from 'react';
import {
  ApiProblem, type ContractChargeDraft, type ContractChecklistRow, type ContractDetail, type ContractDraft, type Row,
} from '../gateway/lmsGateway';
import { useSession } from '../session';

/** Today as a `YYYY-MM-DD` day, in UTC like the API. */
function today(): string {
  return new Date().toISOString().slice(0, 10);
}

const FIELDS: Array<[keyof ContractDraft, string, string]> = [
  ['code', 'Contract number (blank for the next in the series)', 'text'],
  ['effectiveFrom', 'Starts', 'date'],
  ['validTill', 'Ends (optional)', 'date'],
  ['transportRatePerKg', 'Transport / kg', 'text'],
  ['transportMinimumCharge', 'Transport minimum', 'text'],
  ['storageRatePerPalletDay', 'Storage / pallet-day', 'text'],
  ['handlingInRatePerPallet', 'Handling in / pallet', 'text'],
  ['handlingOutRatePerPallet', 'Handling out / pallet', 'text'],
];

function blankDraft(): ContractDraft {
  return {
    code: '', effectiveFrom: today(), validTill: '', transportRatePerKg: '', transportMinimumCharge: '',
    storageRatePerPalletDay: '', handlingInRatePerPallet: '', handlingOutRatePerPallet: '',
  };
}

function draftOf(d: ContractDetail): ContractDraft {
  return {
    code: d.code, effectiveFrom: d.effectiveFrom, validTill: d.validTill, transportRatePerKg: d.transportRatePerKg,
    transportMinimumCharge: d.transportMinimumCharge, storageRatePerPalletDay: d.storageRatePerPalletDay,
    handlingInRatePerPallet: d.handlingInRatePerPallet, handlingOutRatePerPallet: d.handlingOutRatePerPallet,
  };
}

/**
 * A client's contract lifecycle: draft a version, edit and delete it while it is a draft,
 * ask for it to be verified (a second person approves it in the approvals inbox), and on one
 * in force ask to extend its end date or add a charge from a day. Everything the server
 * refuses is shown with its code and, for the checklist, every failing rule.
 */
export function ContractLifecycle({ clientId, contracts, onChanged }: {
  clientId: string; contracts: Row[]; onChanged: () => void;
}) {
  const { gateway, can, auth } = useSession();
  const [form, setForm] = useState<ContractDraft>(blankDraft);
  const [edit, setEdit] = useState<ContractDraft>(blankDraft);
  const [detail, setDetail] = useState<ContractDetail | null>(null);
  const [checklist, setChecklist] = useState<ContractChecklistRow[]>([]);
  const [error, setError] = useState('');
  const [code, setCode] = useState('');
  const [refused, setRefused] = useState<Array<[string, string]>>([]);
  const [message, setMessage] = useState('');
  const [extendTill, setExtendTill] = useState('');
  const [extendReason, setExtendReason] = useState('');
  const [charge, setCharge] = useState<ContractChargeDraft>({
    serviceCode: '', unit: 'UNIT', rate: '', effectiveFrom: today(), reason: '',
  });
  const manage = can('CLIENT_CONTRACT_MANAGE');

  async function open(id: string) {
    try {
      const d = await gateway.contractDetail(id);
      setDetail(d);
      setEdit(draftOf(d));
      setChecklist(d.status === 'DRAFT' ? await gateway.contractChecklist(id) : []);
    } catch (e) {
      fail(e);
    }
  }

  // A version opened earlier follows the list when it is read again.
  const openId = useRef<string | null>(null);
  openId.current = detail?.id ?? null;
  useEffect(() => {
    if (openId.current) {
      void open(openId.current);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [contracts]);

  function fail(e: unknown) {
    setError((e as Error).message);
    if (e instanceof ApiProblem) {
      setCode(e.code);
      setRefused(Object.entries(e.errors));
    }
  }

  /** Runs one action, shows what the server refused it with, and reads the versions again. */
  async function act(action: () => Promise<void>) {
    setError('');
    setCode('');
    setRefused([]);
    setMessage('');
    try {
      await action();
      onChanged();
    } catch (e) {
      fail(e);
    }
  }

  const create = (event: FormEvent) => {
    event.preventDefault();
    void act(async () => {
      const id = await gateway.createContractDraft(auth?.orgUnitId ?? '', clientId, form);
      setMessage('Draft created.');
      setForm(blankDraft());
      await open(id);
    });
  };

  const save = (event: FormEvent) => {
    event.preventDefault();
    if (!detail) {
      return;
    }
    void act(async () => {
      await gateway.reviseContractDraft(detail, edit);
      setMessage('Draft saved.');
      await open(detail.id);
    });
  };

  const remove = () => {
    if (!detail) {
      return;
    }
    void act(async () => {
      await gateway.deleteContractDraft(detail.id, detail.version);
      setDetail(null);
      setMessage('Draft deleted.');
    });
  };

  const verify = () => {
    if (!detail) {
      return;
    }
    void act(async () => {
      const ticket = await gateway.requestContractVerification(detail.id, detail.version);
      setMessage(ticket ? 'Verification requested: awaiting approval.' : 'The contract is in force.');
      await open(detail.id);
    });
  };

  const extend = (event: FormEvent) => {
    event.preventDefault();
    if (!detail) {
      return;
    }
    void act(async () => {
      const ticket = await gateway.requestContractExtension(detail.id, extendTill, extendReason);
      setMessage(ticket ? 'Extension requested: awaiting approval.' : 'The end date has moved.');
      await open(detail.id);
    });
  };

  const addCharge = (event: FormEvent) => {
    event.preventDefault();
    if (!detail) {
      return;
    }
    void act(async () => {
      const ticket = await gateway.requestContractChargeAddition(detail.id, charge);
      setMessage(ticket ? 'Charge requested: awaiting approval.' : 'A successor version carries the charge.');
      await open(detail.id);
    });
  };

  return (
    <section data-testid="contract-lifecycle" aria-label="Contract lifecycle">
      <h3>Contract versions</h3>
      <table>
        <thead><tr><th>Contract</th><th>State</th><th>From</th><th>To</th><th>Ends</th><th></th></tr></thead>
        <tbody>
          {contracts.map((c) => (
            <tr key={c.id} data-testid="version-row">
              <td>{c.code}</td><td data-testid="version-state">{c.state}</td>
              <td>{c.effectiveFrom}</td><td>{c.effectiveTo}</td><td>{c.validTill}</td>
              <td><button className="link" data-testid="open-version" onClick={() => void open(c.id)}>Open</button></td>
            </tr>
          ))}
        </tbody>
      </table>

      {manage && (
        <>
          <h3>Draft a contract</h3>
          <form className="inline" onSubmit={create} data-testid="new-contract">
            {FIELDS.map(([field, label, type]) => (
              <label key={field}>{label}{' '}
                <input name={`new-${field}`} type={type} data-testid={`new-contract-${field}`} value={form[field]}
                  onChange={(e) => setForm({ ...form, [field]: e.target.value })} />
              </label>
            ))}
            <button type="submit" data-testid="create-draft">Create draft</button>
          </form>
        </>
      )}
      {error && (
        <>
          <p className="error" role="alert" data-testid="contract-error">
            {code && <code data-testid="contract-refusal">{code}</code>} {error}
          </p>
          {refused.length > 0 && (
            <ul data-testid="contract-refused-rules">
              {refused.map(([rule, why]) => <li key={rule} data-testid={`refused-${rule}`}>{rule}: {why}</li>)}
            </ul>
          )}
        </>
      )}
      {message && <p data-testid="contract-message">{message}</p>}

      {detail && (
        <section data-testid="contract-panel" aria-label="Contract version">
          <header>
            <h3>{detail.code}</h3>
            <button className="link" onClick={() => setDetail(null)} aria-label="Close contract">Close</button>
          </header>
          <dl>
            <div><dt>State</dt><dd data-testid="contract-status">{detail.state}</dd></div>
            <div><dt>Starts</dt><dd>{detail.effectiveFrom}</dd></div>
            <div><dt>Ends</dt><dd data-testid="contract-valid-till">{detail.validTill}</dd></div>
            <div><dt>Services</dt><dd>{detail.services.map((s) => `${s.serviceCode} ${s.rate}/${s.unit}`).join('; ')}</dd></div>
          </dl>
          {detail.rejectionReason && (
            <p className="error" data-testid="contract-rejection">Verification refused: {detail.rejectionReason}</p>
          )}

          {detail.status === 'DRAFT' && manage && (
            <>
              <ul data-testid="contract-checklist">
                {checklist.map((c) => (
                  <li key={c.code} data-testid={`check-${c.code}`} className={c.passed ? undefined : 'error'}>
                    {c.code}: {c.passed ? 'passes' : c.message}{c.required ? '' : ' (not required)'}
                  </li>
                ))}
              </ul>
              <form className="inline" onSubmit={save} data-testid="edit-contract">
                {FIELDS.map(([field, label, type]) => (
                  <label key={field}>{label}{' '}
                    <input name={`edit-${field}`} type={type} data-testid={`edit-contract-${field}`} value={edit[field]}
                      onChange={(e) => setEdit({ ...edit, [field]: e.target.value })} />
                  </label>
                ))}
                <button type="submit" data-testid="save-draft">Save draft</button>
              </form>
              <p className="actions">
                <button data-testid="request-verification" onClick={verify}>Request verification</button>
                <button className="link" data-testid="delete-draft" onClick={remove}>Delete draft</button>
              </p>
            </>
          )}
          {detail.status === 'PENDING_VERIFICATION' && (
            <p className="muted" data-testid="contract-pending">Awaiting verification in the approvals inbox.</p>
          )}

          {detail.status === 'ACTIVE' && !detail.effectiveTo && manage && (
            <>
              {detail.validTill && (
                <>
                  <h4>Extend</h4>
                  <form className="inline" onSubmit={extend} data-testid="extend-contract">
                    <input name="extendTill" type="date" aria-label="New end date" data-testid="extend-till"
                      value={extendTill} onChange={(e) => setExtendTill(e.target.value)} required />
                    <input name="extendReason" aria-label="Reason for the extension" placeholder="Reason"
                      data-testid="extend-reason" value={extendReason}
                      onChange={(e) => setExtendReason(e.target.value)} required />
                    <button type="submit" data-testid="request-extension">Request extension</button>
                  </form>
                </>
              )}
              <h4>Add a charge</h4>
              <form className="inline" onSubmit={addCharge} data-testid="add-charge">
                <input name="chargeCode" aria-label="Service code" placeholder="Service code" data-testid="charge-code"
                  value={charge.serviceCode} onChange={(e) => setCharge({ ...charge, serviceCode: e.target.value })} required />
                <input name="chargeUnit" aria-label="Unit" placeholder="Unit" data-testid="charge-unit"
                  value={charge.unit} onChange={(e) => setCharge({ ...charge, unit: e.target.value })} required />
                <input name="chargeRate" aria-label="Rate" placeholder="Rate" inputMode="decimal" data-testid="charge-rate"
                  value={charge.rate} onChange={(e) => setCharge({ ...charge, rate: e.target.value })} required />
                <input name="chargeFrom" type="date" aria-label="Applies from" data-testid="charge-from"
                  value={charge.effectiveFrom} onChange={(e) => setCharge({ ...charge, effectiveFrom: e.target.value })} required />
                <input name="chargeReason" aria-label="Reason for the charge" placeholder="Reason" data-testid="charge-reason"
                  value={charge.reason} onChange={(e) => setCharge({ ...charge, reason: e.target.value })} required />
                <button type="submit" data-testid="request-charge">Request charge</button>
              </form>
            </>
          )}
        </section>
      )}
    </section>
  );
}
