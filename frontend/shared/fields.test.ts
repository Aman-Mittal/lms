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
import { fieldText } from './fields';

test('an aria-label becomes a real label and the placeholder stays a hint', () => {
  assert.deepEqual(fieldText('Subject id', 'Trip, consignment or order id'),
    { label: 'Subject id', help: 'Trip, consignment or order id', real: true });
  assert.deepEqual(fieldText('Quantity', 'Quantity'), { label: 'Quantity', help: '', real: true });
});

test('a long placeholder with a parenthesis is split into a label and help', () => {
  assert.deepEqual(fieldText('', 'Order number (blank: next in series)'),
    { label: 'Order number', help: 'blank: next in series', real: false });
  assert.deepEqual(fieldText('', 'Legal name'), { label: 'Legal name', help: '', real: false });
});
