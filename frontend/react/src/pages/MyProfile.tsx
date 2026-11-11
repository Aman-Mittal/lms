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
import { applyDisplay } from '../display';
import type {
  EffectiveSettingView, MeView, MyPreferencesView, SettingDefinitionView, SettingValue,
} from '../gateway/lmsGateway';
import { useSession } from '../session';
import { choicesOf, controlOf, fromInput, toInput } from '../settingsForm';

/** One notification event and its two channels. */
interface NotifyRow {
  event: string;
  label: string;
  inApp: string;
  email: string;
}

function notifyRows(effective: EffectiveSettingView[]): NotifyRow[] {
  const events = new Map<string, NotifyRow>();
  for (const e of effective.filter((x) => x.category === 'NOTIFICATIONS')) {
    const [, event] = e.key.split('.');
    const row = events.get(event) ?? { event, label: e.label.replace(/^[^:]*: /, ''), inApp: '', email: '' };
    if (e.key.endsWith('.inApp')) {
      row.inApp = e.key;
    } else {
      row.email = e.key;
    }
    events.set(event, row);
  }
  return [...events.values()];
}

function inputsOf(effective: EffectiveSettingView[]): Record<string, string> {
  return Object.fromEntries(effective.map((e) => [e.key, toInput(e.value)]));
}

/**
 * My profile & preferences: who the signed-in person is, as the platform
 * records them, and the preferences they keep for themself. Saving applies
 * the display ones at once (theme, date format and time zone, rows per page).
 */
export function MyProfile() {
  const { gateway } = useSession();
  const [me, setMe] = useState<MeView | null>(null);
  const [definitions, setDefinitions] = useState(new Map<string, SettingDefinitionView>());
  const [prefs, setPrefs] = useState<MyPreferencesView | null>(null);
  const [typed, setTyped] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');
  const [busy, setBusy] = useState(false);

  function show(mine: MyPreferencesView) {
    setPrefs(mine);
    setTyped(inputsOf(mine.effective));
  }

  useEffect(() => {
    let ignore = false;
    Promise.all([gateway.me(), gateway.settingDefinitions(), gateway.myPreferences()])
      .then(([m, defs, mine]) => {
        if (!ignore) {
          setMe(m);
          setDefinitions(new Map(defs.map((d) => [d.key, d])));
          show(mine);
        }
      })
      .catch((e: Error) => { if (!ignore) { setError(e.message); } });
    return () => { ignore = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function send(values: Record<string, SettingValue | null>) {
    if (!prefs) {
      return;
    }
    setBusy(true);
    try {
      const mine = await gateway.saveMyPreferences(prefs.version, values);
      applyDisplay(mine.effective);
      show(mine);
      setSaved('Saved.');
    } finally {
      setBusy(false);
    }
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    setError('');
    setSaved('');
    if (!prefs) {
      return;
    }
    const loaded = inputsOf(prefs.effective);
    try {
      const values: Record<string, SettingValue | null> = {};
      for (const [key, text] of Object.entries(typed)) {
        if (text !== loaded[key]) {
          values[key] = fromInput(definitions.get(key)!, text);
        }
      }
      await send(values);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function reset() {
    setError('');
    setSaved('');
    try {
      await send(Object.fromEntries(Object.keys(prefs?.values ?? {}).map((k) => [k, null])));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const display = (prefs?.effective ?? []).filter((e) => e.category === 'UI');
  const set = (key: string, value: string) => setTyped((t) => ({ ...t, [key]: value }));

  return (
    <>
      <h1>My profile &amp; preferences</h1>
      {error && <p className="error" role="alert">{error}</p>}
      {me && (
        <section className="panel" data-testid="me-identity">
          <dl>
            <div><dt>Name</dt><dd data-testid="me-name">{me.fullName}</dd></div>
            <div><dt>Email</dt><dd data-testid="me-email">{me.email}</dd></div>
            <div><dt>Company</dt><dd data-testid="me-tenant">{me.tenantName} ({me.tenantCode})</dd></div>
            <div><dt>Unit</dt><dd data-testid="me-unit">{me.orgUnitName} · {me.orgPath}</dd></div>
            <div><dt>Roles</dt><dd data-testid="me-roles">{me.roles.join(', ') || 'None'}</dd></div>
            <div><dt>Permissions</dt><dd data-testid="me-permissions">{me.permissions.length}</dd></div>
          </dl>
        </section>
      )}

      <h2>Display</h2>
      <form onSubmit={save} data-testid="preferences">
        {display.map((p) => {
          const d = definitions.get(p.key);
          const control = d ? controlOf(d) : 'text';
          return (
            <label key={p.key}>
              {p.label}
              {control === 'select' ? (
                <select data-testid={`pref-${p.key}`} value={typed[p.key] ?? ''}
                  onChange={(e) => set(p.key, e.target.value)}>
                  {(d ? choicesOf(d) : []).map((c) => <option key={c} value={c}>{c}</option>)}
                </select>
              ) : (
                <input data-testid={`pref-${p.key}`} type={control} value={typed[p.key] ?? ''}
                  onChange={(e) => set(p.key, e.target.value)} />
              )}
              <span className="muted" data-testid={`pref-source-${p.key}`}>From: {p.source}</span>
            </label>
          );
        })}

        <h2>Notifications</h2>
        <p className="muted">Recorded for when the platform sends notifications; nothing is sent from these yet.</p>
        <table data-testid="notifications">
          <thead><tr><th>Event</th><th>In the console</th><th>By e-mail</th></tr></thead>
          <tbody>
            {notifyRows(prefs?.effective ?? []).map((n) => (
              <tr key={n.event}>
                <td>{n.label}</td>
                <td><input type="checkbox" data-testid={`pref-${n.inApp}`} checked={typed[n.inApp] === 'true'}
                  onChange={(e) => set(n.inApp, String(e.target.checked))} /></td>
                <td><input type="checkbox" data-testid={`pref-${n.email}`} checked={typed[n.email] === 'true'}
                  onChange={(e) => set(n.email, String(e.target.checked))} /></td>
              </tr>
            ))}
          </tbody>
        </table>
        <p>
          <button type="submit" data-testid="save-preferences" disabled={busy || !prefs}>Save preferences</button>
          <button type="button" className="link" data-testid="reset-preferences" disabled={busy || !prefs}
            onClick={reset}>Use the defaults</button>
        </p>
        {saved && <p className="ok" role="status" data-testid="preferences-saved">{saved}</p>}
      </form>
    </>
  );
}
