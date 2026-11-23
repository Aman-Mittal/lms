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

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { DISMISS_LABEL, confirmWording, reasonGiven, wrapFocus } from './confirm';

test('the question names the action and the record', () => {
  const w = confirmWording('Cancel trip', 'T-100');
  assert.equal(w.title, 'Cancel trip T-100?');
  assert.equal(w.confirmLabel, 'Cancel trip');
  assert.equal(w.dismissLabel, DISMISS_LABEL);
  assert.equal(w.dismissLabel, 'Keep it');
});

test('a tail finishes the question and blank parts are left out', () => {
  assert.equal(confirmWording('Remove consignment', 'C-1', '', 'from this load').title, 'Remove consignment C-1 from this load?');
  assert.equal(confirmWording('Clear corridor', '  ').title, 'Clear corridor?');
  assert.equal(confirmWording('Void invoice', 'INV-7', ' It stays on record. ').body, 'It stays on record.');
});

test('a reason must hold more than white space', () => {
  assert.equal(reasonGiven('  '), false);
  assert.equal(reasonGiven(' no show '), true);
});

test('focus wraps at both ends of the dialog', () => {
  assert.equal(wrapFocus(3, 2, false), 0);
  assert.equal(wrapFocus(3, 0, true), 2);
  assert.equal(wrapFocus(3, 1, false), 2);
  assert.equal(wrapFocus(3, -1, false), 0);
  assert.equal(wrapFocus(3, -1, true), 2);
  assert.equal(wrapFocus(0, -1, false), -1);
});
