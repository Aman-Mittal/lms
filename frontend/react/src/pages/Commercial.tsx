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
  ApiProblem, GUIDE_STRATEGIES, SLAB_RATE_BASES, type AllocationDetail, type FreightBillDetail, type GuideDraft,
  type GuideStrategy, type SlabRateBasis, type RoutingGuideDetail, type Row, type SlabDraft, type TariffDraft,
} from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';

/** A refusal as the desk shows it: the problem's stable code, when it has one, and its message. */
interface Refusal {
  code: string;
  message: string;
}

function refusalOf(e: unknown): Refusal {
  return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
}

/** Today as a `YYYY-MM-DD` day, in UTC like the API. */
function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/** A refusal, named by its code so the fix can be looked up. */
function RefusalNote({ refusal, testId }: { refusal: Refusal | null; testId: string }) {
  if (!refusal) {
    return null;
  }
  return (
    <p className="error" role="alert" data-testid={testId}>
      {refusal.code && <code data-testid="refusal-code">{refusal.code}</code>} {refusal.message}
    </p>
  );
}

/** A plain table of rows, one column per field. */
function Table({ rows, columns, rowTestId }: { rows: Row[]; columns: Array<[string, string]>; rowTestId: string }) {
  return (
    <table>
      <thead>
        <tr>{columns.map(([key, title]) => <th key={key}>{title}</th>)}</tr>
      </thead>
      <tbody>
        {rows.map((row, i) => (
          <tr key={i} data-testid={rowTestId}>
            {columns.map(([key]) => <td key={key}>{row[key]}</td>)}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

const SLAB_COLUMNS: Array<[string, string]> = [
  ['minWeightKg', 'From kg'], ['maxWeightKg', 'To kg'], ['rateBasis', 'Basis'], ['rateAmount', 'Rate'],
];
const ENTRY_COLUMNS: Array<[string, string]> = [
  ['rankNo', 'Rank'], ['vendorName', 'Vendor'], ['responseSlaMinutes', 'Answers within (min)'],
  ['agreedRate', 'Agreed rate'],
];
const BILL_LINE_COLUMNS: Array<[string, string]> = [
  ['lineNo', '#'], ['chargeType', 'Charge'], ['narrative', 'Narrative'], ['quantity', 'Quantity'],
  ['unit', 'Unit'], ['rate', 'Rate'], ['amount', 'Amount'],
];

function blankTariff(): TariffDraft {
  return {
    code: '', vendorPartnerId: '', originTerminalId: '', destinationTerminalId: '', vehicleType: '',
    effectiveFrom: today(), currency: '', minimumCharge: '', detentionFreeHours: '', detentionHourlyRate: '',
    additionalDropFee: '',
  };
}

function blankSlab(): SlabDraft {
  return { minWeightKg: '', maxWeightKg: '', rateBasis: 'PER_KG', rateAmount: '' };
}

/** Rate cards, routing guides, allocations and freight bills: what a lane costs and who carries it. */
export function Commercial() {
  const { gateway, can, auth } = useSession();
  const vendors = useLoad(() => gateway.activeVendors(), 'vendors');
  const terminals = useLoad(() => gateway.terminalChoices(), 'terminals');

  return (
    <>
      <h1>Commercial</h1>
      <p className="muted">
        Publish rate cards, decide which vendors are offered a lane, follow loads out to vendors,
        and settle what each vendor bills.
      </p>
      <datalist id="commercial-vendors">
        {(vendors.data ?? []).map((v) => <option key={v.id} value={v.id}>{v.label}</option>)}
      </datalist>
      <datalist id="commercial-terminals">
        {(terminals.data ?? []).map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
      </datalist>
      {can('TARIFF_MANAGE') && auth?.orgUnitId && <TariffsPanel orgUnitId={auth.orgUnitId} />}
      {(can('ROUTING_GUIDE_MANAGE') || can('LOAD_ALLOCATE')) && (
        <GuidesPanel manage={can('ROUTING_GUIDE_MANAGE')} orgUnitId={auth?.orgUnitId ?? ''} />
      )}
      {(can('LOAD_READ') || can('LOAD_ALLOCATE')) && <AllocationsPanel act={can('LOAD_ALLOCATE')} />}
      {can('INVOICE_READ') && <BillsPanel approve={can('INVOICE_APPROVE')} />}
    </>
  );
}

/**
 * Rate cards. A card is a version: correcting one means publishing a successor, so there is
 * nothing to edit here -- only publish, then add the weight bands while the card is open.
 */
function TariffsPanel({ orgUnitId }: { orgUnitId: string }) {
  const { gateway } = useSession();
  const [draft, setDraft] = useState<TariffDraft>(blankTariff());
  const [publishRefusal, setPublishRefusal] = useState<Refusal | null>(null);
  const [tariffId, setTariffId] = useState('');
  const [publishedCode, setPublishedCode] = useState('');
  const [slab, setSlab] = useState<SlabDraft>(blankSlab());
  const [slabs, setSlabs] = useState<Row[]>([]);
  const [slabRefusal, setSlabRefusal] = useState<Refusal | null>(null);
  const [fuelMonth, setFuelMonth] = useState(today().slice(0, 7));
  const [fuelPct, setFuelPct] = useState('');
  const [fuelRecorded, setFuelRecorded] = useState('');
  const [fuelRefusal, setFuelRefusal] = useState<Refusal | null>(null);

  const field = (name: keyof TariffDraft) => ({
    value: draft[name],
    onChange: (e: { target: { value: string } }) => setDraft((d) => ({ ...d, [name]: e.target.value })),
  });

  async function publish(event: FormEvent) {
    event.preventDefault();
    setPublishRefusal(null);
    try {
      const id = await gateway.publishTariff(orgUnitId, draft);
      setTariffId(id);
      setPublishedCode(draft.code.trim());
      setSlabs([]);
      setDraft(blankTariff());
    } catch (e) {
      setPublishRefusal(refusalOf(e));
    }
  }

  async function addSlab(event: FormEvent) {
    event.preventDefault();
    setSlabRefusal(null);
    try {
      await gateway.addTariffSlab(tariffId, slab);
      setSlabs((rows) => [...rows, {
        minWeightKg: slab.minWeightKg, maxWeightKg: slab.maxWeightKg || '—', rateBasis: slab.rateBasis,
        rateAmount: slab.rateAmount,
      }]);
      setSlab(blankSlab());
    } catch (e) {
      setSlabRefusal(refusalOf(e));
    }
  }

  async function fuel(event: FormEvent) {
    event.preventDefault();
    setFuelRefusal(null);
    setFuelRecorded('');
    try {
      await gateway.recordFuelSurcharge(`${fuelMonth}-01`, Number(fuelPct));
      setFuelRecorded(fuelMonth);
      setFuelPct('');
    } catch (e) {
      setFuelRefusal(refusalOf(e));
    }
  }

  return (
    <section data-testid="tariffs">
      <h2>Rate cards</h2>
      <form className="card" onSubmit={publish} data-testid="publish-tariff">
        <label>Code <input {...field('code')} required /></label>
        <label>Vendor <input list="commercial-vendors" {...field('vendorPartnerId')} required /></label>
        <label>Origin <input list="commercial-terminals" {...field('originTerminalId')} required /></label>
        <label>Destination <input list="commercial-terminals" {...field('destinationTerminalId')} required /></label>
        <label>Vehicle type <input {...field('vehicleType')} required /></label>
        <label>Effective from <input type="date" {...field('effectiveFrom')} required /></label>
        <label>Currency <input placeholder="INR" {...field('currency')} /></label>
        <label>Minimum charge <input inputMode="decimal" {...field('minimumCharge')} /></label>
        <label>Free detention hours <input inputMode="decimal" {...field('detentionFreeHours')} /></label>
        <label>Detention per hour <input inputMode="decimal" {...field('detentionHourlyRate')} /></label>
        <label>Fee per additional drop <input inputMode="decimal" {...field('additionalDropFee')} /></label>
        <button type="submit">Publish rate card</button>
      </form>
      <RefusalNote refusal={publishRefusal} testId="tariff-refusal" />

      {tariffId && (
        <section data-testid="tariff-panel" aria-label="Published rate card">
          <p>Rate card <strong data-testid="tariff-code">{publishedCode}</strong> published as{' '}
            <code data-testid="tariff-id">{tariffId}</code>.</p>
          <h3>Weight bands</h3>
          {slabs.length === 0
            ? <p className="muted" data-testid="no-slabs">No band yet: the card prices nothing until it has one.</p>
            : <Table rows={slabs} columns={SLAB_COLUMNS} rowTestId="slab-row" />}
          <form className="inline" onSubmit={addSlab} data-testid="add-slab">
            <input aria-label="From kg" placeholder="From kg" inputMode="decimal" value={slab.minWeightKg}
              onChange={(e) => setSlab((s) => ({ ...s, minWeightKg: e.target.value }))} required />
            <input aria-label="To kg" placeholder="To kg (blank: no limit)" inputMode="decimal" value={slab.maxWeightKg}
              onChange={(e) => setSlab((s) => ({ ...s, maxWeightKg: e.target.value }))} />
            <select aria-label="Rate basis" value={slab.rateBasis}
              onChange={(e) => setSlab((s) => ({ ...s, rateBasis: e.target.value as SlabRateBasis }))}>
              {SLAB_RATE_BASES.map((b) => <option key={b} value={b}>{b}</option>)}
            </select>
            <input aria-label="Rate" placeholder="Rate" inputMode="decimal" value={slab.rateAmount}
              onChange={(e) => setSlab((s) => ({ ...s, rateAmount: e.target.value }))} required />
            <button type="submit">Add band</button>
          </form>
          <RefusalNote refusal={slabRefusal} testId="slab-refusal" />
        </section>
      )}

      <h3>Fuel surcharge</h3>
      <form className="inline" onSubmit={fuel} data-testid="fuel-surcharge">
        <input type="month" aria-label="Month" value={fuelMonth} onChange={(e) => setFuelMonth(e.target.value)} required />
        <input aria-label="Surcharge %" placeholder="Surcharge %" inputMode="decimal" value={fuelPct}
          onChange={(e) => setFuelPct(e.target.value)} required />
        <button type="submit">Record surcharge</button>
      </form>
      {fuelRecorded && <p data-testid="fuel-recorded">Surcharge for {fuelRecorded} recorded.</p>}
      <RefusalNote refusal={fuelRefusal} testId="fuel-refusal" />
    </section>
  );
}

/** Routing guides: who is offered a lane's loads, in what order, and how long each has to answer. */
function GuidesPanel({ manage, orgUnitId }: { manage: boolean; orgUnitId: string }) {
  const { gateway } = useSession();
  const [guide, setGuide] = useState<GuideDraft>({
    originTerminalId: '', destinationTerminalId: '', vehicleType: '', strategy: 'CONTRACTUAL',
  });
  const [defineRefusal, setDefineRefusal] = useState<Refusal | null>(null);
  const [typed, setTyped] = useState('');
  const [guideId, setGuideId] = useState('');
  const [detail, setDetail] = useState<RoutingGuideDetail | null>(null);
  const [openRefusal, setOpenRefusal] = useState<Refusal | null>(null);
  const [vendorId, setVendorId] = useState('');
  const [rank, setRank] = useState('1');
  const [sla, setSla] = useState('60');
  const [agreedRate, setAgreedRate] = useState('');
  const [actRefusal, setActRefusal] = useState<Refusal | null>(null);

  async function open(id: string) {
    setOpenRefusal(null);
    setActRefusal(null);
    try {
      setDetail(await gateway.routingGuide(id.trim()));
      setGuideId(id.trim());
    } catch (e) {
      setDetail(null);
      setOpenRefusal(refusalOf(e));
    }
  }

  async function define(event: FormEvent) {
    event.preventDefault();
    setDefineRefusal(null);
    try {
      const id = await gateway.defineRoutingGuide(orgUnitId, guide);
      setTyped(id);
      await open(id);
    } catch (e) {
      setDefineRefusal(refusalOf(e));
    }
  }

  async function addVendor(event: FormEvent) {
    event.preventDefault();
    setActRefusal(null);
    try {
      await gateway.addGuideVendor(guideId, {
        vendorPartnerId: vendorId, rankNo: Number(rank), responseSlaMinutes: Number(sla), agreedRate,
      });
      setVendorId('');
      setAgreedRate('');
      setRank((r) => String(Number(r) + 1));
      setDetail(await gateway.routingGuide(guideId));
    } catch (e) {
      setActRefusal(refusalOf(e));
    }
  }

  async function retire() {
    setActRefusal(null);
    try {
      await gateway.retireRoutingGuide(guideId);
      setDetail(await gateway.routingGuide(guideId));
    } catch (e) {
      setActRefusal(refusalOf(e));
    }
  }

  return (
    <section data-testid="guides">
      <h2>Routing guides</h2>
      {manage && orgUnitId && (
        <>
          <form className="inline" onSubmit={define} data-testid="define-guide">
            <input aria-label="Guide origin" placeholder="Origin" list="commercial-terminals" value={guide.originTerminalId}
              onChange={(e) => setGuide((g) => ({ ...g, originTerminalId: e.target.value }))} required />
            <input aria-label="Guide destination" placeholder="Destination" list="commercial-terminals"
              value={guide.destinationTerminalId}
              onChange={(e) => setGuide((g) => ({ ...g, destinationTerminalId: e.target.value }))} required />
            <input aria-label="Guide vehicle type" placeholder="Vehicle type" value={guide.vehicleType}
              onChange={(e) => setGuide((g) => ({ ...g, vehicleType: e.target.value }))} required />
            <select aria-label="Strategy" value={guide.strategy}
              onChange={(e) => setGuide((g) => ({ ...g, strategy: e.target.value as GuideStrategy }))}>
              {GUIDE_STRATEGIES.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
            <button type="submit">Define guide</button>
          </form>
          <RefusalNote refusal={defineRefusal} testId="guide-refusal" />
        </>
      )}
      <form className="inline" data-testid="open-guide"
        onSubmit={(e) => { e.preventDefault(); void open(typed); }}>
        <input aria-label="Guide id" placeholder="Guide id" value={typed} onChange={(e) => setTyped(e.target.value)} required />
        <button type="submit">Open</button>
      </form>
      <RefusalNote refusal={openRefusal} testId="guide-open-refusal" />

      {detail && (
        <section data-testid="guide-panel" aria-label="Routing guide">
          <h3>{detail.guide.originTerminalCode} → {detail.guide.destinationTerminalCode} · {detail.guide.vehicleType}</h3>
          <p>Strategy {detail.guide.strategy} ·{' '}
            <span data-testid="guide-state">{detail.guide.active ? 'Active' : 'Retired'}</span></p>
          {detail.entries.length === 0
            ? <p className="muted" data-testid="no-vendors">No vendor on this guide yet.</p>
            : <Table rows={detail.entries} columns={ENTRY_COLUMNS} rowTestId="guide-vendor" />}
          {manage && detail.guide.active && (
            <>
              <form className="inline" onSubmit={addVendor} data-testid="add-vendor">
                <input aria-label="Vendor" placeholder="Vendor" list="commercial-vendors" value={vendorId}
                  onChange={(e) => setVendorId(e.target.value)} required />
                <input aria-label="Rank" placeholder="Rank" inputMode="numeric" value={rank}
                  onChange={(e) => setRank(e.target.value)} required />
                <input aria-label="Answers within minutes" placeholder="Answers within (min)" inputMode="numeric"
                  value={sla} onChange={(e) => setSla(e.target.value)} required />
                <input aria-label="Agreed rate" placeholder="Agreed rate" inputMode="decimal" value={agreedRate}
                  onChange={(e) => setAgreedRate(e.target.value)} />
                <button type="submit">Add vendor</button>
              </form>
              <p><button className="link" data-testid="retire-guide" onClick={() => void retire()}>Retire guide</button></p>
            </>
          )}
          <RefusalNote refusal={actRefusal} testId="guide-act-refusal" />
        </section>
      )}
    </section>
  );
}

/** Allocations: a load offered to the vendors its guide names, one at a time, until one accepts. */
function AllocationsPanel({ act }: { act: boolean }) {
  const { gateway } = useSession();
  const [status, setStatus] = useState('');
  const [rows, setRows] = useState<Row[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [listRefusal, setListRefusal] = useState<Refusal | null>(null);
  const [typed, setTyped] = useState('');
  const [loadId, setLoadId] = useState('');
  const [allocateRefusal, setAllocateRefusal] = useState<Refusal | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [detail, setDetail] = useState<AllocationDetail | null>(null);
  const [actRefusal, setActRefusal] = useState<Refusal | null>(null);

  async function refresh(filter = status) {
    setListRefusal(null);
    try {
      setRows(await gateway.allocations(filter.trim().toUpperCase()));
      setLoaded(true);
    } catch (e) {
      setListRefusal(refusalOf(e));
    }
  }

  useEffect(() => {
    void refresh('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function open(id: string) {
    setOpenId(id);
    setActRefusal(null);
    try {
      setDetail(await gateway.allocationDetail(id));
    } catch (e) {
      setDetail(null);
      setActRefusal(refusalOf(e));
    }
  }

  async function allocate(event: FormEvent) {
    event.preventDefault();
    setAllocateRefusal(null);
    try {
      const id = await gateway.allocateLoad(loadId);
      setLoadId('');
      await refresh();
      await open(id);
    } catch (e) {
      setAllocateRefusal(refusalOf(e));
    }
  }

  async function respond(action: (id: string) => Promise<void>) {
    if (!openId) {
      return;
    }
    setActRefusal(null);
    try {
      await action(openId);
    } catch (e) {
      setActRefusal(refusalOf(e));
    }
    await refresh();
    try {
      setDetail(await gateway.allocationDetail(openId));
    } catch {
      // The refusal above, if any, already says what went wrong.
    }
  }

  return (
    <section data-testid="allocations">
      <h2>Allocations</h2>
      {act && (
        <>
          <form className="inline" onSubmit={allocate} data-testid="allocate-load">
            <input aria-label="Load id to allocate" placeholder="Load id" value={loadId}
              onChange={(e) => setLoadId(e.target.value)} required />
            <button type="submit">Allocate load</button>
          </form>
          <RefusalNote refusal={allocateRefusal} testId="allocate-refusal" />
        </>
      )}
      <form className="inline" data-testid="allocation-filter" onSubmit={(e) => { e.preventDefault(); void refresh(); }}>
        <input aria-label="Status" placeholder="Status (blank: all)" value={status} onChange={(e) => setStatus(e.target.value)} />
        <button type="submit">List</button>
      </form>
      <form className="inline" data-testid="open-allocation" onSubmit={(e) => { e.preventDefault(); void open(typed.trim()); }}>
        <input aria-label="Allocation id" placeholder="Allocation id" value={typed} onChange={(e) => setTyped(e.target.value)} required />
        <button type="submit">Open</button>
      </form>
      <RefusalNote refusal={listRefusal} testId="allocations-refusal" />
      {loaded && rows.length === 0 ? (
        <p className="muted" data-testid="no-allocations">No allocation matches.</p>
      ) : (
        <table>
          <thead><tr><th>Load</th><th>Status</th><th>Waiting on</th><th>Awarded to</th><th>Offers</th></tr></thead>
          <tbody>
            {rows.map((a) => (
              <tr key={a.id} data-testid="allocation-row" tabIndex={0} className={a.id === openId ? 'selected' : undefined}
                onClick={() => void open(a.id)} onKeyDown={(e) => { if (e.key === 'Enter') { void open(a.id); } }}>
                <td>{a.loadNo}</td><td>{a.status}</td><td>{a.pendingVendorName}</td>
                <td>{a.awardedVendorName}</td><td>{a.offersMade}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {detail && (
        <section data-testid="allocation-panel" aria-label="Allocation">
          <h3>Load {detail.allocation.loadNo}</h3>
          <dl>
            <div><dt>Status</dt><dd data-testid="allocation-status">{detail.allocation.status}</dd></div>
            <div><dt>Strategy</dt><dd>{detail.allocation.strategy}</dd></div>
            <div><dt>Awarded to</dt><dd data-testid="allocation-awarded">{detail.allocation.awardedVendorName}</dd></div>
            {detail.allocation.appliedRuleName && (
              <div><dt>Rule</dt><dd>{detail.allocation.appliedRuleName}</dd></div>
            )}
          </dl>
          <table>
            <thead><tr><th>Rank</th><th>Vendor</th><th>Outcome</th><th>Answer by</th><th></th></tr></thead>
            <tbody>
              {detail.offers.map((o) => (
                <tr key={o.id} data-testid="offer-row">
                  <td>{o.rankNo}</td><td>{o.vendorName}</td>
                  <td data-testid="offer-outcome">{o.outcome}</td><td>{o.respondsBy}</td>
                  <td>
                    {act && o.outcome === 'PENDING' && (
                      <>
                        <button data-testid="accept-offer"
                          onClick={() => void respond((id) => gateway.acceptOffer(id, o.vendorPartnerId))}>Accept</button>
                        <button className="link" data-testid="reject-offer"
                          onClick={() => void respond((id) => gateway.rejectOffer(id, o.vendorPartnerId))}>Reject</button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {act && detail.allocation.status !== 'CANCELLED' && detail.allocation.status !== 'AWARDED' && (
            <p><button className="link" data-testid="cancel-allocation"
              onClick={() => void respond((id) => gateway.cancelAllocation(id))}>Cancel allocation</button></p>
          )}
        </section>
      )}
      <RefusalNote refusal={actRefusal} testId="allocation-refusal" />
    </section>
  );
}

/** Freight bills: what the platform computed, what the vendor claimed, and how it settled. */
function BillsPanel({ approve }: { approve: boolean }) {
  const { gateway } = useSession();
  const [status, setStatus] = useState('');
  const [rows, setRows] = useState<Row[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [listRefusal, setListRefusal] = useState<Refusal | null>(null);
  const [typed, setTyped] = useState('');
  const [openId, setOpenId] = useState<string | null>(null);
  const [detail, setDetail] = useState<FreightBillDetail | null>(null);
  const [slip, setSlip] = useState(false);
  const [claimed, setClaimed] = useState('');
  const [reason, setReason] = useState('');
  const [outcome, setOutcome] = useState('');
  const [actRefusal, setActRefusal] = useState<Refusal | null>(null);

  async function refresh(filter = status) {
    setListRefusal(null);
    try {
      setRows(await gateway.freightBillList(filter.trim().toUpperCase()));
      setLoaded(true);
    } catch (e) {
      setListRefusal(refusalOf(e));
    }
  }

  useEffect(() => {
    void refresh('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function open(id: string) {
    const target = id.trim();
    if (target !== openId) {
      setSlip(false);
      setOutcome('');
    }
    setOpenId(target);
    setActRefusal(null);
    try {
      setDetail(await gateway.freightBill(target));
    } catch (e) {
      setDetail(null);
      setActRefusal(refusalOf(e));
    }
  }

  async function reread(id: string) {
    await refresh();
    try {
      setDetail(await gateway.freightBill(id));
    } catch {
      // The refusal above, if any, already says what went wrong.
    }
  }

  async function match(event: FormEvent) {
    event.preventDefault();
    if (!openId) {
      return;
    }
    setActRefusal(null);
    try {
      setOutcome(await gateway.matchFreightBill(openId, Number(claimed)));
      setClaimed('');
    } catch (e) {
      setActRefusal(refusalOf(e));
    }
    await reread(openId);
  }

  async function resolve(event: FormEvent) {
    event.preventDefault();
    if (!openId) {
      return;
    }
    setActRefusal(null);
    try {
      await gateway.resolveFreightBill(openId, reason);
      setReason('');
      setOutcome('');
    } catch (e) {
      setActRefusal(refusalOf(e));
    }
    await reread(openId);
  }

  return (
    <section data-testid="freight-bills">
      <h2>Freight bills</h2>
      <form className="inline" data-testid="bill-filter" onSubmit={(e) => { e.preventDefault(); void refresh(); }}>
        <input aria-label="Bill status" placeholder="Status (blank: all)" value={status} onChange={(e) => setStatus(e.target.value)} />
        <button type="submit">List</button>
      </form>
      <form className="inline" data-testid="open-bill" onSubmit={(e) => { e.preventDefault(); void open(typed); }}>
        <input aria-label="Bill id" placeholder="Bill id" value={typed} onChange={(e) => setTyped(e.target.value)} required />
        <button type="submit">Open</button>
      </form>
      <RefusalNote refusal={listRefusal} testId="bills-refusal" />
      {loaded && rows.length === 0 ? (
        <p className="muted" data-testid="no-bills">No bill matches.</p>
      ) : (
        <div className="table-scroll">
          <table>
            <thead><tr><th>Bill</th><th>Status</th><th>Vendor</th><th>Computed</th><th>Claimed</th><th>Variance</th></tr></thead>
            <tbody>
              {rows.map((b) => (
                <tr key={b.id} data-testid="bill-row" tabIndex={0} className={b.id === openId ? 'selected' : undefined}
                  onClick={() => void open(b.id)} onKeyDown={(e) => { if (e.key === 'Enter') { void open(b.id); } }}>
                  <td>{b.billNo}</td><td>{b.status}</td><td>{b.vendorName}</td>
                  <td>{b.computedAmount}</td><td>{b.claimedAmount}</td><td>{b.varianceAmount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {detail && (
        <section data-testid="bill-panel" aria-label="Freight bill">
          <header>
            <h3>{detail.bill.billNo}</h3>
            <button className="link" onClick={() => { setOpenId(null); setDetail(null); }} aria-label="Close bill">Close</button>
          </header>
          <dl>
            <div><dt>Status</dt><dd data-testid="bill-status">{detail.bill.status}</dd></div>
            <div><dt>Vendor</dt><dd>{detail.bill.vendorName}</dd></div>
            <div><dt>Trip</dt><dd>{detail.bill.tripNo}</dd></div>
            <div><dt>Rate card</dt><dd>{detail.bill.tariffCode}</dd></div>
            <div><dt>Computed</dt><dd data-testid="bill-computed">{detail.bill.computedAmount} {detail.bill.currency}</dd></div>
            <div><dt>Claimed</dt><dd data-testid="bill-claimed">{detail.bill.claimedAmount}</dd></div>
            <div><dt>Variance</dt><dd data-testid="bill-variance">{detail.bill.varianceAmount}</dd></div>
            {detail.bill.resolution && (
              <div><dt>Resolution</dt><dd data-testid="bill-resolution">{detail.bill.resolution}</dd></div>
            )}
          </dl>
          <Table rows={detail.lines} columns={BILL_LINE_COLUMNS} rowTestId="bill-line" />
          <p><button className="link" data-testid="print-bill" onClick={() => setSlip(true)}>Print bill</button></p>
          {slip && (
            <section className="slip manifest" data-testid="bill-slip" aria-label="Freight bill to print">
              <h3>Freight bill {detail.bill.billNo}</h3>
              <p>{detail.bill.vendorName} · trip {detail.bill.tripNo} · load {detail.bill.loadNo} · priced {detail.bill.pricedOn}</p>
              <Table rows={detail.lines} columns={BILL_LINE_COLUMNS} rowTestId="slip-line" />
              <p>Computed {detail.bill.computedAmount} {detail.bill.currency} · claimed {detail.bill.claimedAmount} · status {detail.bill.status}</p>
              <p className="actions no-print">
                <button data-testid="print-now" onClick={() => window.print()}>Print</button>
                <button className="link" onClick={() => setSlip(false)}>Close</button>
              </p>
            </section>
          )}
          {approve && detail.bill.status === 'DRAFT' && (
            <form className="inline" onSubmit={match} data-testid="match-bill">
              <input aria-label="Vendor's claimed amount" placeholder="Claimed amount" inputMode="decimal" value={claimed}
                onChange={(e) => setClaimed(e.target.value)} required />
              <button type="submit">Match claim</button>
            </form>
          )}
          {approve && detail.bill.status === 'DISPUTED' && (
            <form className="inline" onSubmit={resolve} data-testid="resolve-bill">
              <input aria-label="Reason for settling" placeholder="Reason" value={reason}
                onChange={(e) => setReason(e.target.value)} required />
              <button type="submit">Settle in the vendor&apos;s favour</button>
            </form>
          )}
          {outcome && <p data-testid="match-outcome">Matched: {outcome}.</p>}
        </section>
      )}
      <RefusalNote refusal={actRefusal} testId="bill-refusal" />
    </section>
  );
}
