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

import { useMemo, useState, type FormEvent } from 'react';
import {
  ApiProblem, NUMBER_YEAR_CODES, NUMBERED_DOCUMENT_TYPES, type NumberedDocumentType, type NumberFormatDraft,
  type NumberFormatSample, type NumberSeriesScope, type NumberSeriesView, type NumberVerdict, type NumberYearCode,
  type Row,
} from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { RowTable, type RowColumn } from './RowTable';

const SERIES_COLUMNS: RowColumn[] = [
  { header: 'Code', field: 'code' }, { header: 'Document', field: 'documentType' }, { header: 'Scope', field: 'scope' },
  { header: 'Format', field: 'sample' }, { header: 'Active', field: 'active' },
  { header: 'Issued this year', field: 'issuedThisYear' }, { header: 'Last issued', field: 'lastIssued' },
  { header: 'Released', field: 'releasedAvailable' },
];

const RELEASE_COLUMNS: RowColumn[] = [
  { header: 'Number', field: 'number' }, { header: 'Reason', field: 'reason' },
  { header: 'Released at', field: 'releasedAt' }, { header: 'Issued again', field: 'reusedAt' },
];

const SEPARATORS = [
  { value: '-', label: '-' }, { value: '/', label: '/' }, { value: '_', label: '_' }, { value: '.', label: '.' },
  { value: '', label: 'None' },
];

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October',
  'November', 'December'];

/**
 * Document number series: how each type of document is numbered, for the whole tenant or
 * one unit's subtree. A format can be previewed before it is saved; a series shows what it
 * would issue next, can issue a number by hand, takes numbers back with a reason, and any
 * number can be checked against the series.
 */
