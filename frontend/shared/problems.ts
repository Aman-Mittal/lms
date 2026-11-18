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
 * Plain messages for the API's problem codes, shared by both consoles' error banners. The API answers every refusal with
 * a stable machine-readable `code` and a `detail` written for a developer; a person at a desk needs the sentence that
 * says what happened and what to do next. The banner shows this sentence first, the server's detail under it when it
 * adds anything, and the code in small print so support can look it up. Framework-free; nothing here touches the DOM.
 */

/** What a person is told for the codes met most often. Codes not listed here fall back on their ending (see SUFFIXES). */
export const PROBLEM_MESSAGES: Readonly<Record<string, string>> = {
  'concurrent-modification': 'Someone else changed this record while you were working on it. Reload it and try again.',
  'validation-failed': 'Some of the details are missing or not valid. Check the highlighted fields and try again.',
  'authentication-required': 'Your session has ended. Sign in again to continue.',
  'access-denied': 'You do not have the right to do this. Ask an administrator if you need it.',
  'not-found': 'That record no longer exists, or the id is wrong.',
  'duplicate-resource': 'A record with these details already exists.',
  'credit-limit-exceeded': 'This would take the customer over their credit limit. Ask for an override, or reduce the amount.',
  'idempotency-key-reused': 'This request was already sent with different details. Start it again.',
  'internal-error': 'Something went wrong on our side. Try again in a moment; if it keeps happening, tell support.',
  'invalid-cursor': 'The list changed while you were paging through it. Reload it.',
  'cursor-invalid': 'The list changed while you were paging through it. Reload it.',
  'order-not-editable': 'This order can no longer be edited because it has moved on.',
  'order-has-no-lines': 'The order has no lines. Add at least one line first.',
  'order-not-validated': 'Validate the order first.',
  'order-illegal-transition': 'The order cannot move to that state from where it is now.',
  'consignment-illegal-transition': 'The consignment cannot move to that state from where it is now.',
  'load-not-open': 'The load is no longer open, so it cannot be changed.',
  'load-not-awarded': 'A trip can only be raised against a load that has been awarded to a vendor.',
  'load-already-has-trip': 'This load already has a live trip. Cancel it first to raise another.',
  'load-is-empty': 'The load has no consignments aboard, so it cannot be planned.',
  'load-no-taken': 'That load number is already in use. Choose a different one.',
  'trip-no-taken': 'That trip number is already in use. Choose a different one.',
  'trip-not-cancellable': 'The trip has already left the yard, so it cannot be cancelled.',
  'trip-already-dispatched': 'The trip has already been dispatched.',
  'dispatch-blocked': 'The trip cannot be dispatched yet. The details below say what is still missing.',
  'assignment-not-compliant': 'That vehicle or driver does not meet the compliance checks for this trip.',
  'vehicle-unavailable': 'That vehicle is not available. Choose another.',
  'eway-bill-required': 'This unit needs an e-way bill on every consignment before the trip leaves.',
  'tare-not-recorded': 'The empty weight has not been recorded yet. Weigh the vehicle at the gate first.',
  'weighing-incomplete': 'The weighing is not finished yet. Record both weights at the gate first.',
  'trip-stops-in-progress': 'A stop has already been reached, so the plan of stops can no longer be replaced.',
  'vehicle-inactive': 'That vehicle is switched off. Choose an active one.',
  'vendor-not-active': 'That vendor is switched off. Choose an active one.',
  'partner-not-transactable': 'That partner cannot be used for new business in its present state.',
  'no-eligible-vendors': 'No vendor is eligible for this load. Check the allocation rules and the routing guide.',
  'no-routing-guide': 'There is no routing guide for this lane yet.',
  'gate-pass-required': 'The vehicle needs a gate pass before it can enter.',
  'gate-pass-expired': 'That gate pass has expired.',
  'gate-pass-used': 'That gate pass has already been used.',
  'gate-entry-already-open': 'That vehicle is already inside. Record its exit first.',
  'bin-over-capacity': 'The bin does not have room for that quantity.',
  'manual-charge-already-billed': 'This charge has already been billed, so it can no longer be changed.',
  'proof-already-recorded': 'Proof of delivery has already been recorded for this consignment.',
  'number-series-in-use': 'That number series is in use and cannot be changed or removed.',
  'vendor-payment-exceeds-balance': 'The payment is more than the invoice balance.',
  'webhook-url-not-https': 'A webhook address must start with https.',
};

/** The general meaning of a code by its ending, for the many codes of one kind (client-code-taken, order-line-invalid). */
const SUFFIXES: ReadonlyArray<readonly [string, string]> = [
  ['-taken', 'That name or code is already in use. Choose a different one.'],
  ['-illegal-transition', 'That change is not allowed from the present state.'],
  ['-not-found', 'That record no longer exists, or the id is wrong.'],
  ['-required', 'A required detail is missing.'],
  ['-missing', 'A required detail is missing.'],
  ['-inactive', 'That record is switched off. Choose an active one.'],
  ['-in-use', 'That record is still in use, so it cannot be changed or removed.'],
  ['-invalid', 'One of the details is not valid. Check it and try again.'],
  ['-expired', 'That has expired. Start again.'],
];

/** The plain message for a problem code; an empty string when the code is blank or not known. */
export function problemMessage(code: string | null | undefined): string {
  const key = (code ?? '').trim().toLowerCase();
  if (!key) {
    return '';
  }
  const exact = PROBLEM_MESSAGES[key];
  if (exact) {
    return exact;
  }
  return SUFFIXES.find(([suffix]) => key.endsWith(suffix))?.[1] ?? '';
}

/** A refusal as the banner shows it. */
export interface ProblemView {
  /** The sentence a person reads first. */
  headline: string;
  /** The server's own words, when they add to the headline; empty when they repeat it or there are none. */
  detail: string;
  /** The stable code, for the small print; empty when the problem has none. */
  code: string;
}

/** What to show for a refusal with this `code` and server `detail`. */
export function describeProblem(code: string | null | undefined, detail: string | null | undefined): ProblemView {
  const shown = (code ?? '').trim();
  const text = (detail ?? '').trim();
  const plain = problemMessage(shown);
  if (!plain) {
    return { headline: text, detail: '', code: shown };
  }
  return { headline: plain, detail: text && text !== plain ? text : '', code: shown };
}

const CODE_FIRST = /^([a-z][a-z0-9]*(?:-[a-z0-9]+)+): ([\s\S]+)$/;
const CODE_LAST = /^([\s\S]+?) \(([a-z][a-z0-9]*(?:-[a-z0-9]+)+)\)$/;

/**
 * The code and message inside a refusal that was flattened to one string (older screens kept "code: message", or
 * "message (code)"); a text with neither form is returned whole with no code.
 */
export function splitProblemText(text: string): { code: string; message: string } {
  const first = CODE_FIRST.exec(text);
  if (first) {
    return { code: first[1], message: first[2] };
  }
  const last = CODE_LAST.exec(text);
  return last ? { code: last[2], message: last[1] } : { code: '', message: text };
}
