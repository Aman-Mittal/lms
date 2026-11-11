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
import { ORG_UNIT_RANK, ORG_UNIT_TYPES, ORG_UNIT_WORDS, type OrgUnitView } from '../gateway/lmsGateway';
import { useSession } from '../session';

/** What the form beneath the tree is doing: adding a child, editing a unit, or nothing. */
type Mode = { kind: 'none' } | { kind: 'child'; parent: OrgUnitView } | { kind: 'edit'; unit: OrgUnitView };

const word = (type: string) => ORG_UNIT_WORDS[type] ?? type;
const childTypes = (u: OrgUnitView): string[] =>
  ORG_UNIT_TYPES.filter((t) => ORG_UNIT_RANK[t] > ORG_UNIT_RANK[u.unitType]);
const jurisdiction = (u: OrgUnitView) =>
  u.effectiveCountry ? (u.effectiveState ? `${u.effectiveCountry}-${u.effectiveState}` : u.effectiveCountry) : '';

/**
 * Administration > Organisation units: the tree from the caller's own unit
 * down, each unit with its level (head office, line of business, country,
 * zone or region, branch, hub), its codes and the jurisdiction it falls
 * under. A unit is added beneath a parent at a lower level and renamed,
 * retyped or recoded in place; it is never moved. Settings follow the tree:
 * a branch's profile beats its zone's, its zone's its country's.
 */
export function OrgUnits() {
  const { gateway, can } = useSession();
  const manage = can('ORG_UNIT_MANAGE');
  const [units, setUnits] = useState<OrgUnitView[]>([]);
  const [mode, setMode] = useState<Mode>({ kind: 'none' });
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [form, setForm] = useState({ slug: '', name: '', unitType: '', countryCode: '', stateCode: '' });

  async function reload() {
    try {
      setUnits(await gateway.orgUnits());
    } catch (e) {
      setError((e as Error).message);
    }
  }

  useEffect(() => {
    void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function allowedTypes(): string[] {
    if (mode.kind === 'child') {
      return childTypes(mode.parent);
    }
    if (mode.kind === 'edit') {
      const parent = units.find((p) => p.id === mode.unit.parentId);
      const children = units.filter((c) => c.parentId === mode.unit.id);
      const floor = parent ? ORG_UNIT_RANK[parent.unitType] : -1;
      const ceiling = Math.min(6, ...children.map((c) => ORG_UNIT_RANK[c.unitType]));
      return ORG_UNIT_TYPES.filter((t) => ORG_UNIT_RANK[t] > floor && ORG_UNIT_RANK[t] < ceiling);
    }
    return [];
  }

  const carriesState = ORG_UNIT_RANK[form.unitType] >= ORG_UNIT_RANK.ZONE;

  function addChild(parent: OrgUnitView) {
    setError('');
    setNotice('');
    setMode({ kind: 'child', parent });
    setForm({ slug: '', name: '', unitType: childTypes(parent)[0] ?? '', countryCode: '', stateCode: '' });
  }

  function edit(unit: OrgUnitView) {
    setError('');
    setNotice('');
    setMode({ kind: 'edit', unit });
    setForm({ slug: unit.slug, name: unit.name, unitType: unit.unitType, countryCode: unit.countryCode,
      stateCode: unit.stateCode });
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    setError('');
    setNotice('');
    const codes = {
      countryCode: form.unitType === 'COUNTRY' ? form.countryCode : '',
      stateCode: carriesState ? form.stateCode : '',
    };
    try {
      if (mode.kind === 'child') {
        await gateway.createOrgUnit({
          parentId: mode.parent.id, slug: form.slug, name: form.name, unitType: form.unitType, ...codes,
        });
        setNotice(`${form.name} added.`);
      } else if (mode.kind === 'edit') {
        const saved = await gateway.updateOrgUnit(mode.unit.id, mode.unit.version,
          { name: form.name, unitType: form.unitType, ...codes });
        setNotice(`${saved.name} saved.`);
      }
      setMode({ kind: 'none' });
      await reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const set = (field: keyof typeof form) => (v: string) => setForm((f) => ({ ...f, [field]: v }));

  return (
    <>
      <h1>Organisation units</h1>
      <p className="muted">Levels run head office, line of business, country, zone (or region), branch, hub; a unit
        sits below its parent's level. A country carries its ISO code and a zone, branch or hub may carry a state
        code: platform rules for that country and state then apply to everything beneath.</p>
      {error && <p className="error" role="alert">{error}</p>}

      <table data-testid="org-units">
        <thead><tr><th>Unit</th><th>Level</th><th>Codes</th><th>Jurisdiction</th><th /></tr></thead>
        <tbody>
          {units.length === 0 && <tr><td colSpan={5} className="muted">No units.</td></tr>}
          {units.map((u) => (
            <tr key={u.id} data-testid="org-unit-row" data-id={u.id} data-path={u.path}>
              <td style={{ paddingLeft: `${Math.max(0, u.path.split('/').length - 3) * 1.25}rem` }}>
                <strong>{u.name}</strong> <span className="muted">{u.path}</span>
              </td>
              <td data-testid="org-unit-level">{word(u.unitType)}</td>
              <td>{[u.countryCode, u.stateCode].filter((c) => c).join(' / ')}</td>
              <td data-testid="org-unit-jurisdiction">{jurisdiction(u)}</td>
              <td>
                {manage && childTypes(u).length > 0 && (
                  <button type="button" className="link" data-testid="add-child" onClick={() => addChild(u)}>Add child</button>
                )}
                {manage && (
                  <button type="button" className="link" data-testid="edit-unit" onClick={() => edit(u)}>Edit</button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {mode.kind !== 'none' && (
        <section className="panel in-flow" data-testid="unit-panel">
          <header>
            <h2>{mode.kind === 'child' ? `Add a unit beneath ${mode.parent.name}` : `Edit ${mode.unit.name}`}</h2>
          </header>
          <form className="inline" onSubmit={save} data-testid="unit-form">
            {mode.kind === 'child' && (
              <input aria-label="Slug" placeholder="slug" data-testid="unit-slug" required value={form.slug}
                onChange={(e) => set('slug')(e.target.value)} />
            )}
            <input aria-label="Unit name" placeholder="Name" data-testid="unit-name" required value={form.name}
              onChange={(e) => set('name')(e.target.value)} />
            <select aria-label="Level" data-testid="unit-type" value={form.unitType}
              onChange={(e) => set('unitType')(e.target.value)}>
              {allowedTypes().map((t) => <option key={t} value={t}>{word(t)}</option>)}
            </select>
            {form.unitType === 'COUNTRY' && (
              <input aria-label="Country code" placeholder="Country (ISO, e.g. IN)" maxLength={2}
                data-testid="unit-country" value={form.countryCode} onChange={(e) => set('countryCode')(e.target.value)} />
            )}
            {carriesState && (
              <input aria-label="State code" placeholder="State (optional)" maxLength={3} data-testid="unit-state"
                value={form.stateCode} onChange={(e) => set('stateCode')(e.target.value)} />
            )}
            <button type="submit" data-testid="save-unit">{mode.kind === 'child' ? 'Add unit' : 'Save'}</button>
            <button type="button" className="link" onClick={() => setMode({ kind: 'none' })}>Cancel</button>
          </form>
        </section>
      )}
      {notice && <p className="ok" role="status" data-testid="unit-saved">{notice}</p>}
    </>
  );
}
