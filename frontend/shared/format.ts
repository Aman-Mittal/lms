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
 * Framework-free formatting shared by the Angular and React consoles: dates and instants in the signed-in person's
 * display preferences, money, quantities, weights, enum labels and status tones. Nothing here touches the DOM or a
 * framework; the consoles' display modules bind the preferences once (bindFormatPreferences) and everything else
 * only calls these functions. Intl is the only dependency.
 */

/** The part of the display preferences the date formatters read. */
export interface FormatPreferences {
  dateFormat: string;
  timeZone: string;
  locale: string;
}

export const DEFAULT_FORMAT: FormatPreferences = { dateFormat: 'YYYY-MM-DD', timeZone: 'UTC', locale: 'en-GB' };

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

let source: () => FormatPreferences = () => DEFAULT_FORMAT;

/** Where the formatters read the current preferences; display.ts calls this once at load. */
export function bindFormatPreferences(read: () => FormatPreferences): void {
  source = read;
}

type Dateish = Date | string | number | null | undefined;

function toDate(value: Dateish): Date | null {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const d = value instanceof Date ? value : new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

// ---------------------------------------------------------------------------------------------------- dates

/** A calendar day (a value at midnight UTC), in the preferred date format; never shifted by a time zone. */
export function formatDay(d: Date, prefs: FormatPreferences = source()): string {
  return day(prefs, d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate());
}

/** An instant, in the preferred time zone and date format, to the minute. */
export function formatInstant(d: Date, prefs: FormatPreferences = source()): string {
  if (prefs.timeZone === 'UTC' && prefs.dateFormat === DEFAULT_FORMAT.dateFormat) {
    return d.toISOString().slice(0, 16).replace('T', ' ');
  }
  let parts: Record<string, string>;
  try {
    parts = Object.fromEntries(new Intl.DateTimeFormat('en-GB', {
      timeZone: prefs.timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit',
      minute: '2-digit', hourCycle: 'h23',
    }).formatToParts(d).map((p) => [p.type, p.value]));
  } catch {
    return d.toISOString().slice(0, 16).replace('T', ' ');
  }
  return `${day(prefs, Number(parts['year']), Number(parts['month']), Number(parts['day']))} ${parts['hour']}:${parts['minute']}`;
}

function day(prefs: FormatPreferences, year: number, month: number, date: number): string {
  const dd = String(date).padStart(2, '0');
  const mm = String(month).padStart(2, '0');
  switch (prefs.dateFormat) {
    case 'DD/MM/YYYY': return `${dd}/${mm}/${year}`;
    case 'MM/DD/YYYY': return `${mm}/${dd}/${year}`;
    case 'DD MMM YYYY': return `${dd} ${MONTHS[month - 1]} ${year}`;
    default: return `${year}-${mm}-${dd}`;
  }
}

const DAY_ONLY = /^\d{4}-\d{2}-\d{2}$/;
const TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})$/;

/**
 * A calendar day in the preferred format. A "YYYY-MM-DD" string or a Date at midnight UTC is a day and is never
 * shifted by a time zone; any other instant is shown as the day it falls on in the preferred zone. Empty or
 * unparseable input gives "".
 */
export function formatDate(value: Dateish, prefs: FormatPreferences = source()): string {
  if (typeof value === 'string' && DAY_ONLY.test(value)) {
    return formatDay(new Date(`${value}T00:00:00Z`), prefs);
  }
  const d = toDate(value);
  if (!d) {
    return '';
  }
  return formatInstant(d, prefs).slice(0, -6);
}

/** An instant, to the minute, in the preferred zone and date format; "" for empty or unparseable input. */
export function formatDateTime(value: Dateish, prefs: FormatPreferences = source()): string {
  if (typeof value === 'string' && DAY_ONLY.test(value)) {
    return formatDate(value, prefs);
  }
  const d = toDate(value);
  return d ? formatInstant(d, prefs) : '';
}

/** "3 hours ago", "in 2 days": the nearest sensible unit, in the preferred locale. `now` is for tests. */
export function formatRelative(value: Dateish, now: Date = new Date(), prefs: FormatPreferences = source()): string {
  const d = toDate(value);
  if (!d) {
    return '';
  }
  const seconds = Math.round((d.getTime() - now.getTime()) / 1000);
  const steps: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ['year', 31536000], ['month', 2592000], ['week', 604800], ['day', 86400], ['hour', 3600], ['minute', 60],
  ];
  let rtf: Intl.RelativeTimeFormat;
  try {
    rtf = new Intl.RelativeTimeFormat(prefs.locale, { numeric: 'auto' });
  } catch {
    rtf = new Intl.RelativeTimeFormat('en-GB', { numeric: 'auto' });
  }
  for (const [unit, size] of steps) {
    if (Math.abs(seconds) >= size) {
      return rtf.format(Math.round(seconds / size), unit);
    }
  }
  return rtf.format(seconds, 'second');
}

