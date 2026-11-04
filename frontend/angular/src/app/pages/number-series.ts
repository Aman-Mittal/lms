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

import { Component, OnInit, computed, inject, signal } from '@angular/core';
import {
  ApiProblem, LmsGateway, NUMBER_YEAR_CODES, NUMBERED_DOCUMENT_TYPES, NumberedDocumentType, NumberFormatDraft,
  NumberSeriesScope, NumberSeriesView, NumberVerdict, NumberYearCode, Row,
} from '../gateway/lms-gateway';
import { RowColumn, RowTable } from './row-table';

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

/** What an `(input)` or `(change)` event carries, read without a cast in the template. */
function valueOf(event: Event): string {
  return (event.target as HTMLInputElement).value;
}

/**
 * Document number series: how each type of document is numbered, for the whole tenant or
 * one unit's subtree. A format can be previewed before it is saved; a series shows what it
 * would issue next, can issue a number by hand, takes numbers back with a reason, and any
 * number can be checked against the series.
 *
 * Inputs bind through signals rather than `ngModel`, which without zone.js can write its
 * first value late and wipe what was typed.
 */
@Component({
  selector: 'app-number-series',
  imports: [RowTable],
  template: `
    <h1>Number series</h1>
    <p class="muted">
      The most specific active series numbers a document: one for its unit or a unit above it, else the
      tenant-wide one. Without a series, a document keeps its built-in numbering.
    </p>
    @if (refusal(); as r) {
      <p class="error" role="alert" data-testid="series-error">
        @if (r.code) { <code data-testid="series-refusal">{{ r.code }}</code> }
        {{ r.message }}
      </p>
    }
    @if (message()) { <p class="ok" role="status" data-testid="series-message">{{ message() }}</p> }

    <form data-testid="series-create" (submit)="create($event)">
      <h2>New series</h2>
      <input placeholder="Code" aria-label="Series code" data-testid="series-code" required
             [value]="code()" (input)="code.set(valueOf($event))" />
      <select aria-label="Document type" data-testid="series-type" (change)="documentType.set($any(valueOf($event)))">
        @for (t of types; track t) { <option [value]="t" [selected]="t === documentType()">{{ t }}</option> }
      </select>
      <select aria-label="Scope" data-testid="series-scope" (change)="orgUnitId.set(valueOf($event))">
        <option value="" [selected]="orgUnitId() === ''">Whole tenant</option>
        @for (u of scopes(); track u.id) {
          <option [value]="u.id" [selected]="u.id === orgUnitId()">{{ u.name }} ({{ u.unitType }})</option>
        }
      </select>
      <input placeholder="Prefix, such as LR" aria-label="Prefix" data-testid="series-prefix" required
             [value]="prefix()" (input)="prefix.set(valueOf($event))" />
      <select aria-label="Year code" data-testid="series-year-code" (change)="yearCode.set($any(valueOf($event)))">
        @for (y of yearCodes; track y) { <option [value]="y" [selected]="y === yearCode()">{{ y }}</option> }
      </select>
      <select aria-label="Separator" data-testid="series-separator" (change)="separator.set(valueOf($event))">
        @for (s of separators; track s.value) {
          <option [value]="s.value" [selected]="s.value === separator()">{{ s.label }}</option>
        }
      </select>
      <input type="number" min="1" max="12" aria-label="Counter digits" data-testid="series-digits"
             [value]="digits()" (input)="digits.set(valueOf($event))" />
      <select aria-label="Year starts in" data-testid="series-month" (change)="month.set(valueOf($event))">
        @for (m of months; track m; let i = $index) {
          <option [value]="i + 1" [selected]="monthOf(i) === month()">{{ m }}</option>
        }
      </select>
      <input placeholder="Time zone (UTC)" aria-label="Time zone" data-testid="series-zone"
             [value]="zone()" (input)="zone.set(valueOf($event))" />
      <label>
        <input type="checkbox" data-testid="series-reuse" [checked]="reuse()"
               (change)="reuse.set($any($event.target).checked)" />
        Reuse released numbers
      </label>
      <input placeholder="Description" aria-label="Series description" data-testid="series-description"
             [value]="description()" (input)="description.set(valueOf($event))" />
      <button type="button" data-testid="series-preview" (click)="previewFormat()">Preview format</button>
      <button type="submit" data-testid="series-create-submit">Create series</button>
      @if (sample(); as s) {
        <p data-testid="series-sample">First number: <code>{{ s.sample }}</code>; next year: <code>{{ s.nextYearSample }}</code></p>
      }
    </form>

    <h2>Series</h2>
    <label>
      Show
      <select aria-label="Filter by document type" data-testid="series-filter" (change)="filter($any(valueOf($event)))">
        <option value="" [selected]="typeFilter() === ''">Every type</option>
        @for (t of types; track t) { <option [value]="t" [selected]="t === typeFilter()">{{ t }}</option> }
      </select>
    </label>
    @if (rows().length === 0) {
      <p class="muted" data-testid="series-empty">No series yet: every document keeps its built-in numbering.</p>
    } @else {
      <app-row-table testId="series" [rows]="rows()" [columns]="columns" [pickable]="true" [picked]="picked()"
                     (pick)="open($event['id'])" />
    }

    @if (current(); as s) {
      <section data-testid="series-detail">
        <h2>{{ s.code }}</h2>
        <dl class="facts">
          <div><dt>Numbers</dt><dd>{{ s.documentType }} · {{ s.scope }}</dd></div>
          <div><dt>State</dt><dd data-testid="series-state">{{ s.active ? 'Active' : 'Inactive' }}</dd></div>
          <div><dt>Financial year</dt><dd>{{ s.financialYear === '0' ? 'Never restarts' : s.financialYear }}</dd></div>
          <div><dt>Next number</dt><dd data-testid="series-next">{{ next() }}</dd></div>
          <div><dt>Reuses released numbers</dt><dd>{{ s.reuseReleased ? 'Yes' : 'No' }}</dd></div>
        </dl>
        <button type="button" data-testid="series-issue" (click)="issue(s.id)">Issue a number</button>
        @if (issued()) { <p data-testid="series-issued">Issued <code>{{ issued() }}</code></p> }
        @if (s.active) {
          <button type="button" data-testid="series-deactivate" (click)="setActive(s.id, false)">Deactivate</button>
        } @else {
          <button type="button" data-testid="series-activate" (click)="setActive(s.id, true)">Activate</button>
        }
        <button type="button" class="link" data-testid="series-delete" (click)="remove(s.id)">Delete</button>

        <form class="inline" data-testid="series-release" (submit)="release($event, s.id)">
          <h3>Give a number back</h3>
          <input placeholder="Number" aria-label="Number to release" data-testid="release-number" required
                 [value]="releaseNo()" (input)="releaseNo.set(valueOf($event))" />
          <input placeholder="Why" aria-label="Release reason" data-testid="release-reason" required
                 [value]="releaseReason()" (input)="releaseReason.set(valueOf($event))" />
          <button type="submit" data-testid="release-submit">Release</button>
        </form>
        @if (releases().length === 0) {
          <p class="muted" data-testid="releases-empty">No number has been given back.</p>
        } @else {
          <app-row-table testId="releases" [rows]="releases()" [columns]="releaseColumns" />
        }
      </section>
    }

    <form class="inline" data-testid="series-verify" (submit)="verify($event)">
      <h2>Check a number</h2>
      <select aria-label="Document type to check" data-testid="verify-type"
              (change)="verifyType.set($any(valueOf($event)))">
        @for (t of types; track t) { <option [value]="t" [selected]="t === verifyType()">{{ t }}</option> }
      </select>
      <input placeholder="Number" aria-label="Number to check" data-testid="verify-number" required
             [value]="verifyNo()" (input)="verifyNo.set(valueOf($event))" />
      <button type="submit" data-testid="verify-submit">Check</button>
    </form>
    @if (verdict(); as v) {
      <p data-testid="verify-verdict">
        @if (v.belongs) {
          {{ v.status }} in series {{ v.seriesCode }} (counter {{ v.counter }}{{ v.financialYear !== '0' ? ', year ' + v.financialYear : '' }})
        } @else {
          Not a number any series of this type produces
        }
      </p>
    }
  `,
})
export class NumberSeriesPage implements OnInit {
  private readonly gateway = inject(LmsGateway);

