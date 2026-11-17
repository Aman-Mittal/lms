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

import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiProblem, type MasterKind, type MasterValues } from '../gateway/lmsGateway';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { FIELDS, KIND_LABEL, blankValues, validate, type FieldDef } from './masterDataFields';
import { ErrorBanner } from './ErrorBanner';
import { showToast } from '../../../shared/feedback';

export const CONFLICT_TEXT = 'Someone else changed this record while you were editing it. '
  + 'It has been reloaded with their changes; check the values and save again.';

interface Props {
  kind: MasterKind;
  /** The record being edited; absent for a new one. */
  recordId?: string;
  orgUnitId: string;
  onSaved: (id: string) => void;
  onCancel: () => void;
}

/** The create and edit form for a partner, vehicle, driver or terminal. An edit reads the record fresh and sends its version as If-Match. */
export function MasterDataForm({ kind, recordId, orgUnitId, onSaved, onCancel }: Props) {
  const { gateway } = useSession();
  const isNew = !recordId;
  const [values, setValues] = useState<MasterValues>(() => blankValues(kind));
  const [version, setVersion] = useState(0);
  const [loading, setLoading] = useState(!isNew);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitted, setSubmitted] = useState(false);
  const [problem, setProblem] = useState('');
  const [conflict, setConflict] = useState(false);
  const [saving, setSaving] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);

  /** Reads the record into the form, keeping the version it was read at. */
  async function load(): Promise<void> {
    if (!recordId) { return; }
    const record = await gateway.masterRecord(kind, recordId);
    setValues({ ...blankValues(kind), ...record.values });
    setVersion(record.version);
    setErrors({});
    setSubmitted(false);
  }

  useEffect(() => {
    load().catch((e: Error) => setProblem(e.message)).finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kind, recordId]);

  useEffect(() => { heading.current?.focus(); }, []);

  function change(name: string, value: string | boolean) {
    const next = { ...values, [name]: value };
    setValues(next);
    if (submitted) { setErrors(validate(kind, next, isNew)); }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    setProblem('');
    setConflict(false);
    const found = validate(kind, values, isNew);
    setErrors(found);
    if (Object.keys(found).length > 0) { return; }
    setSaving(true);
    try {
      const savedId = await gateway.saveMasterRecord(kind, orgUnitId, recordId ?? null, values, version);
      showToast('Saved.');
      onSaved(savedId);
    } catch (e) {
      if (e instanceof ApiProblem && e.code === 'concurrent-modification') {
        setConflict(true);
        await load().catch((inner: Error) => setProblem(inner.message));
      } else {
        setProblem((e as Error).message);
      }
    } finally {
      setSaving(false);
    }
  }

  const label = KIND_LABEL[kind];
  const shown = FIELDS[kind].filter((f) => !f.when || f.when(values, isNew));

  function control(f: FieldDef) {
    const id = `md-${f.name}`;
    const value = values[f.name];
    const locked = !isNew && f.createOnly === true;
    const described = errors[f.name] ? `${id}-error` : f.hint ? `${id}-hint` : undefined;
    const common = { id, 'data-testid': f.testId, 'aria-invalid': errors[f.name] ? true : undefined, 'aria-describedby': described };
    switch (f.type) {
      case 'select':
        return (
          <select {...common} value={String(value ?? '')} disabled={locked} onChange={(e) => change(f.name, e.target.value)}>
            {f.options?.map((o) => <option key={o} value={o}>{o}</option>)}
          </select>
        );
      case 'textarea':
        return <textarea {...common} rows={3} value={String(value ?? '')} onChange={(e) => change(f.name, e.target.value)} />;
      case 'checkbox':
        return <input {...common} type="checkbox" checked={value === true} onChange={(e) => change(f.name, e.target.checked)} />;
      case 'partner':
        return (
          <EntityPicker kind="partner" value={String(value ?? '')} onChange={(v) => change(f.name, v)}
            ariaLabel={f.label} testId={f.testId} />
        );
      default:
        return (
          <input {...common} type={f.type === 'number' ? 'text' : f.type} inputMode={f.type === 'number' ? 'decimal' : undefined}
            value={String(value ?? '')} disabled={locked} onChange={(e) => change(f.name, e.target.value)} />
        );
    }
  }

  return (
    <aside className="panel master-form" aria-label={`${isNew ? 'New' : 'Edit'} ${label}`}>
      <header>
        <h2 ref={heading} tabIndex={-1}>{isNew ? 'New' : 'Edit'} {label}</h2>
      </header>
      {conflict && <p className="notice" role="alert" data-testid="conflict-notice">{CONFLICT_TEXT}</p>}
      {loading ? <p className="muted">Loading…</p> : (
        <form onSubmit={submit} noValidate data-testid="record-form" className="stack">
          {shown.map((f) => (
            <div key={f.name} className={f.type === 'checkbox' ? 'field field--check' : 'field'}>
              <label htmlFor={`md-${f.name}`}>{f.label}{f.required && !(f.createOnly && !isNew) ? ' *' : ''}</label>
              {control(f)}
              {f.hint && !errors[f.name] && <small id={`md-${f.name}-hint`} className="muted">{f.hint}</small>}
              {errors[f.name] && (
                <small id={`md-${f.name}-error`} className="error" role="alert" data-testid={`error-${f.name}`}>
                  {errors[f.name]}
                </small>
              )}
            </div>
          ))}
          {problem && <ErrorBanner message={problem} testId="form-error" />}
          <div className="actions">
            <button type="submit" data-testid="save-record" disabled={saving}>Save</button>
            <button type="button" className="link" data-testid="cancel-form" onClick={onCancel}>Cancel</button>
          </div>
        </form>
      )}
    </aside>
  );
}