/**
 * The preferred rendering of a text that is a full ISO-8601 timestamp or a "YYYY-MM-DD" day, which is how a JSON
 * contract carries both; null when the text is anything else.
 */
export function formatIfDateText(text: string, prefs: FormatPreferences = source()): string | null {
  if (DAY_ONLY.test(text)) {
    const day = new Date(`${text}T00:00:00Z`);
    return Number.isNaN(day.getTime()) ? null : formatDay(day, prefs);
  }
  if (!TIMESTAMP.test(text)) {
    return null;
  }
  const d = new Date(text);
  if (Number.isNaN(d.getTime())) {
    return null;
  }
  return d.toISOString().endsWith('T00:00:00.000Z') ? formatDay(d, prefs) : formatInstant(d, prefs);
}

// ---------------------------------------------------------------------------------------------------- numbers

type Numeric = number | string | null | undefined;

function toNumber(value: Numeric): number | null {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}

const moneyFormats = new Map<string, Intl.NumberFormat>();

/**
 * Money with Indian digit grouping and the currency symbol, two decimals: formatMoney(12500.5) is "₹12,500.50".
 * The currency is an ISO code and defaults to INR. Null or empty input gives ""; text that is not a number is
 * returned as it came, so a bad value is visible rather than hidden.
 */
export function formatMoney(value: Numeric, currency: string | null | undefined = 'INR'): string {
  const n = toNumber(value);
  if (n === null) {
    return value === null || value === undefined ? '' : String(value);
  }
  const code = (currency || 'INR').toUpperCase();
  let nf = moneyFormats.get(code);
  if (!nf) {
    try {
      nf = new Intl.NumberFormat('en-IN', { style: 'currency', currency: code, minimumFractionDigits: 2, maximumFractionDigits: 2 });
    } catch {
      return `${code} ${new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n)}`;
    }
    moneyFormats.set(code, nf);
  }
  return nf.format(n);
}

const quantityFormat = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 3 });

/** A count or measure with Indian grouping, up to three decimals, and the unit after it: "1,250 pcs". */
export function formatQuantity(value: Numeric, unit?: string | null): string {
  const n = toNumber(value);
  if (n === null) {
    return value === null || value === undefined ? '' : String(value);
  }
  return unit ? `${quantityFormat.format(n)} ${unit}` : quantityFormat.format(n);
}

/** A weight; `unit` is what the value is in (kg by default): "20,00,000 kg". */
export function formatWeight(value: Numeric, unit: string = 'kg'): string {
  return formatQuantity(value, unit);
}

// ---------------------------------------------------------------------------------------------------- labels and tones

const LABELS: Record<string, string> = {
  DAMAGE: 'Damaged',
  FORCE: 'Force closed',
  SHORT: 'Short closed',
};

const ACRONYMS = new Set(['OTP', 'POD', 'ASN', 'LTL', 'FTL', 'GST', 'ID', 'KYC', 'API', 'URL', 'PAN', 'IRN', 'SLA', 'ETA', 'RFQ', 'CSV', 'XLSX']);

/**
 * A person's word for an enum value: a mapped label when there is one, else the value in sentence case with its
 * underscores as spaces ("PENDING_APPROVAL" is "Pending approval", "OTP_ISSUED" is "OTP issued"). Anything that is not
 * an upper-case enum (a name, a free-text status) comes back as it is; null or empty gives "".
 */