  readonly valueOf = valueOf;
  readonly types = NUMBERED_DOCUMENT_TYPES;
  readonly yearCodes = NUMBER_YEAR_CODES;
  readonly separators = SEPARATORS;
  readonly months = MONTHS;
  /** A month's option value, as the month signal holds it. */
  readonly monthOf = (index: number): string => String(index + 1);
  readonly columns = SERIES_COLUMNS;
  readonly releaseColumns = RELEASE_COLUMNS;

  readonly refusal = signal<{ code: string; message: string } | null>(null);
  readonly message = signal('');

  // the new-series form
  readonly code = signal('');
  readonly documentType = signal<NumberedDocumentType>('CONSIGNMENT_NOTE');
  readonly orgUnitId = signal('');
  readonly prefix = signal('');
  readonly yearCode = signal<NumberYearCode>('START_YEAR');
  readonly separator = signal('-');
  readonly digits = signal('5');
  readonly month = signal('4');
  readonly zone = signal('');
  readonly reuse = signal(false);
  readonly description = signal('');
  readonly sample = signal<{ sample: string; nextYearSample: string } | null>(null);

  // the list and the open series
  readonly scopes = signal<NumberSeriesScope[]>([]);
  readonly series = signal<NumberSeriesView[]>([]);
  readonly typeFilter = signal<NumberedDocumentType | ''>('');
  readonly rows = computed<Row[]>(() => this.series().map((s) => ({
    id: s.id, code: s.code, documentType: s.documentType, scope: s.scope, sample: s.sample,
    active: s.active ? 'Yes' : 'No', issuedThisYear: s.issuedThisYear, lastIssued: s.lastIssued || '—',
    releasedAvailable: s.releasedAvailable,
  })));
  readonly picked = signal('');
  readonly current = computed(() => this.series().find((s) => s.id === this.picked()) ?? null);
  readonly next = signal('');
  readonly issued = signal('');
  readonly releases = signal<Row[]>([]);
  readonly releaseNo = signal('');
  readonly releaseReason = signal('');

