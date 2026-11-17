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

import { Component, input } from '@angular/core';

/** "Nothing here": a calm box with the sentence that says why, and optionally what to do about it. */
@Component({
  selector: 'app-empty-state',
  template: `
    <div class="empty-state" [attr.data-testid]="testId() || null">
      <svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16v12H4zM4 7l3-3h10l3 3M9 12h6" /></svg>
      <p>{{ message() }}</p>
      <ng-content />
    </div>
  `,
})
export class EmptyState {
  readonly message = input.required<string>();
  readonly testId = input('');
}

/** "Working on it": a spinner and a sentence; announced politely so a reader knows the list is on its way. */
@Component({
  selector: 'app-loading-state',
  template: `<div class="loading-state" role="status" [attr.data-testid]="testId() || null"><p>{{ message() }}</p></div>`,
})
export class LoadingState {
  readonly message = input('Loading…');
  readonly testId = input('');
}
