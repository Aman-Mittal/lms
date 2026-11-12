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
  ApiProblem, LANE_RATE_BASES, type ContractChargeDraft, type ContractChecklistRow, type ContractDetail,
  type ContractDraft, type ContractLaneDraft, type Row,
} from '../gateway/lmsGateway';
import { EntityPicker } from '../EntityPicker';
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

function blankLane(): ContractLaneDraft {
  return {
    laneCode: '', originTerminalId: '', originZone: '', destinationTerminalId: '', destinationZone: '',
    freightMode: '', loadType: '', rateBasis: 'PER_KG', rate: '', transitDays: '', slabs: '', consigneeIds: '',
  };
}

/** A lane's end for the table: a terminal, or a zone. */
function end(terminal: string, zone: string): string {
  return zone ? `zone ${zone}` : terminal;
}

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
  const [lane, setLane] = useState<ContractLaneDraft>(blankLane);
  const [direct, setDirect] = useState(true);
  const [loadTypes, setLoadTypes] = useState('');
  const manage = can('CLIENT_CONTRACT_MANAGE');
  const setLaneField = (field: keyof ContractLaneDraft) => (value: string) => setLane((l) => ({ ...l, [field]: value }));

  async function open(id: string) {
    try {
      const d = await gateway.contractDetail(id);
      setDetail(d);
      setEdit(draftOf(d));
      setDirect(d.directOrdersAllowed);
      setLoadTypes(d.allowedLoadTypes.join(', '));
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

  /** Adds the lane typed to the draft: the lanes are restated whole, so a clash of codes is refused by name. */
  const addLane = (event: FormEvent) => {
    event.preventDefault();
    if (!detail) {
      return;
    }
    void act(async () => {
      await gateway.reviseContractLanes(detail, [...detail.lanes, lane], detail.directOrdersAllowed,
        detail.allowedLoadTypes);
      setMessage('Lane added.');
      setLane(blankLane());
      await open(detail.id);
    });
  };

  const removeLane = (laneCode: string) => {
    if (!detail) {
      return;
    }
    void act(async () => {
      await gateway.reviseContractLanes(detail, detail.lanes.filter((l) => l.laneCode !== laneCode),
        detail.directOrdersAllowed, detail.allowedLoadTypes);
      setMessage('Lane removed.');
      await open(detail.id);
    });
  };

  /** Whether orders outside the lanes are accepted, and which load types the contract covers. */
  const saveAllowances = (event: FormEvent) => {
    event.preventDefault();
    if (!detail) {
      return;
    }
    const types = loadTypes.split(',').map((t) => t.trim()).filter(Boolean);
    void act(async () => {
      await gateway.reviseContractLanes(detail, detail.lanes, direct, types);
      setMessage('Allowances saved.');
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
            <div><dt>Direct orders</dt><dd data-testid="contract-direct">{detail.directOrdersAllowed ? 'Allowed' : 'Not allowed'}</dd></div>
            <div><dt>Load types</dt><dd data-testid="contract-load-types">{detail.allowedLoadTypes.length ? detail.allowedLoadTypes.join(', ') : 'Any'}</dd></div>
          </dl>
          <h4>Lanes</h4>
          {detail.lanes.length === 0 && <p className="muted" data-testid="no-lanes">No lanes: orders are priced by the per-kilo rate.</p>}
          {detail.lanes.length > 0 && (
            <table data-testid="contract-lanes">
              <thead><tr><th>Lane</th><th>From</th><th>To</th><th>Mode</th><th>Load</th><th>Basis</th><th>Rate</th><th>Days</th><th>Slabs</th><th /></tr></thead>
              <tbody>
                {detail.lanes.map((l) => (
                  <tr key={l.laneCode} data-testid="lane-row">
                    <td data-testid="lane-code">{l.laneCode}</td>
                    <td>{end(l.originTerminalId, l.originZone)}</td><td>{end(l.destinationTerminalId, l.destinationZone)}</td>
                    <td>{l.freightMode || 'Any'}</td><td>{l.loadType || 'Any'}</td>
                    <td>{l.rateBasis}</td><td>{l.rate || '—'}</td><td>{l.transitDays || '—'}</td>
                    <td>{l.slabs || '—'}</td>
                    <td>
                      {detail.status === 'DRAFT' && manage && (
                        <button className="link" data-testid="remove-lane" onClick={() => removeLane(l.laneCode)}>Remove</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
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
              <form className="inline" onSubmit={saveAllowances} data-testid="edit-allowances">
                <label>Direct orders allowed{' '}
                  <input type="checkbox" name="direct" data-testid="allow-direct" checked={direct}
                    onChange={(e) => setDirect(e.target.checked)} />
                </label>
                <input name="loadTypes" aria-label="Allowed load types" placeholder="Allowed load types (comma separated)"
                  data-testid="allowed-load-types" value={loadTypes} onChange={(e) => setLoadTypes(e.target.value)} />
                <button type="submit" data-testid="save-allowances">Save allowances</button>
              </form>
              <form className="inline" onSubmit={addLane} data-testid="add-lane">
                <input name="laneCode" aria-label="Lane code" placeholder="Lane code" data-testid="lane-form-code"
                  value={lane.laneCode} onChange={(e) => setLaneField('laneCode')(e.target.value)} required />
                <EntityPicker kind="terminal" ariaLabel="Lane origin terminal id" placeholder="Origin terminal"
                  testId="lane-form-origin" value={lane.originTerminalId} onChange={setLaneField('originTerminalId')} />
                <input name="originZone" aria-label="Lane origin zone" placeholder="or origin zone"
                  data-testid="lane-form-origin-zone" value={lane.originZone}
                  onChange={(e) => setLaneField('originZone')(e.target.value)} />
                <EntityPicker kind="terminal" ariaLabel="Lane destination terminal id" placeholder="Destination terminal"
                  testId="lane-form-destination" value={lane.destinationTerminalId}
                  onChange={setLaneField('destinationTerminalId')} />
                <input name="destinationZone" aria-label="Lane destination zone" placeholder="or destination zone"
                  data-testid="lane-form-destination-zone" value={lane.destinationZone}
                  onChange={(e) => setLaneField('destinationZone')(e.target.value)} />
                <select name="freightMode" aria-label="Lane freight mode" data-testid="lane-form-mode"
                  value={lane.freightMode} onChange={(e) => setLaneField('freightMode')(e.target.value)}>
                  <option value="">Any mode</option>
                  <option value="FTL">FTL</option>
                  <option value="LTL">LTL</option>
                </select>
                <input name="laneLoadType" aria-label="Lane load type" placeholder="Load type (any)"
                  data-testid="lane-form-load-type" value={lane.loadType}
                  onChange={(e) => setLaneField('loadType')(e.target.value)} />
                <select name="rateBasis" aria-label="Lane rate basis" data-testid="lane-form-basis" value={lane.rateBasis}
                  onChange={(e) => setLaneField('rateBasis')(e.target.value)}>
                  {LANE_RATE_BASES.map((b) => <option key={b} value={b}>{b}</option>)}
                </select>
                <input name="laneRate" aria-label="Lane rate" placeholder="Rate" inputMode="decimal"
                  data-testid="lane-form-rate" value={lane.rate} onChange={(e) => setLaneField('rate')(e.target.value)} />
                <input name="laneDays" aria-label="Lane transit days" placeholder="Transit days" inputMode="numeric"
                  data-testid="lane-form-days" value={lane.transitDays}
                  onChange={(e) => setLaneField('transitDays')(e.target.value)} />
                <input name="laneSlabs" aria-label="Lane slabs" placeholder="Slabs: 0-1000:PER_KG:12; 1000-:PER_TONNE:9000"
                  data-testid="lane-form-slabs" value={lane.slabs} onChange={(e) => setLaneField('slabs')(e.target.value)} />
                <input name="laneConsignees" aria-label="Lane consignee ids" placeholder="Consignee ids (comma separated)"
                  data-testid="lane-form-consignees" value={lane.consigneeIds}
                  onChange={(e) => setLaneField('consigneeIds')(e.target.value)} />
                <button type="submit" data-testid="add-lane-button">Add lane</button>
              </form>
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
