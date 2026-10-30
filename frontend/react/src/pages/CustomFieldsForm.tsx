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
import type {
  CustomFieldKind, CustomValues, FieldDefinition,
} from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';

/** Everything a form field holds is text until it is saved. */
type Draft = Record<string, string>;

function draftOf(definitions: FieldDefinition[], values: CustomValues): Draft {
  const draft: Draft = {};
  for (const d of definitions) {
    const v = values[d.key];
    draft[d.key] = v === undefined ? '' : String(v);
  }
  return draft;
}

/** The typed value to store for a field's text, or undefined when it is empty. */
function valueOf(d: FieldDefinition, text: string): string | number | boolean | undefined {
  if (text === '') {
    return undefined;
  }
  switch (d.type) {
    case 'NUMBER': return Number(text);
    case 'BOOLEAN': return text === 'true';
    default: return text;
  }
}

export function CustomFieldsForm({ kind, recordId }: { kind: CustomFieldKind; recordId: string }) {
  const { gateway, can } = useSession();
  const definitions = useLoad(() => gateway.listFields(kind), kind);
  const stored = useLoad(() => gateway.customValues(kind, recordId), recordId);

  const failed = definitions.error || stored.error;
  if (failed) {
    return <p className="error" role="alert">{failed}</p>;
  }
  if (!definitions.data || !stored.data) {
    return <p>Loading custom fields…</p>;
  }
  const active = definitions.data.filter((d) => d.active);
  if (active.length === 0) {
    return <p className="muted" data-testid="no-custom-fields">No custom fields are defined for this kind of record.</p>;
  }
  return (
    <Editor kind={kind} recordId={recordId} definitions={active} initial={stored.data}
      canWrite={can('CUSTOM_FIELD_WRITE')} />
  );
}

interface EditorProps {
  kind: CustomFieldKind;
  recordId: string;
  definitions: FieldDefinition[];
  initial: CustomValues;
  canWrite: boolean;
}

function Editor({ kind, recordId, definitions, initial, canWrite }: EditorProps) {
  const { gateway } = useSession();

  /*
   * REACT CONCEPT: initial state from props.
   * useState uses its argument only on the first render. That is what we want
   * here: the form starts from what is stored, and from then on the user's
   * typing is the truth. The parent re-creates this component (a new key) when
   * the record changes.
   */
  const [draft, setDraft] = useState<Draft>(() => draftOf(definitions, initial));
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function save(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError('');
    setMessage('');
    const values: CustomValues = {};
    for (const d of definitions) {
      const v = valueOf(d, draft[d.key] ?? '');
      if (v !== undefined) {
        values[d.key] = v;
      }
    }
    try {
      const stored = await gateway.saveCustomValues(kind, recordId, values);
      setDraft(draftOf(definitions, stored));
      setMessage('Saved');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  function set(key: string, value: string) {
    setDraft((d) => ({ ...d, [key]: value }));
    setMessage('');
  }

  return (
    <form onSubmit={save} data-testid="custom-fields">
      <h3>Custom fields</h3>
      {definitions.map((d) => {
        const label = d.required ? `${d.label} *` : d.label;
        const value = draft[d.key] ?? '';
        return (
          <label key={d.key}>
            {label}
            {d.type === 'SELECT' ? (
              <select value={value} disabled={!canWrite} data-testid={`cf-${d.key}`}
                onChange={(e) => set(d.key, e.target.value)}>
                <option value="" />
                {d.options.map((o) => <option key={o} value={o}>{o}</option>)}
              </select>
            ) : d.type === 'BOOLEAN' ? (
              <select value={value} disabled={!canWrite} data-testid={`cf-${d.key}`}
                onChange={(e) => set(d.key, e.target.value)}>
                <option value="" />
                <option value="true">Yes</option>
                <option value="false">No</option>
              </select>
            ) : (
              <input
                type={d.type === 'NUMBER' ? 'number' : d.type === 'DATE' ? 'date' : 'text'}
                step={d.type === 'NUMBER' ? 'any' : undefined}
                value={value} disabled={!canWrite} data-testid={`cf-${d.key}`}
                onChange={(e) => set(d.key, e.target.value)}
              />
            )}
          </label>
        );
      })}
      {canWrite && <button type="submit" disabled={busy} data-testid="save-custom-fields">Save</button>}
      {message && <p className="ok" role="status" data-testid="custom-fields-saved">{message}</p>}
      {error && <p className="error" role="alert">{error}</p>}
    </form>
  );
}