export function labelFor(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '';
  }
  const mapped = LABELS[value];
  if (mapped) {
    return mapped;
  }
  if (!/^[A-Z][A-Z0-9_]*$/.test(value)) {
    return value;
  }
  const words = value.split('_').filter(Boolean).map((w) => w.toLowerCase());
  const text = words.map((w) => (ACRONYMS.has(w.toUpperCase()) ? w.toUpperCase() : w)).join(' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';

const TONES: Record<Tone, string[]> = {
  success: ['APPROVED', 'ACTIVE', 'CONFIRMED', 'DELIVERED', 'COMPLETED', 'PAID', 'RELEASED', 'RECEIVED', 'ACCEPTED',
    'AWARDED', 'WON', 'CREATED', 'RESOLVED', 'SETTLED', 'VALIDATED', 'DISPATCHED', 'PACKED', 'DEPARTED', 'ISSUED',
    'FULLY_PLANNED', 'VERIFIED', 'UPDATED', 'MATCHED', 'CONVERTED', 'APPROVED_FOR_PAYMENT', 'OK', 'YES', 'GRANTED'],
  warning: ['PENDING', 'PENDING_APPROVAL', 'PENDING_VERIFICATION', 'PARTIAL', 'PARTIALLY_PAID', 'PARTIALLY_PLANNED',
    'SUSPENDED', 'UNDER_REVIEW', 'UNDER_INVESTIGATION', 'OTP_ISSUED', 'DISPUTED', 'OVERDUE', 'EXPIRED', 'SKIPPED',
    'NO_LIMIT', 'REQUESTED', 'ON_HOLD', 'AT_RISK', 'LATE', 'EXTENDED', 'AWAITING_PROOF'],
  danger: ['REJECTED', 'FAILED', 'REFUSED', 'REVERSED', 'REVOKED', 'BLOCKED', 'DAMAGE', 'LOST', 'ERROR', 'DENIED', 'NOT_RECEIVED'],
  info: ['OPEN', 'PLANNED', 'IN_TRANSIT', 'EXPECTED', 'ARRIVED', 'SUBMITTED', 'RAISED', 'ASSIGNED', 'OFFERED', 'PICKED',
    'SPLIT', 'IN_PROGRESS', 'SCHEDULED', 'NEW'],
  neutral: ['DRAFT', 'CLOSED', 'CANCELLED', 'INACTIVE', 'RETIRED', 'SUPERSEDED', 'NONE'],
};

const TONE_OF = new Map<string, Tone>();
for (const [tone, values] of Object.entries(TONES) as Array<[Tone, string[]]>) {
  for (const v of values) {
    TONE_OF.set(v, tone);
  }
}

/** The tone a status is drawn in: a known value by name, an unknown one by the words in it, else neutral. */
export function toneFor(status: string | null | undefined): Tone {
  if (!status) {
    return 'neutral';
  }
  const key = status.trim().toUpperCase().replace(/[\s-]+/g, '_');
  const known = TONE_OF.get(key);
  if (known) {
    return known;
  }
  if (/FAIL|REJECT|REFUS|REVERS|REVOK|BLOCK|ERROR|DENIED/.test(key)) {
    return 'danger';
  }
  if (/PENDING|PARTIAL|OVERDUE|SUSPEND|REVIEW|INVESTIGAT|AWAIT|HOLD|EXPIR/.test(key)) {
    return 'warning';
  }
  if (/APPROV|CONFIRM|COMPLET|DELIVER|PAID|SETTLE|RESOLV|ACTIVE|VERIFIED|SUCCESS/.test(key)) {
    return 'success';
  }
  return 'neutral';
}

// ---------------------------------------------------------------------------------------------------- generic tables

export type ColumnKind = 'status' | 'money' | 'weight' | 'quantity' | 'text';

const STATUS_FIELD = /^(status|state|outcome|severity|verdict|decision|entryType|\w+(Status|State))$/;
const MONEY_FIELD = /^(amount|rate|balance|price|debit|credit|limit|cost|total|\w+(Amount|Rate|RatePerKg|RatePerPallet|RatePerPalletDay|Balance|Price|Payable|Outstanding|Limit|Cost|Total))$/;

/**
 * How a column of a generic record table is drawn, from its field name and a sample value: statuses as badges (only
 * when the value is an upper-case enum), money and weights as formatted right-aligned numbers, the rest as text.
 */
export function columnKind(field: string, value: string = ''): ColumnKind {
  if (STATUS_FIELD.test(field)) {
    return /^[A-Z][A-Z0-9_]*$/.test(value) ? 'status' : 'text';
  }
  if (!value || !Number.isFinite(Number(value))) {
    return 'text';
  }
  if (MONEY_FIELD.test(field)) {
    return 'money';
  }
  if (/(WeightKg|Kg)$/.test(field) || field === 'weight') {
    return 'weight';
  }
  if (field === 'quantity') {
    return 'quantity';
  }
  return 'text';
}

/** Whether a column is numeric, so its header and cells are right-aligned. */
export function isNumericColumn(field: string): boolean {
  return MONEY_FIELD.test(field) || /(WeightKg|Kg)$/.test(field) || field === 'weight' || field === 'quantity';
}

const DATE_FIELD = /(Date|On|At|Until|Start|End|By|From|To|Day|Window)$/;

/** Whether a field holds a date or instant, so its cell should not break at the hyphens. */
export function isDateColumn(field: string): boolean {
  return DATE_FIELD.test(field) && field !== 'to' && field !== 'from';
}
