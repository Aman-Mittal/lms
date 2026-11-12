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
import { EntityPicker } from '../EntityPicker';
import {
  ApiProblem, GATE_PURPOSES, type GatePassPage, type GatePassVerdict, type GatePassView, type GatePurpose,
  type GateVisitPage, type GateVisitView,
} from '../gateway/lmsGateway';
import { useSession } from '../session';

type Tab = 'desk' | 'register' | 'passes';

/** A comma-separated list as the gateway takes it. */
function listOf(text: string): string[] {
  return text.split(',').map((s) => s.trim()).filter((s) => s !== '');
}

/**
 * The yard gate: who is inside now, recording a vehicle in and out, the register of past
 * visits, and gate passes. Recording needs GATE_REGISTER and issuing or cancelling a pass
 * needs GATE_PASS_MANAGE; the controls a person could not use are not drawn.
 */
export function GateDesk() {
  const { gateway, can } = useSession();
  const [tab, setTab] = useState<Tab>('desk');
  const [refusal, setRefusal] = useState<{ code: string; message: string } | null>(null);
  const [message, setMessage] = useState('');

  // inside now and the exit form
  const [inside, setInside] = useState<GateVisitView[]>([]);
  const [exiting, setExiting] = useState('');
  const [exitSeals, setExitSeals] = useState('');
  const [exitWeight, setExitWeight] = useState('');
  const [exitRemarks, setExitRemarks] = useState('');

  // the entry form
  const [entrySite, setEntrySite] = useState('');
  const [entryVehicle, setEntryVehicle] = useState('');
  const [entryRegistration, setEntryRegistration] = useState('');
  const [entryDriver, setEntryDriver] = useState('');
  const [entryDriverName, setEntryDriverName] = useState('');
  const [entryPurpose, setEntryPurpose] = useState<GatePurpose>('TRIP');
  const [entryTrip, setEntryTrip] = useState('');
  const [entryPass, setEntryPass] = useState('');
  const [entrySeals, setEntrySeals] = useState('');
  const [entryWeight, setEntryWeight] = useState('');
  const [entryRemarks, setEntryRemarks] = useState('');

  // checking a pass
  const [checkSite, setCheckSite] = useState('');
  const [checkVehicle, setCheckVehicle] = useState('');
  const [checkRegistration, setCheckRegistration] = useState('');
  const [checkTrip, setCheckTrip] = useState('');
  const [checkNumber, setCheckNumber] = useState('');
  const [verdict, setVerdict] = useState<GatePassVerdict | null>(null);

  // the register
  const [registerStatus, setRegisterStatus] = useState('');
  const [registerQ, setRegisterQ] = useState('');
  const [visits, setVisits] = useState<GateVisitView[]>([]);
  const [visitsNext, setVisitsNext] = useState<string | null>(null);

  // the passes
  const [passSite, setPassSite] = useState('');
  const [passVehicle, setPassVehicle] = useState('');
  const [passRegistration, setPassRegistration] = useState('');
  const [passPurpose, setPassPurpose] = useState<GatePurpose>('VISITOR');
  const [passFrom, setPassFrom] = useState('');
  const [passUntil, setPassUntil] = useState('');
  const [passTrip, setPassTrip] = useState('');
  const [passNumber, setPassNumber] = useState('');
  const [passStatus, setPassStatus] = useState('');
  const [passQ, setPassQ] = useState('');
  const [passes, setPasses] = useState<GatePassView[]>([]);
  const [passesNext, setPassesNext] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState('');
  const [cancelReason, setCancelReason] = useState('');

  async function loadInside() {
    setInside(await gateway.openGateVisits());
  }

  async function loadVisits(append: boolean) {
    const page: GateVisitPage = await gateway.gateVisits(
      { status: registerStatus, q: registerQ }, append ? visitsNext ?? undefined : undefined);
    setVisits(append ? [...visits, ...page.items] : page.items);
    setVisitsNext(page.nextCursor);
  }

  async function loadPasses(append: boolean) {
    const page: GatePassPage = await gateway.gatePasses(
      { status: passStatus, q: passQ }, append ? passesNext ?? undefined : undefined);
    setPasses(append ? [...passes, ...page.items] : page.items);
    setPassesNext(page.nextCursor);
  }

  async function attempt(action: () => Promise<string | void>) {
    setRefusal(null);
    setMessage('');
    try {
      const said = await action();
      if (said) {
        setMessage(said);
      }
    } catch (e) {
      setRefusal({ code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message });
    }
  }

  // The first read of the desk.
  useEffect(() => {
    void attempt(loadInside);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function show(next: Tab) {
    setTab(next);
    setRefusal(null);
    setMessage('');
    void attempt(async () => {
      if (next === 'desk') {
        await loadInside();
      } else if (next === 'register') {
        await loadVisits(false);
      } else {
        await loadPasses(false);
      }
    });
  }

  function enter(e: FormEvent) {
    e.preventDefault();
    void attempt(async () => {
      await gateway.recordGateEntry({
        terminalId: entrySite, vehicleId: entryVehicle, registrationNo: entryRegistration, driverId: entryDriver,
        driverName: entryDriverName, purpose: entryPurpose, tripId: entryTrip, gatePassNo: entryPass,
        sealNos: listOf(entrySeals), weightRef: entryWeight, remarks: entryRemarks,
      });
      setEntryRegistration('');
      setEntryDriverName('');
      setEntryVehicle('');
      setEntryDriver('');
      setEntryTrip('');
      setEntryPass('');
      setEntrySeals('');
      setEntryWeight('');
      setEntryRemarks('');
      await loadInside();
      return 'Entry recorded.';
    });
  }

  function startExit(id: string) {
    setExiting(id);
    setExitSeals('');
    setExitWeight('');
    setExitRemarks('');
  }

  function exit(e: FormEvent, id: string) {
    e.preventDefault();
    void attempt(async () => {
      await gateway.recordGateExit(id, { sealNos: listOf(exitSeals), weightRef: exitWeight, remarks: exitRemarks });
      setExiting('');
      await loadInside();
      return 'Exit recorded.';
    });
  }

  function check(e: FormEvent) {
    e.preventDefault();
    setVerdict(null);
    void attempt(async () => setVerdict(await gateway.checkGatePass({
      terminalId: checkSite, vehicleId: checkVehicle, registrationNo: checkRegistration, tripId: checkTrip,
      passNo: checkNumber,
    })));
  }

  function issue(e: FormEvent) {
    e.preventDefault();
    void attempt(async () => {
      await gateway.issueGatePass({
        terminalId: passSite, vehicleId: passVehicle, registrationNo: passRegistration, tripId: passTrip,
        purpose: passPurpose, validFrom: passFrom, validUntil: passUntil, passNo: passNumber,
      });
      setPassRegistration('');
      setPassVehicle('');
      setPassTrip('');
      setPassNumber('');
      await loadPasses(false);
      return 'Pass issued.';
    });
  }

  function startCancel(id: string) {
    setCancelling(id);
    setCancelReason('');
  }

  function cancel(e: FormEvent, id: string) {
    e.preventDefault();
    void attempt(async () => {
      await gateway.cancelGatePass(id, cancelReason);
      setCancelling('');
      await loadPasses(false);
      return 'Pass cancelled.';
    });
  }

  return (
    <>
      <h1>Gate desk</h1>
      <p className="muted">Vehicles at the gate: who is inside, entries and exits, the register and gate passes.</p>
      {refusal && (
        <p className="error" role="alert" data-testid="gate-error">
          {refusal.code && <code data-testid="gate-refusal">{refusal.code}</code>}
          {' '}{refusal.message}
        </p>
      )}
      {message && <p className="ok" role="status" data-testid="gate-message">{message}</p>}

      <p className="actions" role="tablist" aria-label="Gate views">
        <button type="button" role="tab" className={tab === 'desk' ? '' : 'link'} data-testid="gate-tab-desk"
          aria-selected={tab === 'desk'} onClick={() => show('desk')}>Desk</button>{' '}
        <button type="button" role="tab" className={tab === 'register' ? '' : 'link'} data-testid="gate-tab-register"
          aria-selected={tab === 'register'} onClick={() => show('register')}>Register</button>{' '}
        <button type="button" role="tab" className={tab === 'passes' ? '' : 'link'} data-testid="gate-tab-passes"
          aria-selected={tab === 'passes'} onClick={() => show('passes')}>Passes</button>
      </p>

      {tab === 'desk' && (
        <section data-testid="gate-desk" aria-label="Gate desk">
          <h2>Inside now</h2>
          {inside.length === 0 ? (
            <p className="muted" data-testid="inside-empty">No vehicle is inside.</p>
          ) : (
            <table data-testid="inside">
              <thead>
                <tr><th>Vehicle</th><th>Site</th><th>Purpose</th><th>Driver</th><th>Trip</th><th>Pass</th><th>Entered</th>
                  <th>Inside for</th><th /></tr>
              </thead>
              <tbody>
                {inside.map((v) => (
                  <InsideRow key={v.id} v={v} canExit={can('GATE_REGISTER')} open={exiting === v.id}
                    onStart={() => startExit(v.id)} onCancel={() => setExiting('')} onSubmit={(e) => exit(e, v.id)}
                    seals={exitSeals} setSeals={setExitSeals} weight={exitWeight} setWeight={setExitWeight}
                    remarks={exitRemarks} setRemarks={setExitRemarks} />
                ))}
              </tbody>
            </table>
          )}

          {can('GATE_REGISTER') && (
            <>
              <form data-testid="gate-entry" onSubmit={enter}>
                <h2>Record entry</h2>
                <EntityPicker kind="terminal" ariaLabel="Gate site" placeholder="Site" testId="entry-site" required
                  value={entrySite} onChange={setEntrySite} />
                <EntityPicker kind="vehicle" ariaLabel="Registered vehicle" placeholder="Registered vehicle"
                  testId="entry-vehicle" value={entryVehicle} onChange={setEntryVehicle} />
                <input placeholder="Or a visitor's registration number" aria-label="Visitor registration number"
                  data-testid="entry-registration" value={entryRegistration}
                  onChange={(e) => setEntryRegistration(e.target.value)} />
                <EntityPicker kind="driver" ariaLabel="Driver" placeholder="Driver" testId="entry-driver"
                  value={entryDriver} onChange={setEntryDriver} />
                <input placeholder="Or a driver's name" aria-label="Driver name" data-testid="entry-driver-name"
                  value={entryDriverName} onChange={(e) => setEntryDriverName(e.target.value)} />
                <select aria-label="Purpose" data-testid="entry-purpose" value={entryPurpose}
                  onChange={(e) => setEntryPurpose(e.target.value as GatePurpose)}>
                  {GATE_PURPOSES.map((p) => <option key={p} value={p}>{p}</option>)}
                </select>
                <EntityPicker kind="trip" ariaLabel="Trip" placeholder="Trip (optional)" testId="entry-trip"
                  value={entryTrip} onChange={setEntryTrip} />
                <input placeholder="Gate pass number" aria-label="Gate pass number" data-testid="entry-pass"
                  value={entryPass} onChange={(e) => setEntryPass(e.target.value)} />
                <input placeholder="Seal numbers, comma separated" aria-label="Seal numbers" data-testid="entry-seals"
                  value={entrySeals} onChange={(e) => setEntrySeals(e.target.value)} />
                <input placeholder="Weight reference" aria-label="Weight reference" data-testid="entry-weight"
                  value={entryWeight} onChange={(e) => setEntryWeight(e.target.value)} />
                <input placeholder="Remarks" aria-label="Remarks" data-testid="entry-remarks" value={entryRemarks}
                  onChange={(e) => setEntryRemarks(e.target.value)} />
                <button type="submit" data-testid="entry-submit">Record entry</button>
              </form>

              <form className="inline" data-testid="pass-check" onSubmit={check}>
                <h2>Check pass</h2>
                <EntityPicker kind="terminal" ariaLabel="Check site" placeholder="Site" testId="check-site" required
                  value={checkSite} onChange={setCheckSite} />
                <EntityPicker kind="vehicle" ariaLabel="Check vehicle" placeholder="Registered vehicle"
                  testId="check-vehicle" value={checkVehicle} onChange={setCheckVehicle} />
                <input placeholder="Or a registration number" aria-label="Check registration number"
                  data-testid="check-registration" value={checkRegistration}
                  onChange={(e) => setCheckRegistration(e.target.value)} />
                <EntityPicker kind="trip" ariaLabel="Check trip" placeholder="Trip (optional)" testId="check-trip"
                  value={checkTrip} onChange={setCheckTrip} />
                <input placeholder="Pass number" aria-label="Check pass number" data-testid="check-number"
                  value={checkNumber} onChange={(e) => setCheckNumber(e.target.value)} />
                <button type="submit" data-testid="check-submit">Check</button>
              </form>
              {verdict && (
                <p data-testid="check-verdict">
                  {verdict.admits
                    ? `Admits${verdict.passNo ? ` on pass ${verdict.passNo}` : ''}${verdict.passRequired ? '' : ' (no pass is required here)'}`
                    : <>Refused: <code data-testid="check-code">{verdict.code}</code> {verdict.message}</>}
                </p>
              )}
            </>
          )}
        </section>
      )}

      {tab === 'register' && (
        <section data-testid="gate-register" aria-label="Gate register">
          <h2>Register</h2>
          <form className="inline" onSubmit={(e) => { e.preventDefault(); void attempt(() => loadVisits(false)); }}>
            <select aria-label="Visit status" data-testid="register-status" value={registerStatus}
              onChange={(e) => setRegisterStatus(e.target.value)}>
              <option value="">Every visit</option>
              <option value="OPEN">Inside</option>
              <option value="CLOSED">Left</option>
            </select>
            <input placeholder="Registration number" aria-label="Search the register" data-testid="register-search"
              value={registerQ} onChange={(e) => setRegisterQ(e.target.value)} />
            <button type="submit" data-testid="register-search-submit">Search</button>
          </form>
          {visits.length === 0 ? (
            <p className="muted" data-testid="register-empty">No visit matches.</p>
          ) : (
            <table data-testid="register">
              <thead>
                <tr><th>Vehicle</th><th>Site</th><th>Purpose</th><th>Driver</th><th>Trip</th><th>Pass</th><th>Status</th>
                  <th>Entered</th><th>Left</th><th>Inside for</th><th>Seals in</th><th>Seals out</th></tr>
              </thead>
              <tbody>
                {visits.map((v) => (
                  <tr key={v.id} data-testid="register-row" data-id={v.id}>
                    <td>{v.registrationNo}</td><td>{v.terminalCode}</td><td>{v.purpose}</td><td>{v.driverName}</td>
                    <td>{v.tripNo}</td><td>{v.gatePassNo}</td><td>{v.status}</td><td>{v.enteredAt}</td>
                    <td>{v.exitedAt}</td><td>{v.dwell}</td><td>{v.entrySeals}</td><td>{v.exitSeals}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {visitsNext && (
            <button type="button" data-testid="register-more"
              onClick={() => void attempt(() => loadVisits(true))}>Load more</button>
          )}
        </section>
      )}

      {tab === 'passes' && (
        <section data-testid="gate-passes" aria-label="Gate passes">
          {can('GATE_PASS_MANAGE') && (
            <form data-testid="pass-issue" onSubmit={issue}>
              <h2>Issue a pass</h2>
              <EntityPicker kind="terminal" ariaLabel="Pass site" placeholder="Site" testId="pass-site" required
                value={passSite} onChange={setPassSite} />
              <EntityPicker kind="vehicle" ariaLabel="Pass vehicle" placeholder="Registered vehicle"
                testId="pass-vehicle" value={passVehicle} onChange={setPassVehicle} />
              <input placeholder="Or a visitor's registration number" aria-label="Pass registration number"
                data-testid="pass-registration" value={passRegistration}
                onChange={(e) => setPassRegistration(e.target.value)} />
              <select aria-label="Pass purpose" data-testid="pass-purpose" value={passPurpose}
                onChange={(e) => setPassPurpose(e.target.value as GatePurpose)}>
                {GATE_PURPOSES.map((p) => <option key={p} value={p}>{p}</option>)}
              </select>
              <label>Valid from
                <input type="datetime-local" aria-label="Valid from" data-testid="pass-from" required value={passFrom}
                  onChange={(e) => setPassFrom(e.target.value)} />
              </label>
              <label>Valid until
                <input type="datetime-local" aria-label="Valid until" data-testid="pass-until" required
                  value={passUntil} onChange={(e) => setPassUntil(e.target.value)} />
              </label>
              <EntityPicker kind="trip" ariaLabel="Pass trip" placeholder="Trip (optional)" testId="pass-trip"
                value={passTrip} onChange={setPassTrip} />
              <input placeholder="Pass number, where no series numbers it" aria-label="Pass number"
                data-testid="pass-number" value={passNumber} onChange={(e) => setPassNumber(e.target.value)} />
              <button type="submit" data-testid="pass-submit">Issue pass</button>
            </form>
          )}

          <h2>Passes</h2>
          <form className="inline" onSubmit={(e) => { e.preventDefault(); void attempt(() => loadPasses(false)); }}>
            <select aria-label="Pass status" data-testid="pass-status-filter" value={passStatus}
              onChange={(e) => setPassStatus(e.target.value)}>
              <option value="">Every pass</option>
              <option value="ISSUED">Issued</option>
              <option value="USED">Used</option>
              <option value="CANCELLED">Cancelled</option>
            </select>
            <input placeholder="Search" aria-label="Search passes" data-testid="pass-search" value={passQ}
              onChange={(e) => setPassQ(e.target.value)} />
            <button type="submit" data-testid="pass-search-submit">Search</button>
          </form>
          {passes.length === 0 ? (
            <p className="muted" data-testid="passes-empty">No pass matches.</p>
          ) : (
            <table data-testid="passes">
              <thead>
                <tr><th>Pass</th><th>Site</th><th>Vehicle</th><th>Trip</th><th>Purpose</th><th>Valid from</th>
                  <th>Valid until</th><th>Status</th><th /></tr>
              </thead>
              <tbody>
                {passes.map((p) => (
                  <PassRow key={p.id} p={p} canCancel={can('GATE_PASS_MANAGE')} open={cancelling === p.id}
                    onStart={() => startCancel(p.id)} onKeep={() => setCancelling('')}
                    onSubmit={(e) => cancel(e, p.id)} reason={cancelReason} setReason={setCancelReason} />
                ))}
              </tbody>
            </table>
          )}
          {passesNext && (
            <button type="button" data-testid="passes-more"
              onClick={() => void attempt(() => loadPasses(true))}>Load more</button>
          )}
        </section>
      )}
    </>
  );
}

interface InsideRowProps {
  v: GateVisitView;
  canExit: boolean;
  open: boolean;
  onStart: () => void;
  onCancel: () => void;
  onSubmit: (e: FormEvent) => void;
  seals: string;
  setSeals: (v: string) => void;
  weight: string;
  setWeight: (v: string) => void;
  remarks: string;
  setRemarks: (v: string) => void;
}

/** A vehicle inside, and the small form that records its exit. */
function InsideRow(p: InsideRowProps) {
  const { v } = p;
  return (
    <>
      <tr data-testid="inside-row" data-id={v.id}>
        <td>{v.registrationNo}</td><td>{v.terminalCode}</td><td>{v.purpose}</td><td>{v.driverName}</td>
        <td>{v.tripNo}</td><td>{v.gatePassNo}</td><td>{v.enteredAt}</td><td>{v.dwell}</td>
        <td>
          {p.canExit && (
            <button type="button" className="link" data-testid="visit-exit" aria-label={`Exit ${v.registrationNo}`}
              onClick={p.onStart}>Exit</button>
          )}
        </td>
      </tr>
      {p.open && (
        <tr data-testid="exit-row">
          <td colSpan={9}>
            <form className="inline" data-testid="exit-form" onSubmit={p.onSubmit}>
              <input placeholder="Seal numbers, comma separated" aria-label="Exit seal numbers"
                data-testid="exit-seals" value={p.seals} onChange={(e) => p.setSeals(e.target.value)} />
              <input placeholder="Weight reference" aria-label="Exit weight reference" data-testid="exit-weight"
                value={p.weight} onChange={(e) => p.setWeight(e.target.value)} />
              <input placeholder="Remarks" aria-label="Exit remarks" data-testid="exit-remarks" value={p.remarks}
                onChange={(e) => p.setRemarks(e.target.value)} />
              <button type="submit" data-testid="exit-submit">Record exit</button>
              <button type="button" className="link" onClick={p.onCancel}>Cancel</button>
            </form>
          </td>
        </tr>
      )}
    </>
  );
}

interface PassRowProps {
  p: GatePassView;
  canCancel: boolean;
  open: boolean;
  onStart: () => void;
  onKeep: () => void;
  onSubmit: (e: FormEvent) => void;
  reason: string;
  setReason: (v: string) => void;
}

/** A pass, and the small form that cancels it. */
function PassRow(props: PassRowProps) {
  const { p } = props;
  return (
    <>
      <tr data-testid="pass-row" data-id={p.id}>
        <td>{p.passNo}</td><td>{p.terminalCode}</td><td>{p.registrationNo}</td><td>{p.tripNo}</td>
        <td>{p.purpose}</td><td>{p.validFrom}</td><td>{p.validUntil}</td>
        <td>
          <span className="badge" data-testid="pass-status">{p.status}</span>
          {p.expired && <span className="badge" data-testid="pass-expired">Expired</span>}
          {p.cancelReason && <span className="muted">{p.cancelReason}</span>}
        </td>
        <td>
          {p.status === 'ISSUED' && props.canCancel && (
            <button type="button" className="link" data-testid="pass-cancel" aria-label={`Cancel pass ${p.passNo}`}
              onClick={props.onStart}>Cancel</button>
          )}
        </td>
      </tr>
      {props.open && (
        <tr data-testid="pass-cancel-row">
          <td colSpan={9}>
            <form className="inline" data-testid="pass-cancel-form" onSubmit={props.onSubmit}>
              <input placeholder="Why" aria-label="Cancellation reason" data-testid="pass-cancel-reason" required
                value={props.reason} onChange={(e) => props.setReason(e.target.value)} />
              <button type="submit" data-testid="pass-cancel-confirm">Cancel pass</button>
              <button type="button" className="link" onClick={props.onKeep}>Keep</button>
            </form>
          </td>
        </tr>
      )}
    </>
  );
}
