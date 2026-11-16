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

import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { Session } from '../session';
import { CustomFieldsForm } from './custom-fields-form';
import { Resource } from './resources';
import { Cell } from './cell';
import { MasterDataActions } from './master-data-actions';
import { MasterDataForm } from './master-data-form';
import type { MasterKind } from '../gateway/master-data-types';

/** One record's details, with its custom fields beneath them. */
@Component({
  selector: 'app-record-panel',
  imports: [CustomFieldsForm, Cell, MasterDataActions, MasterDataForm],
  template: `
    <aside class="panel" data-testid="record-panel" aria-label="Record details">
      <header>
        <h2>{{ title() }}</h2>
        <button class="link" (click)="closed.emit()" aria-label="Close panel">Close</button>
      </header>
      @if (maintained(); as kind) {
        @if (editing() && session.orgUnitId(); as unit) {
          @defer (on immediate) {
            <app-master-data-form [kind]="kind" [orgUnitId]="unit" [recordId]="row()['id']"
                                  (saved)="editing.set(false); changed.emit()" (closed)="editing.set(false)" />
          }
        }
      }
      @if (!editing()) {
        <dl>
          @for (c of rest(); track c.field) {
            <div><dt>{{ c.header }}</dt><dd>@if (row()[c.field]) { <app-cell [field]="c.field" [value]="row()[c.field]" /> } @else { — }</dd></div>
          }
        </dl>
        @if (maintained(); as kind) {
          @defer (on immediate) {
            <app-master-data-actions [kind]="kind" [recordId]="row()['id']" [status]="row()['status'] ?? ''"
                                     [label]="title()" (edit)="editing.set(true)" (changed)="changed.emit()" />
          }
        }
      }
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
  /** A record was edited or switched off or on, so the list behind it is stale. */
  readonly changed = output<void>();
  protected readonly editing = signal(false);

  /** The register this is, when it can be edited here. */
  readonly maintained = computed<MasterKind | null>(() => {
    const key = this.resource().key;
    return key === 'partners' || key === 'vehicles' || key === 'drivers' || key === 'terminals' ? key : null;
  });

  constructor() {
    // Another record opens in the same panel: leave any edit of the last one.
    effect(() => { this.row()['id']; this.editing.set(false); }, { allowSignalWrites: true });
  }

  readonly title = computed(() => this.row()[this.resource().columns[0].field]);
  readonly rest = computed(() => this.resource().columns.slice(1));
}
