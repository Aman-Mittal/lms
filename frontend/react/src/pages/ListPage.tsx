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
import { PARTNER_TYPES, type PartnerType } from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { RESOURCES } from './resources';

export function ListPage() {
  const { resource: key } = useParams(); // the :resource part of the URL
  const resource = RESOURCES.find((r) => r.key === key);
  const { auth, gateway } = useSession();

  // Bumping this number makes useLoad fetch again (e.g. after registering).
  const [reload, setReload] = useState(0);
  // The effect also re-runs when the route changes, because `key` is in here.
  const { data, error, loading } = useLoad(
    () => (resource ? gateway.list(resource.key) : Promise.resolve([])),
    `${key}:${reload}`,
  );

  if (!resource) {
    return <p>Unknown screen.</p>;
  }
  const rows = data ?? [];

  return (
    <>
      <h1>{resource.title}</h1>
      {error && <p className="error" role="alert">{error}</p>}
      {loading ? (
        <p>Loading…</p>
      ) : rows.length === 0 && !error ? (
        <p className="muted">Nothing here yet.</p>
      ) : (
        rows.length > 0 && (
          <table>
            <thead>
              <tr>{resource.columns.map((c) => <th key={c.field}>{c.header}</th>)}</tr>
            </thead>
            <tbody>
              {rows.map((row, i) => (
                <tr key={i}>
                  {resource.columns.map((c) => <td key={c.field}>{row[c.field]}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        )
      )}
      {resource.key === 'partners' && auth?.orgUnitId && (
        <RegisterPartner orgUnitId={auth.orgUnitId} onDone={() => setReload((n) => n + 1)} />
      )}
    </>
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
      <form className="inline" onSubmit={submit}>
        <input placeholder="Code" value={code} onChange={(e) => setCode(e.target.value)} required />
        <input placeholder="Legal name" value={legalName} onChange={(e) => setLegalName(e.target.value)} required />
        <select value={partnerType} onChange={(e) => setPartnerType(e.target.value as PartnerType)}>
          {PARTNER_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
        <button type="submit">Register</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
    </>
  );
}