export function NumberSeries() {
  const { gateway } = useSession();
  const [refusal, setRefusal] = useState<{ code: string; message: string } | null>(null);
  const [message, setMessage] = useState('');
  const [reload, setReload] = useState(0);

  // the new-series form
  const [code, setCode] = useState('');
  const [documentType, setDocumentType] = useState<NumberedDocumentType>('CONSIGNMENT_NOTE');
  const [orgUnitId, setOrgUnitId] = useState('');
  const [prefix, setPrefix] = useState('');
  const [yearCode, setYearCode] = useState<NumberYearCode>('START_YEAR');
  const [separator, setSeparator] = useState('-');
  const [digits, setDigits] = useState('5');
  const [month, setMonth] = useState('4');
  const [zone, setZone] = useState('');
  const [reuse, setReuse] = useState(false);
  const [description, setDescription] = useState('');
  const [sample, setSample] = useState<NumberFormatSample | null>(null);

  // the list and the open series
  const [typeFilter, setTypeFilter] = useState<NumberedDocumentType | ''>('');
  const [picked, setPicked] = useState('');
  const [issued, setIssued] = useState('');
  const [releaseNo, setReleaseNo] = useState('');
  const [releaseReason, setReleaseReason] = useState('');

  // checking a number
  const [verifyType, setVerifyType] = useState<NumberedDocumentType>('CONSIGNMENT_NOTE');
  const [verifyNo, setVerifyNo] = useState('');
  const [verdict, setVerdict] = useState<NumberVerdict | null>(null);

  const scopes = useLoad(() => gateway.numberSeriesScopes(), 0);
  const listed = useLoad(() => gateway.numberSeries(typeFilter || undefined), `${typeFilter}:${reload}`);
  const detail = useLoad(
    () => (picked
      ? Promise.all([gateway.nextNumber(picked), gateway.numberReleases(picked)])
      : Promise.resolve(['', [] as Row[]] as [string, Row[]])),
    `${picked}:${reload}`,
  );

  const series: NumberSeriesView[] = listed.data ?? [];
  const units: NumberSeriesScope[] = scopes.data ?? [];
  const rows = useMemo<Row[]>(() => series.map((s) => ({
    id: s.id, code: s.code, documentType: s.documentType, scope: s.scope, sample: s.sample,
    active: s.active ? 'Yes' : 'No', issuedThisYear: s.issuedThisYear, lastIssued: s.lastIssued || '—',
    releasedAvailable: s.releasedAvailable,
  })), [series]);
  const current = series.find((s) => s.id === picked) ?? null;
  const [next, releases] = detail.data ?? ['', [] as Row[]];
  const loadError = listed.error || scopes.error || detail.error;

  function format(): NumberFormatDraft {
    return {
      prefix, yearCode, separator, counterLength: Number(digits) || 5, yearStartMonth: Number(month) || 4,
      timeZone: zone,
    };
  }

  async function attempt(action: () => Promise<string | void>) {
    setRefusal(null);
    setMessage('');
    try {
      const said = await action();
      if (said) {
        setMessage(said);
      }
    } catch (e) {
      setRefusal({ code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message });
    }
    setReload((n) => n + 1);
  }

  function create(e: FormEvent) {
    e.preventDefault();
    void attempt(async () => {
      const id = await gateway.createNumberSeries({
        ...format(), code, documentType, orgUnitId, reuseReleased: reuse, active: true, description,
      });
      setCode('');
      setPrefix('');
      setDescription('');
      setSample(null);
      setPicked(id);
      setIssued('');
      return 'Series created.';
    });
  }

  function release(e: FormEvent, id: string) {
    e.preventDefault();
    void attempt(async () => {
      const number = releaseNo.trim();
      await gateway.releaseNumber(id, number, releaseReason);
      setReleaseNo('');
      setReleaseReason('');
      return `${number} given back.`;
    });
  }

  function verify(e: FormEvent) {
    e.preventDefault();
    setVerdict(null);
    void attempt(async () => setVerdict(await gateway.verifyNumber(verifyType, verifyNo)));
  }

  return (
    <>
      <h1>Number series</h1>
      <p className="muted">
        The most specific active series numbers a document: one for its unit or a unit above it, else the
        tenant-wide one. Without a series, a document keeps its built-in numbering.
      </p>
      {refusal && (
        <p className="error" role="alert" data-testid="series-error">
          {refusal.code && <><code data-testid="series-refusal">{refusal.code}</code>{' '}</>}
          {refusal.message}
        </p>
      )}
      {!refusal && loadError && <p className="error" role="alert" data-testid="series-error">{loadError}</p>}
      {message && <p className="ok" role="status" data-testid="series-message">{message}</p>}

      <form data-testid="series-create" onSubmit={create}>
        <h2>New series</h2>
        <input placeholder="Code" aria-label="Series code" data-testid="series-code" required
          value={code} onChange={(e) => setCode(e.target.value)} />
        <select aria-label="Document type" data-testid="series-type" value={documentType}
          onChange={(e) => setDocumentType(e.target.value as NumberedDocumentType)}>
          {NUMBERED_DOCUMENT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
        <select aria-label="Scope" data-testid="series-scope" value={orgUnitId}
          onChange={(e) => setOrgUnitId(e.target.value)}>
          <option value="">Whole tenant</option>
          {units.map((u) => <option key={u.id} value={u.id}>{u.name} ({u.unitType})</option>)}
        </select>
        <input placeholder="Prefix, such as LR" aria-label="Prefix" data-testid="series-prefix" required
          value={prefix} onChange={(e) => setPrefix(e.target.value)} />
        <select aria-label="Year code" data-testid="series-year-code" value={yearCode}
          onChange={(e) => setYearCode(e.target.value as NumberYearCode)}>
          {NUMBER_YEAR_CODES.map((y) => <option key={y} value={y}>{y}</option>)}
        </select>
        <select aria-label="Separator" data-testid="series-separator" value={separator}
          onChange={(e) => setSeparator(e.target.value)}>
          {SEPARATORS.map((s) => <option key={s.label} value={s.value}>{s.label}</option>)}
        </select>
        <input type="number" min="1" max="12" aria-label="Counter digits" data-testid="series-digits"
          value={digits} onChange={(e) => setDigits(e.target.value)} />
        <select aria-label="Year starts in" data-testid="series-month" value={month}
          onChange={(e) => setMonth(e.target.value)}>
          {MONTHS.map((m, i) => <option key={m} value={String(i + 1)}>{m}</option>)}
        </select>
        <input placeholder="Time zone (UTC)" aria-label="Time zone" data-testid="series-zone"
          value={zone} onChange={(e) => setZone(e.target.value)} />
        <label>
          <input type="checkbox" data-testid="series-reuse" checked={reuse} onChange={(e) => setReuse(e.target.checked)} />
          {' '}Reuse released numbers
        </label>
        <input placeholder="Description" aria-label="Series description" data-testid="series-description"
          value={description} onChange={(e) => setDescription(e.target.value)} />
        <button type="button" data-testid="series-preview"
          onClick={() => void attempt(async () => setSample(await gateway.previewNumberFormat(format())))}>
          Preview format
        </button>
        <button type="submit" data-testid="series-create-submit">Create series</button>
        {sample && (
          <p data-testid="series-sample">
            First number: <code>{sample.sample}</code>; next year: <code>{sample.nextYearSample}</code>
          </p>
        )}
      </form>

      <h2>Series</h2>
      <label>
        Show{' '}
        <select aria-label="Filter by document type" data-testid="series-filter" value={typeFilter}
          onChange={(e) => setTypeFilter(e.target.value as NumberedDocumentType | '')}>
          <option value="">Every type</option>
          {NUMBERED_DOCUMENT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
      </label>
      {rows.length === 0
        ? <p className="muted" data-testid="series-empty">No series yet: every document keeps its built-in numbering.</p>
        : <RowTable testId="series" rows={rows} columns={SERIES_COLUMNS} picked={picked}
            onPick={(row) => { setPicked(row.id); setIssued(''); }} />}

      {current && (
        <section data-testid="series-detail">
          <h2>{current.code}</h2>
          <dl className="facts">
            <div><dt>Numbers</dt><dd>{current.documentType} · {current.scope}</dd></div>
            <div><dt>State</dt><dd data-testid="series-state">{current.active ? 'Active' : 'Inactive'}</dd></div>
            <div><dt>Financial year</dt><dd>{current.financialYear === '0' ? 'Never restarts' : current.financialYear}</dd></div>
            <div><dt>Next number</dt><dd data-testid="series-next">{next}</dd></div>
            <div><dt>Reuses released numbers</dt><dd>{current.reuseReleased ? 'Yes' : 'No'}</dd></div>
          </dl>
          <button type="button" data-testid="series-issue" onClick={() => void attempt(async () => {
            const number = await gateway.issueNumber(current.id);
            setIssued(number);
            return `Issued ${number}.`;
          })}>Issue a number</button>
          {issued && <p data-testid="series-issued">Issued <code>{issued}</code></p>}
          {current.active
            ? <button type="button" data-testid="series-deactivate" onClick={() => void attempt(async () => {
              await gateway.setNumberSeriesActive(current.id, false);
              return 'Series deactivated.';
            })}>Deactivate</button>
            : <button type="button" data-testid="series-activate" onClick={() => void attempt(async () => {
              await gateway.setNumberSeriesActive(current.id, true);
              return 'Series activated.';
            })}>Activate</button>}
          <button type="button" className="link" data-testid="series-delete" onClick={() => void attempt(async () => {
            await gateway.deleteNumberSeries(current.id);
            setPicked('');
            return 'Series deleted.';
          })}>Delete</button>

          <form className="inline" data-testid="series-release" onSubmit={(e) => release(e, current.id)}>
            <h3>Give a number back</h3>
            <input placeholder="Number" aria-label="Number to release" data-testid="release-number" required
              value={releaseNo} onChange={(e) => setReleaseNo(e.target.value)} />
            <input placeholder="Why" aria-label="Release reason" data-testid="release-reason" required
              value={releaseReason} onChange={(e) => setReleaseReason(e.target.value)} />
            <button type="submit" data-testid="release-submit">Release</button>
          </form>
          {releases.length === 0
            ? <p className="muted" data-testid="releases-empty">No number has been given back.</p>
            : <RowTable testId="releases" rows={releases} columns={RELEASE_COLUMNS} />}
        </section>
      )}

      <form className="inline" data-testid="series-verify" onSubmit={verify}>
        <h2>Check a number</h2>
        <select aria-label="Document type to check" data-testid="verify-type" value={verifyType}
          onChange={(e) => setVerifyType(e.target.value as NumberedDocumentType)}>
          {NUMBERED_DOCUMENT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
        <input placeholder="Number" aria-label="Number to check" data-testid="verify-number" required
          value={verifyNo} onChange={(e) => setVerifyNo(e.target.value)} />
        <button type="submit" data-testid="verify-submit">Check</button>
      </form>
      {verdict && (
        <p data-testid="verify-verdict">
          {verdict.belongs
            ? `${verdict.status} in series ${verdict.seriesCode} (counter ${verdict.counter}`
              + `${verdict.financialYear !== '0' ? `, year ${verdict.financialYear}` : ''})`
            : 'Not a number any series of this type produces'}
        </p>
      )}
    </>
  );
}