  // checking a number
  readonly verifyType = signal<NumberedDocumentType>('CONSIGNMENT_NOTE');
  readonly verifyNo = signal('');
  readonly verdict = signal<NumberVerdict | null>(null);

  async ngOnInit(): Promise<void> {
    await this.attempt(async () => {
      const [scopes] = await Promise.all([this.gateway.numberSeriesScopes(), this.reload()]);
      this.scopes.set(scopes);
    });
  }

  async filter(type: NumberedDocumentType | ''): Promise<void> {
    this.typeFilter.set(type);
    await this.attempt(() => this.reload());
  }

  async previewFormat(): Promise<void> {
    await this.attempt(async () => this.sample.set(await this.gateway.previewNumberFormat(this.format())));
  }

  async create(event: Event): Promise<void> {
    event.preventDefault();
    await this.attempt(async () => {
      const id = await this.gateway.createNumberSeries({
        ...this.format(), code: this.code(), documentType: this.documentType(), orgUnitId: this.orgUnitId(),
        reuseReleased: this.reuse(), active: true, description: this.description(),
      });
      // Clear first: a re-read below must not race what is typed next.
      this.code.set('');
      this.prefix.set('');
      this.description.set('');
      this.sample.set(null);
      await this.reload();
      await this.open(id);
      return 'Series created.';
    });
  }

  async open(id: string): Promise<void> {
    this.picked.set(id);
    this.issued.set('');
    await this.attempt(async () => {
      const [next, releases] = await Promise.all([this.gateway.nextNumber(id), this.gateway.numberReleases(id)]);
      this.next.set(next);
      this.releases.set(releases);
    });
  }

  async issue(id: string): Promise<void> {
    await this.attempt(async () => {
      const number = await this.gateway.issueNumber(id);
      this.issued.set(number);
      await this.refresh(id);
      return `Issued ${number}.`;
    });
  }

  async setActive(id: string, active: boolean): Promise<void> {
    await this.attempt(async () => {
      await this.gateway.setNumberSeriesActive(id, active);
      await this.refresh(id);
      return active ? 'Series activated.' : 'Series deactivated.';
    });
  }

  async remove(id: string): Promise<void> {
    await this.attempt(async () => {
      await this.gateway.deleteNumberSeries(id);
      this.picked.set('');
      await this.reload();
      return 'Series deleted.';
    });
  }

  async release(event: Event, id: string): Promise<void> {
    event.preventDefault();
    await this.attempt(async () => {
      const number = this.releaseNo().trim();
      await this.gateway.releaseNumber(id, number, this.releaseReason());
      this.releaseNo.set('');
      this.releaseReason.set('');
      await this.refresh(id);
      return `${number} given back.`;
    });
  }

  async verify(event: Event): Promise<void> {
    event.preventDefault();
    this.verdict.set(null);
    await this.attempt(async () => this.verdict.set(await this.gateway.verifyNumber(this.verifyType(), this.verifyNo())));
  }

  // ---------------------------------------------------------------- helpers

  private format(): NumberFormatDraft {
    return {
      prefix: this.prefix(), yearCode: this.yearCode(), separator: this.separator(),
      counterLength: Number(this.digits()) || 5, yearStartMonth: Number(this.month()) || 4, timeZone: this.zone(),
    };
  }

  private async reload(): Promise<void> {
    this.series.set(await this.gateway.numberSeries(this.typeFilter() || undefined));
  }

  /** The list, then the open series' next number and releases. */
  private async refresh(id: string): Promise<void> {
    await this.reload();
    const [next, releases] = await Promise.all([this.gateway.nextNumber(id), this.gateway.numberReleases(id)]);
    this.next.set(next);
    this.releases.set(releases);
  }

  private async attempt(action: () => Promise<string | void>): Promise<void> {
    this.refusal.set(null);
    this.message.set('');
    try {
      const said = await action();
      if (said) {
        this.message.set(said);
      }
    } catch (e) {
      this.refusal.set({ code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message });
    }
  }
}
