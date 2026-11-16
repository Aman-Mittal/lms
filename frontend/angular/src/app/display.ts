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

import { bindFormatPreferences } from '../../../shared/format';

// The shared formatters (shared/format.ts) read the date preferences from here; consoles import formatDay and
// formatInstant from this module as before.
export { formatDay, formatInstant } from '../../../shared/format';

export interface DisplayPreferences {
  theme: string;
  dateFormat: string;
  timeZone: string;
  locale: string;
  pageSize: number;
  landingPage: string;
  /** `ui.clientCache`: whether maps, tracks and tiles may be kept in the browser. */
  clientCache: boolean;
}

export const DEFAULT_DISPLAY: DisplayPreferences = {
  theme: 'system', dateFormat: 'YYYY-MM-DD', timeZone: 'UTC', locale: 'en-GB', pageSize: 50,
  landingPage: 'dashboard', clientCache: true,
};

let current: DisplayPreferences = { ...DEFAULT_DISPLAY };

bindFormatPreferences(() => current);

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
    // Anything but an explicit false keeps the default (on).
    clientCache: value('ui.clientCache') !== false,
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

function paint(): void {
  if (typeof document === 'undefined') {
    return;
  }
  document.documentElement.dataset['theme'] = current.theme;
  document.documentElement.lang = current.locale;
}
