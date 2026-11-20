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

import { Component, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiProblem, LmsGateway, NUMBER_YEAR_CODES } from '../gateway/lms-gateway';
import { SeriesRevisionDraft } from '../gateway/small-ops-types';
import { ErrorBanner } from './error-banner';

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October',
  'November', 'December'];

const BLANK: SeriesRevisionDraft = {
  prefix: '', yearCode: 'NONE', separator: '-', counterLength: '5', yearStartMonth: '4', timeZone: '',
  reuseReleased: false, description: '',
};

/**
 * Revises an open number series: its format and settings restated whole, sent under the version read when the series
 * was opened, so a series somebody else just changed is refused rather than overwritten. The format can change only
 * until the series issues its first number.
 */
@Component({
  selector: 'app-series-revise',
  imports: [ErrorBanner, FormsModule],
  template: `
    <form ngNativeValidate data-testid="series-revise" (submit)="submit($event)">
      <h3>Revise this series</h3>
      <p class="muted">
        The format can change only until the series has issued a number; the description and whether it reuses
        released numbers can change at any time.
      </p>
      <input placeholder="Prefix" aria-label="Revised prefix" data-testid="revise-prefix" required
             [value]="draft().prefix" (input)="set('prefix', $any($event.target).value)" />
      <select aria-label="Revised year code" data-testid="revise-year-code" (change)="set('yearCode', $any($event.target).value)">
        @for (y of yearCodes; track y) { <option [value]="y" [selected]="y === draft().yearCode">{{ y }}</option> }
      </select>
      <select aria-label="Revised separator" data-testid="revise-separator" (change)="set('separator', $any($event.target).value)">
        @for (s of separators; track s.value) {
          <option [value]="s.value" [selected]="s.value === draft().separator">{{ s.label }}</option>
        }
      </select>
      <input type="number" min="1" max="12" aria-label="Revised counter digits" data-testid="revise-digits"
             [value]="draft().counterLength" (input)="set('counterLength', $any($event.target).value)" />
      <select aria-label="Revised year start" data-testid="revise-month" (change)="set('yearStartMonth', $any($event.target).value)">
        @for (m of months; track m; let i = $index) {
          <option [value]="i + 1" [selected]="'' + (i + 1) === draft().yearStartMonth">{{ m }}</option>
        }
      </select>
      <input placeholder="Time zone (UTC)" aria-label="Revised time zone" data-testid="revise-zone"
             [value]="draft().timeZone" (input)="set('timeZone', $any($event.target).value)" />
      <label>
        <input type="checkbox" data-testid="revise-reuse" [checked]="draft().reuseReleased"
               (change)="set('reuseReleased', $any($event.target).checked)" />
        Reuse released numbers
      </label>
      <input placeholder="Description" aria-label="Revised description" data-testid="revise-description"
             [value]="draft().description" (input)="set('description', $any($event.target).value)" />
      <button type="submit" data-testid="revise-apply">Apply revision</button>
      @if (done()) { <p class="ok" role="status" data-testid="revise-done">Series revised.</p> }
      @if (error().message) { <app-error-banner [message]="error().message" [code]="error().code" testId="revise-error" codeTestId="revise-refusal" /> }
    </form>
  `,
})
export class SeriesRevise {
  private readonly gateway = inject(LmsGateway);

  /** The series being revised; reading it again whenever it changes. */
  readonly seriesId = input.required<string>();
  readonly revised = output<void>();

  readonly yearCodes = NUMBER_YEAR_CODES;
  readonly separators = [{ value: '-', label: '-' }, { value: '/', label: '/' }, { value: '_', label: '_' },
    { value: '.', label: '.' }, { value: '', label: 'None' }];
  readonly months = MONTHS;
  readonly draft = signal<SeriesRevisionDraft>(BLANK);
  readonly done = signal(false);
  readonly error = signal({ message: '', code: '' });
  private version = '';

  constructor() {
    effect(() => { void this.read(this.seriesId()); });
  }

  set<K extends keyof SeriesRevisionDraft>(key: K, value: SeriesRevisionDraft[K]): void {
    this.draft.update((d) => ({ ...d, [key]: value }));
  }

  private async read(id: string): Promise<void> {
    this.done.set(false);
    this.error.set({ message: '', code: '' });
    try {
      const state = await this.gateway.numberSeriesRevisionState(id);
      this.version = state.version;
      this.draft.set(state.draft);
    } catch (e) {
      this.error.set({ message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' });
    }
  }

  async submit(event: Event): Promise<void> {
    event.preventDefault();
    this.done.set(false);
    this.error.set({ message: '', code: '' });
    try {
      await this.gateway.reviseNumberSeries(this.seriesId(), this.version, this.draft());
      await this.read(this.seriesId());
      this.done.set(true);
      this.revised.emit();
    } catch (e) {
      const refused = { message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' };
      if (refused.code === 'concurrent-modification') {
        // Somebody else changed the series: show what it is now, and say why this revision did not go through.
        await this.read(this.seriesId());
      }
      this.error.set(refused);
    }
  }
}
