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
import { bulkChanges, bulkSummary, MAX_BULK_CONSIGNMENTS, pickUpTo } from './consignment-bulk';

test('only filled or explicitly cleared fields are changes', () => {
  assert.deepEqual(bulkChanges({ contactName: '  Meera ', remarks: '   ' }, { contactPhone: true }),
    { contactName: 'Meera', contactPhone: '' });
  assert.deepEqual(bulkChanges({}, {}), {});
});

test('a field marked to be cleared is empty text even when text was typed', () => {
  assert.deepEqual(bulkChanges({ remarks: 'x' }, { remarks: true }), { remarks: '' });
});

test('the summary names what happened and leaves out zeros', () => {
  assert.equal(bulkSummary([{ ok: true }, { ok: true }, { ok: true }, { ok: false }], 'amended'), '3 amended, 1 refused');
  assert.equal(bulkSummary([{ ok: true }], 'cancelled'), '1 cancelled');
  assert.equal(bulkSummary([{ ok: false }], 'cancelled'), '1 refused');
});

test('picking stops at the limit and says so', () => {
  const first = pickUpTo([], ['a', 'b', 'c'], 2);
  assert.deepEqual(first, { picked: ['a', 'b'], truncated: true });
  assert.deepEqual(pickUpTo(['a'], ['a', 'b'], 2), { picked: ['a', 'b'], truncated: false });
  assert.equal(MAX_BULK_CONSIGNMENTS, 200);
});
