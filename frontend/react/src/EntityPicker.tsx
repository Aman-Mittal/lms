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

import { useEffect, useRef, useState } from 'react';
import type { Choice, PickKind } from './gateway/lmsGateway';
import { useSession } from './session';

/** A pasted id is taken as it is, without a search. */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DEBOUNCE_MS = 250;
const UNMATCHED = 'Choose one of the suggestions, or paste an id.';

export interface EntityPickerProps {
  kind: PickKind;
  /** The chosen id, or '' when nothing is chosen. */
  value: string;
  onChange: (id: string) => void;
  ariaLabel: string;
  placeholder?: string;
  required?: boolean;
  /** For `bin`: the warehouse whose bins are offered. */
  warehouseId?: string;
  testId?: string;
}

/*
 * REACT CONCEPT: a controlled component that keeps two values.
 * The parent owns the id (`value`); the picker owns what is typed (`text`).
 * They differ while a person is searching -- "steel" is typed, nothing is
 * chosen -- and meet again when a suggestion is picked or an id is pasted.
 * The debounce is a timer kept in a ref: a ref survives re-renders without
 * causing one, which is what a pending timer needs.
 */
export function EntityPicker({
  kind, value, onChange, ariaLabel, placeholder, required, warehouseId, testId,
}: EntityPickerProps) {
  const { gateway } = useSession();
  const [text, setText] = useState(value);
  const [options, setOptions] = useState<Choice[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState('');
  // Why a submit was refused; kept apart from search errors, which a later search clears.
  const [refusal, setRefusal] = useState('');
  const chosen = useRef(value);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Numbers each search, so an answer that arrives after a newer one -- or after
  // an id was pasted -- is dropped rather than reopening a stale list.
  const asked = useRef(0);
  const input = useRef<HTMLInputElement>(null);

  // A value set from outside (an order read back, a form reset) is shown as it is.
  useEffect(() => {
    if (value !== chosen.current) {
      chosen.current = value;
      setText(value);
    }
  }, [value]);

  // Text that names nothing is not an answer: the form will not send it.
  useEffect(() => {
    input.current?.setCustomValidity(
      text.trim() && !chosen.current ? UNMATCHED : '');
  }, [text, value]);

  useEffect(() => () => { if (timer.current) { clearTimeout(timer.current); } }, []);

  async function search(q: string, cursor: string | null) {
    const ticket = ++asked.current;
    try {
      const page = await gateway.pick({ kind, q, cursor, warehouseId });
      if (ticket !== asked.current || (chosen.current && !cursor)) {
        return;
      }
      setOptions((before) => (cursor ? [...before, ...page.items] : page.items));
      setNext(page.nextCursor);
      setError('');
      setOpen(true);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function typed(t: string) {
    setText(t);
    setRefusal('');
    if (timer.current) { clearTimeout(timer.current); }
    if (UUID.test(t.trim())) {
      asked.current++;
      chosen.current = t.trim();
      onChange(t.trim());
      setOpen(false);
      return;
    }
    if (chosen.current) {
      chosen.current = '';
      onChange('');
    }
    timer.current = setTimeout(() => { void search(t, null); }, DEBOUNCE_MS);
  }

  function pick(choice: Choice) {
    setRefusal('');
    chosen.current = choice.id;
    setText(choice.label);
    onChange(choice.id);
    setOpen(false);
  }

  return (
    <span className="picker" data-testid={testId ? `${testId}-picker` : undefined}>
      <input ref={input} aria-label={ariaLabel} placeholder={placeholder} required={required}
        data-testid={testId} value={text} autoComplete="off" role="combobox" aria-expanded={open}
        onChange={(e) => typed(e.target.value)}
        // The browser refuses the submit; say why where it can be read out.
        onInvalid={(e) => {
          if (text.trim() && !chosen.current) { e.preventDefault(); setOpen(false); setRefusal(UNMATCHED); }
        }}
        onFocus={() => { if (!text.trim() && !open) { void search('', null); } }}
        onKeyDown={(e) => { if (e.key === 'Escape') { setOpen(false); } }}
        onBlur={() => { setTimeout(() => setOpen(false), 150); }} />
      {open && (
        <ul className="picker-options" role="listbox" aria-label="Suggestions">
          {options.length === 0 && <li className="muted">Nothing matches.</li>}
          {options.map((o) => (
            <li key={o.id}>
              <button type="button" role="option" aria-selected={o.id === chosen.current}
                onMouseDown={(e) => e.preventDefault()} onClick={() => pick(o)}>{o.label}</button>
            </li>
          ))}
          {next && (
            <li>
              <button type="button" className="more" onMouseDown={(e) => e.preventDefault()}
                onClick={() => { void search(text, next); }}>More…</button>
            </li>
          )}
        </ul>
      )}
      {error && <span className="error" role="alert">{error}</span>}
      {refusal && <span className="error" role="alert">{refusal}</span>}
    </span>
  );
}
