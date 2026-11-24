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
 * Bulk amend and bulk cancel of consignments: the limits and the small rules both consoles share, with no framework in them.
 * The server judges every item on its own (a refusal never stops the rest), so these only build what is sent and word what came back.
 */

/** The most consignments one bulk request takes (the server's ConsignmentBulkService.MAX_ITEMS). */
export const MAX_BULK_CONSIGNMENTS = 200;

/** The permission both bulk actions need, as the server checks it. */
export const BULK_CONSIGNMENT_PERMISSION = 'LOAD_CREATE';

export type ParticularKey = 'contactName' | 'contactPhone' | 'consigneeReference' | 'clientReference'
  | 'deliveryWindowFrom' | 'deliveryWindowTo' | 'remarks';

/** The particulars an amendment may name, in the order the single amend form shows them. */
export const PARTICULAR_FIELDS: { key: ParticularKey; label: string }[] = [
  { key: 'contactName', label: 'Contact name' },
  { key: 'contactPhone', label: 'Contact phone' },
  { key: 'consigneeReference', label: 'Consignee reference' },
  { key: 'clientReference', label: 'Client reference' },
  { key: 'deliveryWindowFrom', label: 'Deliver from' },
  { key: 'deliveryWindowTo', label: 'Deliver by' },
  { key: 'remarks', label: 'Remarks' },
];

/**
 * The changes to send for every picked consignment: a field with text is set to it, a field marked to be cleared is sent as empty
 * text, and a field left alone is left out, so what a consignment already says is kept.
 */
export function bulkChanges(values: Partial<Record<ParticularKey, string>>, cleared: Partial<Record<ParticularKey, boolean>>):
  Partial<Record<ParticularKey, string>> {
  const changes: Partial<Record<ParticularKey, string>> = {};
  for (const { key } of PARTICULAR_FIELDS) {
    const text = (values[key] ?? '').trim();
    if (cleared[key]) {
      changes[key] = '';
    } else if (text) {
      changes[key] = text;
    }
  }
  return changes;
}

/** What one item came back as. `ok` is false for a refusal. */
export interface BulkOutcomeLike {
  ok: boolean;
}

/** The one-line summary of a bulk request: "3 amended, 1 refused", leaving out a part that is zero. */
export function bulkSummary(results: BulkOutcomeLike[], done: 'amended' | 'cancelled'): string {
  const ok = results.filter((r) => r.ok).length;
  const refused = results.length - ok;
  const parts = [ok > 0 ? `${ok} ${done}` : '', refused > 0 ? `${refused} refused` : ''].filter(Boolean);
  return parts.length > 0 ? parts.join(', ') : `No consignments ${done}`;
}

/** The message shown when a pick would pass the limit. */
export const PICK_LIMIT_MESSAGE = `At most ${MAX_BULK_CONSIGNMENTS} consignments can be picked at once.`;

/**
 * Adds `ids` to the picked ones, up to the limit. `truncated` says some did not fit.
 */
export function pickUpTo(picked: string[], ids: string[], limit = MAX_BULK_CONSIGNMENTS): { picked: string[]; truncated: boolean } {
  const next = [...picked];
  let truncated = false;
  for (const id of ids) {
    if (next.includes(id)) {
      continue;
    }
    if (next.length >= limit) {
      truncated = true;
      break;
    }
    next.push(id);
  }
  return { picked: next, truncated };
}
