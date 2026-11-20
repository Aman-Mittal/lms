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
import { corridorPoints, permissionCodes } from './small-ops';

test('permission codes are split, upper-cased and deduplicated in order', () => {
  assert.deepEqual(permissionCodes('invoice_approve, FINANCE_HEAD  invoice_approve\nops'), ['INVOICE_APPROVE', 'FINANCE_HEAD', 'OPS']);
  assert.deepEqual(permissionCodes('  , '), []);
});

test('corridor points read one pair per line', () => {
  assert.deepEqual(corridorPoints('77.1, 28.6\n72.8 19.0\n\n'), [[77.1, 28.6], [72.8, 19]]);
});

test('a malformed corridor line is named', () => {
  assert.throws(() => corridorPoints('77.1, 28.6\nnope'), /Line 2/);
  assert.throws(() => corridorPoints('200, 10\n1, 1'), /Line 1/);
  assert.throws(() => corridorPoints('1, 2, 3\n1, 1'), /Line 1/);
});

test('a corridor needs two points', () => {
  assert.throws(() => corridorPoints('77.1, 28.6'), /at least two points/);
});
