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
import { ApiProblem, NUMBER_YEAR_CODES } from '../gateway/lmsGateway';
import type { SeriesRevisionDraft } from '../gateway/smallOpsTypes';
import { useSession } from '../session';
import { ErrorBanner } from './ErrorBanner';

interface Refused {
  message: string;
  code: string;
}

const NONE: Refused = { message: '', code: '' };

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October',
  'November', 'December'];

const SEPARATORS = [
  { value: '-', label: '-' }, { value: '/', label: '/' }, { value: '_', label: '_' }, { value: '.', label: '.' },
  { value: '', label: 'None' },
];

const BLANK: SeriesRevisionDraft = {
  prefix: '', yearCode: 'NONE', separator: '-', counterLength: '5', yearStartMonth: '4', timeZone: '',
  reuseReleased: false, description: '',
};

/**
 * Revises an open number series: its format and settings restated whole, sent under the version read when the series
 * was opened, so a series somebody else just changed is refused rather than overwritten. The format can change only
 * until the series issues its first number.
 */
export function SeriesRevise({ seriesId, onRevised }: { seriesId: string; onRevised: () => void }) {
  const { gateway } = useSession();
  const [draft, setDraft] = useState<SeriesRevisionDraft>(BLANK);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<Refused>(NONE);
  const version = useRef('');
  const set = <K extends keyof SeriesRevisionDraft>(key: K, value: SeriesRevisionDraft[K]) =>
    setDraft((d) => ({ ...d, [key]: value }));

  async function read() {
    const state = await gateway.numberSeriesRevisionState(seriesId);
    version.current = state.version;
    setDraft(state.draft);
  }

  useEffect(() => {
    setDone(false);
    setError(NONE);
    read().catch((e: Error) => setError({ message: e.message, code: e instanceof ApiProblem ? e.code : '' }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [seriesId]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setDone(false);
    setError(NONE);
    try {
      await gateway.reviseNumberSeries(seriesId, version.current, draft);
      await read();
      setDone(true);
      onRevised();
    } catch (e) {
      const refused = { message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' };
      if (refused.code === 'concurrent-modification') {
        // Somebody else changed the series: show what it is now, and say why this revision did not go through.
        await read().catch(() => undefined);
      }
      setError(refused);
    }
  }

  return (
    <form data-testid="series-revise" onSubmit={(ev) => void submit(ev)}>
      <h3>Revise this series</h3>
      <p className="muted">
        The format can change only until the series has issued a number; the description and whether it reuses
        released numbers can change at any time.
      </p>
      <input placeholder="Prefix" aria-label="Revised prefix" data-testid="revise-prefix" required value={draft.prefix}
        onChange={(e) => set('prefix', e.target.value)} />
      <select aria-label="Revised year code" data-testid="revise-year-code" value={draft.yearCode}
        onChange={(e) => set('yearCode', e.target.value)}>
        {NUMBER_YEAR_CODES.map((y) => <option key={y} value={y}>{y}</option>)}
      </select>
      <select aria-label="Revised separator" data-testid="revise-separator" value={draft.separator}
        onChange={(e) => set('separator', e.target.value)}>
        {SEPARATORS.map((s) => <option key={s.label} value={s.value}>{s.label}</option>)}
      </select>
      <input type="number" min="1" max="12" aria-label="Revised counter digits" data-testid="revise-digits"
        value={draft.counterLength} onChange={(e) => set('counterLength', e.target.value)} />
      <select aria-label="Revised year start" data-testid="revise-month" value={draft.yearStartMonth}
        onChange={(e) => set('yearStartMonth', e.target.value)}>
        {MONTHS.map((m, i) => <option key={m} value={String(i + 1)}>{m}</option>)}
      </select>
      <input placeholder="Time zone (UTC)" aria-label="Revised time zone" data-testid="revise-zone" value={draft.timeZone}
        onChange={(e) => set('timeZone', e.target.value)} />
      <label>
        <input type="checkbox" data-testid="revise-reuse" checked={draft.reuseReleased}
          onChange={(e) => set('reuseReleased', e.target.checked)} />
        Reuse released numbers
      </label>
      <input placeholder="Description" aria-label="Revised description" data-testid="revise-description"
        value={draft.description} onChange={(e) => set('description', e.target.value)} />
      <button type="submit" data-testid="revise-apply">Apply revision</button>
      {done && <p className="ok" role="status" data-testid="revise-done">Series revised.</p>}
      {error.message && <ErrorBanner message={error.message} code={error.code} testId="revise-error" codeTestId="revise-refusal" />}
    </form>
  );
}
