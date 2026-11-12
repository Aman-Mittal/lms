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
  CHARGE_BASES, CHARGE_DIRECTIONS, type ChargeBasisChoice, type ChargeDirectionChoice, type ChargeEntryView,
} from '../gateway/lmsGateway';
import { useSession } from '../session';
import { money, problemText } from './moneyProblem';

/**
 * The tenant's catalogue of manual charges. Keeping it needs CHARGE_CATALOGUE_MANAGE; an entry is
 * switched off rather than deleted, and an edit carries the version it was read at.
 */
export function ChargeCatalogue() {
  const { gateway, can } = useSession();
  const [entries, setEntries] = useState<ChargeEntryView[]>([]);
  const [search, setSearch] = useState('');
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');
  const [reload, setReload] = useState(0);

  // the form; `editing` holds the entry being edited, with the version it was read at
  const [editing, setEditing] = useState<ChargeEntryView | null>(null);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [basis, setBasis] = useState<ChargeBasisChoice>('FLAT');
  const [rate, setRate] = useState('');
  const [direction, setDirection] = useState<ChargeDirectionChoice>('BOTH');
  const [active, setActive] = useState(true);

  useEffect(() => {
    let ignore = false;
    gateway.listChargeCatalogue({ active: null, q: search })
      .then((rows) => { if (!ignore) { setEntries(rows); } })
      .catch((e: unknown) => { if (!ignore) { setError(problemText(e)); } });
    return () => { ignore = true; };
  }, [gateway, search, reload]);

  function reset() {
    setEditing(null);
    setCode('');
    setName('');
    setBasis('FLAT');
    setRate('');
    setDirection('BOTH');
    setActive(true);
  }

  function edit(entry: ChargeEntryView) {
    setError('');
    setSaved('');
    // The version is read fresh so the edit does not rest on what the list showed earlier.
    gateway.getChargeCatalogueEntry(entry.id).then((fresh) => {
      setEditing(fresh);
      setCode(fresh.code);
      setName(fresh.name);
      setBasis(fresh.basis);
      setRate(String(fresh.defaultRate));
      setDirection(fresh.direction);
      setActive(fresh.active);
    }).catch((e: unknown) => setError(problemText(e)));
  }

  function save(event: FormEvent) {
    event.preventDefault();
    setError('');
    setSaved('');
    const draft = { code, name, basis, defaultRate: rate, direction, active };
    const done = editing
      ? gateway.updateChargeCatalogueEntry(editing.id, editing.version, draft)
      : gateway.createChargeCatalogueEntry(draft).then(() => undefined);
    done.then(() => {
      setSaved(editing ? `Saved ${editing.code}.` : `Added ${code.trim().toUpperCase()}.`);
      reset();
      setReload((n) => n + 1);
    }).catch((e: unknown) => setError(problemText(e)));
  }

  return (
    <>
      <h1>Charge catalogue</h1>
      <p className="muted">The charges raised by hand against a trip, consignment or order, with their basis and default rate.</p>
      {error && <p className="error" role="alert" data-testid="cc-error">{error}</p>}
      {saved && <p className="ok" role="status" data-testid="cc-saved">{saved}</p>}

      {can('CHARGE_CATALOGUE_MANAGE') && (
        <form data-testid="cc-form" onSubmit={save}>
          <h2>{editing ? `Edit ${editing.code}` : 'Add a charge'}</h2>
          <input placeholder="Code" aria-label="Code" data-testid="cc-code" required disabled={editing !== null}
            value={code} onChange={(e) => setCode(e.target.value)} />
          <input placeholder="Name" aria-label="Name" data-testid="cc-name" required value={name}
            onChange={(e) => setName(e.target.value)} />
          <select aria-label="Basis" data-testid="cc-basis" value={basis}
            onChange={(e) => setBasis(e.target.value as ChargeBasisChoice)}>
            {CHARGE_BASES.map((b) => <option key={b} value={b}>{b}</option>)}
          </select>
          <input type="number" min="0" step="0.01" placeholder="Default rate" aria-label="Default rate"
            data-testid="cc-rate" required value={rate} onChange={(e) => setRate(e.target.value)} />
          <select aria-label="Direction" data-testid="cc-direction" value={direction}
            onChange={(e) => setDirection(e.target.value as ChargeDirectionChoice)}>
            {CHARGE_DIRECTIONS.map((d) => <option key={d} value={d}>{d}</option>)}
          </select>
          <label>
            <input type="checkbox" data-testid="cc-active" checked={active}
              onChange={(e) => setActive(e.target.checked)} /> Active
          </label>
          <button type="submit" data-testid="cc-save">{editing ? 'Save changes' : 'Add charge'}</button>
          <button type="button" className="link" data-testid="cc-reset" onClick={reset}>Reset</button>
        </form>
      )}

      <h2>Charges</h2>
      <input type="search" placeholder="Search code or name" aria-label="Search charges" data-testid="cc-search"
        value={search} onChange={(e) => setSearch(e.target.value)} />
      {entries.length === 0 ? (
        <p className="muted">No charge matches.</p>
      ) : (
        <table data-testid="cc-list">
          <thead><tr><th>Code</th><th>Name</th><th>Basis</th><th>Default rate</th><th>Direction</th><th>Active</th><th /></tr></thead>
          <tbody>
            {entries.map((e) => (
              <tr key={e.id} data-testid="cc-row">
                <td>{e.code}</td><td>{e.name}</td><td>{e.basis}</td><td>{money(e.defaultRate)}</td>
                <td>{e.direction}</td><td>{e.active ? 'Yes' : 'No'}</td>
                <td>
                  {can('CHARGE_CATALOGUE_MANAGE') && (
                    <button type="button" className="link" data-testid="cc-edit" aria-label={`Edit ${e.code}`}
                      onClick={() => edit(e)}>Edit</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
