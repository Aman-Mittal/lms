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
import { PROBLEM_MESSAGES, describeProblem, problemMessage, splitProblemText } from './problems';

test('a known code is told in plain words', () => {
  assert.match(problemMessage('concurrent-modification'), /Someone else changed this record/);
  assert.equal(problemMessage(' Concurrent-Modification '), problemMessage('concurrent-modification'));
});

test('a code of a known kind falls back on its ending', () => {
  assert.match(problemMessage('webhook-name-taken'), /already in use/);
  assert.match(problemMessage('partner-illegal-transition'), /not allowed/);
  assert.match(problemMessage('proof-remark-required'), /missing/);
});

test('a blank or unknown code has no plain message', () => {
  assert.equal(problemMessage(''), '');
  assert.equal(problemMessage(null), '');
  assert.equal(problemMessage('something-new'), '');
});

test('the banner leads with the plain sentence, keeps the server detail and the code', () => {
  const view = describeProblem('concurrent-modification', 'The enquiry changed since it was read');
  assert.equal(view.headline, PROBLEM_MESSAGES['concurrent-modification']);
  assert.equal(view.detail, 'The enquiry changed since it was read');
  assert.equal(view.code, 'concurrent-modification');
});

test('a detail that repeats the sentence is not shown twice', () => {
  const view = describeProblem('not-found', PROBLEM_MESSAGES['not-found']);
  assert.equal(view.detail, '');
});

test('an unknown code shows the server detail as the headline, with the code', () => {
  assert.deepEqual(describeProblem('something-new', 'It broke'), { headline: 'It broke', detail: '', code: 'something-new' });
  assert.deepEqual(describeProblem('', 'It broke'), { headline: 'It broke', detail: '', code: '' });
});

test('every listed message is a sentence a person can read', () => {
  for (const [code, text] of Object.entries(PROBLEM_MESSAGES)) {
    assert.match(code, /^[a-z]+(-[a-z0-9]+)*$/);
    assert.match(text, /^[A-Z].*\.$/);
  }
});

test('a refusal flattened to one string is split back into its code and message', () => {
  assert.deepEqual(splitProblemText('concurrent-modification: The enquiry changed'),
    { code: 'concurrent-modification', message: 'The enquiry changed' });
  assert.deepEqual(splitProblemText('Already live (eway-bill-already-live)'),
    { code: 'eway-bill-already-live', message: 'Already live' });
  assert.deepEqual(splitProblemText('Could not reach the API. Try again.'),
    { code: '', message: 'Could not reach the API. Try again.' });
  assert.deepEqual(splitProblemText('Note: this is not a code'), { code: '', message: 'Note: this is not a code' });
});
