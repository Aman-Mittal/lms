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
import {
  CUSTOM_FIELD_KINDS, FIELD_TYPES, type CustomFieldKind, type FieldDefinition, type FieldType,
} from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';

const KIND_LABEL: Record<CustomFieldKind, string> = {
  PARTNER: 'Partners', VEHICLE: 'Vehicles', DRIVER: 'Drivers', TERMINAL: 'Terminals',
  ORDER: 'Orders', LOAD: 'Loads', TRIP: 'Trips',
};

/** Settings: which custom fields exist on each kind of record. */
export function FieldsSettings() {
  const { gateway, can } = useSession();
  const manage = can('CUSTOM_FIELD_MANAGE');
  const [kind, setKind] = useState<CustomFieldKind>('PARTNER');
  const [reload, setReload] = useState(0);
  const [error, setError] = useState('');
  const { data, error: loadError, loading } = useLoad(() => gateway.listFields(kind), `${kind}:${reload}`);

  async function toggle(d: FieldDefinition) {
    setError('');
    try {
      await gateway.reviseField(d.id, {
        label: d.label, required: d.required, options: d.options, active: !d.active,
      });
      setReload((n) => n + 1);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <h1>Custom fields</h1>
      <p className="muted">Fields your company tracks on its own records. Retiring a field keeps what was recorded under it.</p>
      <label>
        Record kind
        <select value={kind} data-testid="kind" onChange={(e) => setKind(e.target.value as CustomFieldKind)}>
          {CUSTOM_FIELD_KINDS.map((k) => <option key={k} value={k}>{KIND_LABEL[k]}</option>)}
        </select>
      </label>
      {(error || loadError) && <p className="error" role="alert">{error || loadError}</p>}
      {loading ? (
        <p>Loading…</p>
      ) : (data ?? []).length === 0 ? (
        <p className="muted">No custom fields yet for {KIND_LABEL[kind].toLowerCase()}.</p>
      ) : (
        <table data-testid="definitions">
          <thead>
            <tr><th>Key</th><th>Label</th><th>Type</th><th>Required</th><th>Options</th><th>Status</th><th /></tr>
          </thead>
          <tbody>
            {(data ?? []).map((d) => (
              <tr key={d.id} data-testid={`def-${d.key}`}>
                <td>{d.key}</td>
                <td>{d.label}</td>
                <td>{d.type}</td>
                <td>{d.required ? 'Yes' : 'No'}</td>
                <td>{d.options.join(', ')}</td>
                <td>{d.active ? 'Active' : 'Retired'}</td>
                <td>
                  {manage && (
                    <button className="link" data-testid={`toggle-${d.key}`} onClick={() => toggle(d)}>
                      {d.active ? 'Retire' : 'Restore'}
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {manage && <AddField kind={kind} onDone={() => setReload((n) => n + 1)} />}
    </>
  );
}

function AddField({ kind, onDone }: { kind: CustomFieldKind; onDone: () => void }) {
  const { gateway } = useSession();
  const [key, setKey] = useState('');
  const [label, setLabel] = useState('');
  const [type, setType] = useState<FieldType>('TEXT');
  const [options, setOptions] = useState('');
  const [required, setRequired] = useState(false);
  const [error, setError] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      await gateway.defineField({
        kind, key, label, type, required,
        options: type === 'SELECT'
          ? options.split(',').map((o) => o.trim()).filter((o) => o !== '')
          : [],
      });
      setKey('');
      setLabel('');
      setOptions('');
      setRequired(false);
      onDone();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <h2>Add a field</h2>
      <form className="inline" onSubmit={submit} data-testid="add-field">
        <input placeholder="Key" aria-label="Key" value={key} onChange={(e) => setKey(e.target.value)} required />
        <input placeholder="Label" aria-label="Label" value={label} onChange={(e) => setLabel(e.target.value)} required />
        <select aria-label="Type" value={type} onChange={(e) => setType(e.target.value as FieldType)}>
          {FIELD_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
        {type === 'SELECT' && (
          <input placeholder="Options, comma separated" aria-label="Options" value={options}
            onChange={(e) => setOptions(e.target.value)} />
        )}
        <label className="check">
          <input type="checkbox" checked={required} onChange={(e) => setRequired(e.target.checked)} />
          Required
        </label>
        <button type="submit">Add field</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
    </>
  );
}
