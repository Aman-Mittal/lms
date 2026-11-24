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
 * The words of the confirm dialog, shared by both consoles. A destructive action names itself and the record it acts on ("Cancel trip T-100?"),
 * offers one danger button labelled with the action ("Cancel trip") and a plain way out ("Keep it"). Framework-free; each console's
 * ConfirmDialog component renders what this builds.
 */

/** What the dialog says. `title` is the question, `confirmLabel` the danger button, `dismissLabel` the safe one. */
export interface ConfirmWording {
  title: string;
  confirmLabel: string;
  dismissLabel: string;
  /** One sentence on the consequence, shown under the title; empty when there is nothing to add. */
  body: string;
}

/** The reason a dialog asks for before it lets the action through. `testId` is the id the field already had on its screen. */
export interface ReasonField {
  label: string;
  testId: string;
  required?: boolean;
  placeholder?: string;
  /** A tall box for a sentence or two, instead of one line. */
  multiline?: boolean;
}

/** An action waiting for its confirmation: what to say, whether to ask for a reason, and what to do once it is given. */
export interface PendingConfirm {
  wording: ConfirmWording;
  reason?: ReasonField;
  /** Danger (the default) for an action that cannot be taken back; neutral for one that can. */
  tone?: 'danger' | 'neutral';
  run: (reason: string) => void | Promise<void>;
}

export const DISMISS_LABEL = 'Keep it';

/**
 * Builds the wording for an action such as "Cancel trip" on a record such as "T-100". `tail` finishes the question when the
 * record is not the last thing in it ("Remove consignment C-1" + "from this load" gives "Remove consignment C-1 from this load?").
 * Blank parts are left out, so a record-less action still reads well.
 */
export function confirmWording(action: string, record = '', body = '', tail = ''): ConfirmWording {
  const question = [action, record, tail].map((part) => part.trim()).filter(Boolean).join(' ');
  return { title: `${question}?`, confirmLabel: action.trim(), dismissLabel: DISMISS_LABEL, body: body.trim() };
}

/** A reason is acceptable when it holds something besides white space. */
export function reasonGiven(reason: string): boolean {
  return reason.trim().length > 0;
}

/**
 * Where Tab (or Shift+Tab) lands among `count` focusable controls when it must wrap instead of leaving the dialog. `index` is the
 * control that has focus now, or -1 when none of them has. Browsers keep a modal dialog's page inert but still let Tab walk out to
 * their own toolbar, so each console's dialog takes the key and uses this to stay inside.
 */
export function wrapFocus(count: number, index: number, backwards: boolean): number {
  if (count <= 0) { return -1; }
  if (index < 0) { return backwards ? count - 1 : 0; }
  return (index + (backwards ? count - 1 : 1)) % count;
}
