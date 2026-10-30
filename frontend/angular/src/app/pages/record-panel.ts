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

import { Component, computed, inject, input, output } from '@angular/core';
import { Session } from '../session';
import { CustomFieldsForm } from './custom-fields-form';
import { Resource } from './resources';

/** One record's details, with its custom fields beneath them. */
@Component({
  selector: 'app-record-panel',
  imports: [CustomFieldsForm],
  template: `
    <aside class="panel" data-testid="record-panel" aria-label="Record details">
      <header>
        <h2>{{ title() }}</h2>
        <button class="link" (click)="closed.emit()" aria-label="Close panel">Close</button>
      </header>
      <dl>
        @for (c of rest(); track c.field) {
          <div><dt>{{ c.header }}</dt><dd>{{ row()[c.field] || '—' }}</dd></div>
        }
      </dl>
      @if (resource().customKind; as kind) {
        @if (session.can('CUSTOM_FIELD_READ')) {
          <app-custom-fields-form [kind]="kind" [recordId]="row()['id']" />
        }
      }
    </aside>
  `,
})
export class RecordPanel {
  protected readonly session = inject(Session);
  readonly resource = input.required<Resource>();
  readonly row = input.required<Record<string, string>>();
  readonly closed = output<void>();

  readonly title = computed(() => this.row()[this.resource().columns[0].field]);
  readonly rest = computed(() => this.resource().columns.slice(1));
}
