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

/*
 * The signed-in person's display preferences, applied where the console has
 * one place to apply them: the theme on the document, the shared date and
 * instant formatting in the gateway, the page size of the record listings and
 * the screen opened after sign-in. Read from `GET /settings/me` at sign-in and
 * after a save; the defaults are what the console did before preferences
 * existed, so a person who sets nothing sees no change.
 */

export interface DisplayPreferences {
  theme: string;
  dateFormat: string;
  timeZone: string;
  locale: string;
  pageSize: number;
  landingPage: string;
}

export const DEFAULT_DISPLAY: DisplayPreferences = {
  theme: 'system', dateFormat: 'YYYY-MM-DD', timeZone: 'UTC', locale: 'en-GB', pageSize: 50,
  landingPage: 'dashboard',
};

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

let current: DisplayPreferences = { ...DEFAULT_DISPLAY };

export function displayPreferences(): DisplayPreferences {
  return current;
}

/** Takes the effective preferences (`ui.*` entries) and applies them to the page. */
export function applyDisplay(effective: Array<{ key: string; value: unknown }>): DisplayPreferences {
  const value = (key: string) => effective.find((e) => e.key === key)?.value;
  const text = (key: string, fallback: string) => {
    const v = value(key);
    return typeof v === 'string' && v ? v : fallback;
  };
  const size = Number(value('ui.defaultPageSize'));
  current = {
    theme: text('ui.theme', DEFAULT_DISPLAY.theme),
    dateFormat: text('ui.dateFormat', DEFAULT_DISPLAY.dateFormat),
    timeZone: text('ui.timeZone', DEFAULT_DISPLAY.timeZone),
    locale: text('ui.locale', DEFAULT_DISPLAY.locale),
    pageSize: Number.isFinite(size) && size > 0 ? size : DEFAULT_DISPLAY.pageSize,
    landingPage: text('ui.landingPage', DEFAULT_DISPLAY.landingPage),
  };
  paint();
  return current;
}

/** Back to the defaults, as on sign-out. */
export function resetDisplay(): void {
  current = { ...DEFAULT_DISPLAY };
  paint();
}

/** The route the landing-page preference names. */
export function landingRoute(): string {
  return current.landingPage === 'dashboard' ? '/' : `/${current.landingPage}`;
}

/** A calendar day (a value at midnight UTC), in the preferred date format; never shifted by a time zone. */
export function formatDay(d: Date): string {
  return day(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate());
}

/** An instant, in the preferred time zone and date format, to the minute. */
export function formatInstant(d: Date): string {
  if (current.timeZone === 'UTC' && current.dateFormat === DEFAULT_DISPLAY.dateFormat) {
    return d.toISOString().slice(0, 16).replace('T', ' ');
  }
  let parts: Record<string, string>;
  try {
    parts = Object.fromEntries(new Intl.DateTimeFormat('en-GB', {
      timeZone: current.timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit',
      minute: '2-digit', hourCycle: 'h23',
    }).formatToParts(d).map((p) => [p.type, p.value]));
  } catch {
    return d.toISOString().slice(0, 16).replace('T', ' ');
  }
  return `${day(Number(parts['year']), Number(parts['month']), Number(parts['day']))} ${parts['hour']}:${parts['minute']}`;
}

function day(year: number, month: number, date: number): string {
  const dd = String(date).padStart(2, '0');
  const mm = String(month).padStart(2, '0');
  switch (current.dateFormat) {
    case 'DD/MM/YYYY': return `${dd}/${mm}/${year}`;
    case 'MM/DD/YYYY': return `${mm}/${dd}/${year}`;
    case 'DD MMM YYYY': return `${dd} ${MONTHS[month - 1]} ${year}`;
    default: return `${year}-${mm}-${dd}`;
  }
}

function paint(): void {
  if (typeof document === 'undefined') {
    return;
  }
  document.documentElement.dataset['theme'] = current.theme;
  document.documentElement.lang = current.locale;
}
