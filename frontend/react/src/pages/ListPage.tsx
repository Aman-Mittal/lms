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
import { useParams } from 'react-router-dom';
import { PARTNER_TYPES, type MasterKind, type PartnerType } from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { RecordPanel } from './RecordPanel';
import { RESOURCES } from './resources';
import { PageHeader } from './PageHeader';
import { Cell } from './Cell';
import { MasterDataForm } from './MasterDataForm';
import { CREATE_PERMISSION, KIND_LABEL, STATUS_OPTIONS } from './masterDataFields';
import { isDateColumn, isNumericColumn } from '../../../shared/format';

export function ListPage() {
  const { resource: key } = useParams(); // the :resource part of the URL
  const resource = RESOURCES.find((r) => r.key === key);
  const { auth, gateway, can } = useSession();
  const kind: MasterKind | null = resource && resource.key in KIND_LABEL ? (resource.key as MasterKind) : null;
  const [status, setStatus] = useState('');
  const [creating, setCreating] = useState(false);

  // Bumping this number makes useLoad fetch again (e.g. after registering).
  const [reload, setReload] = useState(0);
  const [query, setQuery] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);

  // The effect also re-runs when the route changes, because `key` is in here.
  const { data, error, loading } = useLoad(
    () => (resource ? gateway.list(resource.key, kind ? { status } : undefined) : Promise.resolve([])),
    `${key}:${reload}:${status}`,
  );

  if (!resource) {
    return <p>Unknown screen.</p>;
  }

  /*
   * REACT CONCEPT: derived state.
   * `visible` is computed from `data` and `query` on every render. It is not
   * stored in state: a second copy of the same information can only drift
   * away from the first.
   */
  const rows = data ?? [];
  const needle = query.trim().toLowerCase();
  const visible = needle
    ? rows.filter((row) => Object.values(row).some((cell) => cell.toLowerCase().includes(needle)))
    : rows;
  const selected = rows.find((row) => row.id === selectedId) ?? null;

  return (
    <div className={selected || creating ? 'with-panel' : undefined}>
      <div className="list">
        <PageHeader title={resource.title}>
          {kind && can(CREATE_PERMISSION[kind]) && auth?.orgUnitId && (
            <button type="button" data-testid="new-record" onClick={() => { setSelectedId(null); setCreating(true); }}>
              New {KIND_LABEL[kind]}
            </button>
          )}
        </PageHeader>
        {kind && (
          <div className="field field--inline">
            <label htmlFor="status-filter">Status</label>
            <select id="status-filter" data-testid="status-filter" value={status}
              onChange={(e) => { setStatus(e.target.value); setSelectedId(null); }}>
              <option value="">Active</option>
              <option value="ALL">All</option>
              {STATUS_OPTIONS[kind].map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </div>
        )}
        <input
          type="search" aria-label="Search" placeholder="Search" data-testid="search"
          value={query} onChange={(e) => setQuery(e.target.value)}
        />
        {error && <p className="error" role="alert">{error}</p>}
        <div className="table-wrap">
          <table>
            <thead>
              <tr>{resource.columns.map((c) => <th key={c.field} className={isNumericColumn(c.field) ? 'num' : undefined}>{c.header}</th>)}</tr>
            </thead>
            <tbody>
              {loading ? (
                <tr className="loading-row"><td className="table-state" colSpan={resource.columns.length}>Loading…</td></tr>
              ) : visible.length === 0 && !error ? (
                <tr className="empty-row">
                  <td className="table-state" colSpan={resource.columns.length}>{rows.length === 0 ? 'Nothing here yet.' : 'No match.'}</td>
                </tr>
              ) : visible.map((row) => (
                <tr
                  key={row.id} data-testid="row" data-id={row.id} tabIndex={0}
                  className={row.id === selectedId ? 'selected' : undefined}
                  onClick={() => { setCreating(false); setSelectedId(row.id); }}
                  onKeyDown={(e) => { if (e.key === 'Enter') { setSelectedId(row.id); } }}
                >
                  {resource.columns.map((c) => (
                    <td key={c.field} className={isNumericColumn(c.field) ? 'num' : isDateColumn(c.field) ? 'nowrap' : undefined}>
                      <Cell field={c.field} value={row[c.field]} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {resource.key === 'partners' && auth?.orgUnitId && (
          <RegisterPartner orgUnitId={auth.orgUnitId} onDone={() => setReload((n) => n + 1)} />
        )}
      </div>
      {/*
        * REACT CONCEPT: key resets state.
        * A component keeps its state while it stays at the same place in the
        * tree. Giving the panel the record's id as its key makes React treat a
        * different record as a different panel, so a half-typed form is never
        * carried from one record to the next.
        */}
      {creating && kind && auth?.orgUnitId && (
        <MasterDataForm key="new" kind={kind} orgUnitId={auth.orgUnitId} onCancel={() => setCreating(false)}
          onSaved={() => { setCreating(false); setReload((n) => n + 1); }} />
      )}
      {selected && !creating && (
        <RecordPanel key={selected.id} resource={resource} row={selected}
          onClose={() => setSelectedId(null)} onChanged={() => setReload((n) => n + 1)} />
      )}
    </div>
  );
}

function RegisterPartner({ orgUnitId, onDone }: { orgUnitId: string; onDone: () => void }) {
  const { gateway } = useSession();
  const [code, setCode] = useState('');
  const [legalName, setLegalName] = useState('');
  const [partnerType, setPartnerType] = useState<PartnerType>('CUSTOMER');
  const [error, setError] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      await gateway.registerPartner(orgUnitId, { code, legalName, partnerType });
      setCode('');
      setLegalName('');
      onDone(); // tell the parent to reload its list: data flows down, events flow up
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <h2>Register a partner</h2>
      <form className="inline" onSubmit={submit} data-testid="register-partner">
        <input placeholder="Code" value={code} onChange={(e) => setCode(e.target.value)} required />
        <input placeholder="Legal name" value={legalName} onChange={(e) => setLegalName(e.target.value)} required />
        <select aria-label="Partner type" value={partnerType} onChange={(e) => setPartnerType(e.target.value as PartnerType)}>
          {PARTNER_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
        <button type="submit">Register</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
    </>
  );
}
