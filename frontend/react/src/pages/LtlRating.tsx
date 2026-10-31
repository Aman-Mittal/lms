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
import { RATE_BASES, type RateBasis } from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { RowTable } from './RowTable';

/**
 * Part-load rating: the receivable each docket earned, and the two things a
 * tariff desk sets up for it -- which zone a site is in, and the zone-to-zone
 * rate card. Zones and rate cards have no list endpoint yet, so they are
 * written here and seen through the receivables they price.
 */
export function LtlRating() {
  const { can } = useSession();
  return (
    <>
      <h1>LTL rating</h1>
      <p className="muted">Part-load dockets are priced from the zones of their sites and the rate card between them.</p>
      {can('INVOICE_READ') && <Receivables />}
      {can('TARIFF_MANAGE') ? (
        <>
          <ZoneForm />
          <RateCardForm />
        </>
      ) : (
        <p className="muted" data-testid="no-tariff-rights">You do not have the right to manage tariffs.</p>
      )}
    </>
  );
}

function Receivables() {
  const { gateway } = useSession();
  const list = useLoad(() => gateway.ltlReceivables(), 'receivables');
  return (
    <section>
      <h2>Docket receivables</h2>
      {list.loading && <p className="muted">Loading…</p>}
      {list.error && <p className="error" role="alert">{list.error}</p>}
      {list.data && list.data.length === 0 && (
        <p className="muted" data-testid="no-receivables">No LTL docket has been priced yet.</p>
      )}
      {list.data && list.data.length > 0 && (
        <RowTable testId="receivables" rows={list.data} columns={[
          { header: 'Docket', field: 'docketNo' }, { header: 'Customer', field: 'customerName' },
          { header: 'Lane', field: 'lane' }, { header: 'Rate card', field: 'rateCardCode' },
          { header: 'Chargeable kg', field: 'chargeableWeightKg' }, { header: 'Total', field: 'totalAmount' },
          { header: 'Status', field: 'status' },
        ]} />
      )}
    </section>
  );
}

function ZoneForm() {
  const { gateway } = useSession();
  const sites = useLoad(() => gateway.terminalChoices(), 'sites');
  const [terminalId, setTerminalId] = useState('');
  const [zoneCode, setZoneCode] = useState('');
  const [oda, setOda] = useState(false);
  const [done, setDone] = useState('');
  const [error, setError] = useState('');

  async function assign(event: FormEvent) {
    event.preventDefault();
    setDone('');
    setError('');
    try {
      await gateway.assignLtlZone(terminalId, zoneCode.trim(), oda);
      setDone(`Zone ${zoneCode.trim()} assigned.`);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <section>
      <h2>Put a site in a zone</h2>
      <form className="inline" onSubmit={assign} data-testid="zone-form">
        <select aria-label="Site" data-testid="zone-terminal" value={terminalId}
          onChange={(e) => setTerminalId(e.target.value)} required>
          <option value="">Site…</option>
          {(sites.data ?? []).map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
        </select>
        <input placeholder="Zone code" aria-label="Zone code" data-testid="zone-code" value={zoneCode}
          onChange={(e) => setZoneCode(e.target.value)} required />
        <label>
          <input type="checkbox" data-testid="zone-oda" checked={oda} onChange={(e) => setOda(e.target.checked)} />
          {' '}Out of delivery area
        </label>
        <button type="submit" data-testid="assign-zone">Assign</button>
      </form>
      {done && <p data-testid="zone-done">{done}</p>}
      {error && <p className="error" role="alert">{error}</p>}
    </section>
  );
}

function RateCardForm() {
  const { gateway } = useSession();
  const [code, setCode] = useState('');
  const [originZone, setOriginZone] = useState('');
  const [destinationZone, setDestinationZone] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState(new Date().toISOString().slice(0, 10));
  const [rateBasis, setRateBasis] = useState<RateBasis>('PER_KG');
  const [rateAmount, setRateAmount] = useState('');
  const [kgPerCft, setKgPerCft] = useState('6');
  const [minimum, setMinimum] = useState('');
  const [docketCharge, setDocketCharge] = useState('');
  const [done, setDone] = useState('');
  const [error, setError] = useState('');

  async function publish(event: FormEvent) {
    event.preventDefault();
    setDone('');
    setError('');
    try {
      await gateway.publishLtlRateCard({
        code: code.trim(), originZone: originZone.trim(), destinationZone: destinationZone.trim(), effectiveFrom,
        rateBasis, rateAmount: Number(rateAmount), kgPerCft: Number(kgPerCft),
        minimumChargeableWeightKg: minimum ? Number(minimum) : undefined,
        docketCharge: docketCharge ? Number(docketCharge) : undefined,
      });
      setDone(`Rate card ${code.trim()} published.`);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <section>
      <h2>Publish a rate card</h2>
      <form className="inline" onSubmit={publish} data-testid="rate-card-form">
        <input placeholder="Code" aria-label="Rate card code" data-testid="rc-code" value={code}
          onChange={(e) => setCode(e.target.value)} required />
        <input placeholder="From zone" aria-label="From zone" data-testid="rc-origin" value={originZone}
          onChange={(e) => setOriginZone(e.target.value)} required />
        <input placeholder="To zone" aria-label="To zone" data-testid="rc-destination" value={destinationZone}
          onChange={(e) => setDestinationZone(e.target.value)} required />
        <input type="date" aria-label="Effective from" data-testid="rc-from" value={effectiveFrom}
          onChange={(e) => setEffectiveFrom(e.target.value)} required />
        <select aria-label="Rate basis" data-testid="rc-basis" value={rateBasis}
          onChange={(e) => setRateBasis(e.target.value as RateBasis)}>
          {RATE_BASES.map((b) => <option key={b} value={b}>{b}</option>)}
        </select>
        <input type="number" step="0.01" placeholder="Rate" aria-label="Rate" data-testid="rc-rate" value={rateAmount}
          onChange={(e) => setRateAmount(e.target.value)} required />
        <input type="number" step="0.01" placeholder="kg per cft" aria-label="kg per cft" data-testid="rc-kgcft"
          value={kgPerCft} onChange={(e) => setKgPerCft(e.target.value)} required />
        <input type="number" step="0.01" placeholder="Minimum kg" aria-label="Minimum chargeable kg" data-testid="rc-min"
          value={minimum} onChange={(e) => setMinimum(e.target.value)} />
        <input type="number" step="0.01" placeholder="Docket charge" aria-label="Docket charge" data-testid="rc-docket"
          value={docketCharge} onChange={(e) => setDocketCharge(e.target.value)} />
        <button type="submit" data-testid="publish-rate-card">Publish</button>
      </form>
      {done && <p data-testid="rate-card-done">{done}</p>}
      {error && <p className="error" role="alert">{error}</p>}
    </section>
  );
}
