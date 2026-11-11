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
  SETTING_SCOPES, type EffectiveSettingView, type PickKind, type SettingChange, type SettingDefinitionView,
  type SettingHistoryRow, type SettingProfileView, type SettingScope,
} from '../gateway/lmsGateway';
import { useSession } from '../session';
import { choicesOf, controlOf, fromInput, todayIso, toInput } from '../settingsForm';

const SCOPE_LABEL: Record<string, string> = {
  ORG_DEFAULT: 'Organisation default', ORG_UNIT: 'Org unit', CUSTOMER: 'Customer', VEHICLE: 'Vehicle',
};
const PICKER: Record<string, PickKind> = { ORG_UNIT: 'org-unit', CUSTOMER: 'customer', VEHICLE: 'vehicle' };
const label = (s: string) => SCOPE_LABEL[s] ?? s;

/**
 * Administration > Profile settings: the company's setting profiles, one per
 * scope (the organisation, a unit, a customer, a vehicle), their values with
 * an input for each setting's type, the history of each, and what is in force
 * for a context and where it comes from. A dated setting changes only by a
 * new version from today or later; versions already in force are shown, not
 * edited.
 */
export function ProfileSettings() {
  const { gateway, can } = useSession();
  const manage = can('SETTINGS_MANAGE');
  const [definitions, setDefinitions] = useState<SettingDefinitionView[]>([]);
  const [filter, setFilter] = useState('');
  const [profiles, setProfiles] = useState<SettingProfileView[]>([]);
  const [picked, setPicked] = useState<SettingProfileView | null>(null);
  const [typed, setTyped] = useState<Record<string, string>>({});
  const [from, setFrom] = useState<Record<string, string>>({});
  const [history, setHistory] = useState<SettingHistoryRow[]>([]);
  const [effective, setEffective] = useState<EffectiveSettingView[]>([]);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [newScope, setNewScope] = useState<SettingScope>('ORG_DEFAULT');
  const [newScopeId, setNewScopeId] = useState('');
  const [newName, setNewName] = useState('');
  const [ctx, setCtx] = useState({ orgUnitId: '', customerId: '', vehicleId: '', asOf: '' });

  const allowed = picked ? definitions.filter((d) => d.scopes.includes(picked.scopeType)) : [];
  const dated = allowed.some((d) => d.effectiveDated);

  async function reload(scope = filter) {
    setProfiles(await gateway.settingProfiles((scope || undefined) as SettingScope | undefined));
  }

  useEffect(() => {
    let ignore = false;
    Promise.all([gateway.settingDefinitions(), gateway.settingProfiles()])
      .then(([defs, list]) => { if (!ignore) { setDefinitions(defs); setProfiles(list); } })
      .catch((e: Error) => { if (!ignore) { setError(e.message); } });
    return () => { ignore = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function show(p: SettingProfileView) {
    setPicked(p);
    const keys = definitions.filter((d) => d.scopes.includes(p.scopeType)).map((d) => d.key);
    setTyped(Object.fromEntries(keys.map((k) => [k, toInput(p.values[k])])));
    setFrom(Object.fromEntries(keys.map((k) => [k, todayIso()])));
  }

  async function guarded(action: () => Promise<void>) {
    setError('');
    setNotice('');
    try {
      await action();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const open = (id: string) => guarded(async () => {
    show(await gateway.settingProfile(id));
    setHistory(await gateway.settingProfileHistory(id));
  });

  async function apply(p: SettingProfileView, changes: SettingChange[]) {
    const saved = await gateway.updateSettingProfile(p.id, p.version, changes);
    show(saved);
    setHistory(await gateway.settingProfileHistory(p.id));
    setNotice('Saved.');
    await reload();
  }

  function create(event: FormEvent) {
    event.preventDefault();
    void guarded(async () => {
      const id = await gateway.createSettingProfile({
        scopeType: newScope, scopeId: newScope === 'ORG_DEFAULT' ? '' : newScopeId, name: newName,
      });
      setNewName('');
      setNewScopeId('');
      await reload();
      show(await gateway.settingProfile(id));
      setHistory(await gateway.settingProfileHistory(id));
    });
  }

  function saveProfile(event: FormEvent) {
    event.preventDefault();
    if (!picked) {
      return;
    }
    void guarded(async () => {
      const changes: SettingChange[] = [];
      for (const d of allowed) {
        const text = typed[d.key] ?? '';
        if (text === '' || text === toInput(picked.values[d.key])) {
          continue;
        }
        changes.push({ key: d.key, effectiveFrom: d.effectiveDated ? (from[d.key] || todayIso()) : '',
          value: fromInput(d, text) });
      }
      await apply(picked, changes);
    });
  }

  const clear = (key: string) => picked && guarded(() => apply(picked, [{ key, effectiveFrom: '', value: null }]));

  const remove = () => picked && guarded(async () => {
    await gateway.deleteSettingProfile(picked.id, picked.version);
    setPicked(null);
    await reload();
  });

  function resolve(event: FormEvent) {
    event.preventDefault();
    void guarded(async () => setEffective(await gateway.effectiveSettings(ctx)));
  }

  return (
    <>
      <h1>Profile settings</h1>
      <p className="muted">The most specific profile that sets a value wins: organisation, then unit (deeper first),
        then vehicle or customer. A person's own preferences sit on top of those, under My profile.</p>
      {error && <p className="error" role="alert">{error}</p>}

      <label>
        Scope
        <select data-testid="profile-filter" value={filter}
          onChange={(e) => { setFilter(e.target.value); void guarded(() => reload(e.target.value)); }}>
          <option value="">All</option>
          {SETTING_SCOPES.map((s) => <option key={s} value={s}>{label(s)}</option>)}
        </select>
      </label>
      <table data-testid="profiles">
        <thead><tr><th>Name</th><th>Scope</th><th>For</th><th>Settings</th></tr></thead>
        <tbody>
          {profiles.length === 0 && <tr><td colSpan={4} className="muted">No profiles yet.</td></tr>}
          {profiles.map((p) => (
            <tr key={p.id} data-testid="profile-row" data-id={p.id} className={picked?.id === p.id ? 'selected' : undefined}>
              <td><button type="button" className="link" onClick={() => open(p.id)}>{p.name}</button></td>
              <td>{label(p.scopeType)}</td>
              <td>{p.scopeLabel}</td>
              <td>{Object.keys(p.values).join(', ')}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {manage && (
        <>
          <h2>Add a profile</h2>
          <form className="inline" onSubmit={create} data-testid="add-profile">
            <select aria-label="Scope" data-testid="new-scope" value={newScope}
              onChange={(e) => setNewScope(e.target.value as SettingScope)}>
              {SETTING_SCOPES.map((s) => <option key={s} value={s}>{label(s)}</option>)}
            </select>
            {newScope !== 'ORG_DEFAULT' && (
              <EntityPicker key={newScope} kind={PICKER[newScope]} ariaLabel="Profile scope" testId="new-scope-id"
                placeholder="Which one" value={newScopeId} onChange={setNewScopeId} />
            )}
            <input placeholder="Name" aria-label="Profile name" data-testid="new-name" value={newName}
              onChange={(e) => setNewName(e.target.value)} />
            <button type="submit" data-testid="create-profile">Add profile</button>
          </form>
        </>
      )}

      {picked && (
        <section className="panel" data-testid="profile-panel">
          <header><h2>{picked.name}</h2><span className="muted">{label(picked.scopeType)} {picked.scopeLabel}</span></header>
          <form onSubmit={saveProfile} data-testid="profile-values">
            <table>
              <thead><tr><th>Setting</th><th>Value</th>{dated && <th>From</th>}<th /></tr></thead>
              <tbody>
                {allowed.map((d) => {
                  const control = controlOf(d);
                  const set = (v: string) => setTyped((t) => ({ ...t, [d.key]: v }));
                  return (
                    <tr key={d.key} data-testid={`setting-${d.key}`}>
                      <td title={d.description}>{d.label}<br /><span className="muted">{d.key}</span></td>
                      <td>
                        {control === 'select' ? (
                          <select aria-label={d.label} data-testid={`value-${d.key}`} value={typed[d.key] ?? ''}
                            disabled={!manage} onChange={(e) => set(e.target.value)}>
                            <option value="">(not set here)</option>
                            {choicesOf(d).map((c) => <option key={c} value={c}>{c}</option>)}
                          </select>
                        ) : (
                          <input aria-label={d.label} data-testid={`value-${d.key}`} type={control}
                            value={typed[d.key] ?? ''} disabled={!manage} placeholder="(not set here)"
                            onChange={(e) => set(e.target.value)} />
                        )}
                      </td>
                      {dated && (
                        <td>
                          {d.effectiveDated && (
                            <input type="date" aria-label={`${d.label} from`} data-testid={`from-${d.key}`}
                              value={from[d.key] ?? ''} disabled={!manage}
                              onChange={(e) => setFrom((f) => ({ ...f, [d.key]: e.target.value }))} />
                          )}
                        </td>
                      )}
                      <td>
                        {manage && !d.effectiveDated && picked.values[d.key] !== undefined && (
                          <button type="button" className="link" data-testid={`clear-${d.key}`}
                            onClick={() => clear(d.key)}>Clear</button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {manage && (
              <p>
                <button type="submit" data-testid="save-profile">Save</button>
                {picked.scopeType !== 'ORG_DEFAULT' && (
                  <button type="button" className="link" data-testid="delete-profile" onClick={remove}>Delete profile</button>
                )}
              </p>
            )}
            {notice && <p className="ok" role="status" data-testid="profile-saved">{notice}</p>}
          </form>
          {picked.versions.length > 0 && (
            <>
              <h3>Versions</h3>
              <table data-testid="versions">
                <thead><tr><th>Setting</th><th>From</th><th>Value</th><th>Status</th></tr></thead>
                <tbody>
                  {picked.versions.map((v) => (
                    <tr key={v.key + v.effectiveFrom}>
                      <td>{v.key}</td><td>{v.effectiveFrom || 'always'}</td><td>{toInput(v.value)}</td><td>{v.status}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
          <h3>History</h3>
          <table data-testid="history">
            <thead><tr><th>When</th><th>Who</th><th>What</th><th>Setting</th><th>Before</th><th>After</th></tr></thead>
            <tbody>
              {history.map((h) => (
                <tr key={h.id} data-testid="history-row">
                  <td>{h.occurredAt}</td><td>{h.actor}</td><td>{h.action}</td>
                  <td>{h.setting}</td><td>{h.before}</td><td>{h.after}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      <h2>In force for</h2>
      <form className="inline" onSubmit={resolve} data-testid="effective-form">
        <EntityPicker kind="org-unit" ariaLabel="Context unit" testId="ctx-unit" placeholder="Unit"
          value={ctx.orgUnitId} onChange={(v) => setCtx((c) => ({ ...c, orgUnitId: v }))} />
        <EntityPicker kind="customer" ariaLabel="Context customer" testId="ctx-customer" placeholder="Customer"
          value={ctx.customerId} onChange={(v) => setCtx((c) => ({ ...c, customerId: v }))} />
        <EntityPicker kind="vehicle" ariaLabel="Context vehicle" testId="ctx-vehicle" placeholder="Vehicle"
          value={ctx.vehicleId} onChange={(v) => setCtx((c) => ({ ...c, vehicleId: v }))} />
        <input type="date" aria-label="As of" data-testid="ctx-as-of" value={ctx.asOf}
          onChange={(e) => setCtx((c) => ({ ...c, asOf: e.target.value }))} />
        <button type="submit" data-testid="show-effective">Show</button>
      </form>
      {effective.length > 0 && (
        <table data-testid="effective">
          <thead><tr><th>Setting</th><th>Value</th><th>From</th></tr></thead>
          <tbody>
            {effective.map((e) => (
              <tr key={e.key} data-testid={`effective-${e.key}`}>
                <td>{e.label}</td><td>{toInput(e.value)}</td><td>{e.source}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
