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

import { Component, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  CustomFieldKind, CustomValues, FieldDefinition, LmsGateway,
} from '../gateway/lms-gateway';
import { Session } from '../session';

/** Everything a form field holds is text until it is saved. */
type Draft = Record<string, string>;

function draftOf(definitions: FieldDefinition[], values: CustomValues): Draft {
  const draft: Draft = {};
  for (const d of definitions) {
    const v = values[d.key];
    draft[d.key] = v === undefined ? '' : String(v);
  }
  return draft;
}

/** The typed value to store for a field's text, or undefined when it is empty. */
function valueOf(d: FieldDefinition, text: string): string | number | boolean | undefined {
  if (text === '') {
    return undefined;
  }
  switch (d.type) {
    case 'NUMBER': return Number(text);
    case 'BOOLEAN': return text === 'true';
    default: return text;
  }
}

@Component({
  selector: 'app-custom-fields-form',
  imports: [FormsModule],
  template: `
    @if (error()) {
      <p class="error" role="alert">{{ error() }}</p>
    } @else if (!ready()) {
      <p>Loading custom fields…</p>
    } @else if (definitions().length === 0) {
      <p class="muted" data-testid="no-custom-fields">No custom fields are defined for this kind of record.</p>
    } @else {
      <form (ngSubmit)="save()" data-testid="custom-fields">
        <h3>Custom fields</h3>
        @for (d of definitions(); track d.key) {
          <label>
            {{ d.required ? d.label + ' *' : d.label }}
            @switch (d.type) {
              @case ('SELECT') {
                <select [name]="d.key" [attr.data-testid]="'cf-' + d.key" [disabled]="!canWrite"
                        [ngModel]="draft()[d.key]" (ngModelChange)="set(d.key, $event)">
                  <option value=""></option>
                  @for (o of d.options; track o) { <option [value]="o">{{ o }}</option> }
                </select>
              }
              @case ('BOOLEAN') {
                <select [name]="d.key" [attr.data-testid]="'cf-' + d.key" [disabled]="!canWrite"
                        [ngModel]="draft()[d.key]" (ngModelChange)="set(d.key, $event)">
                  <option value=""></option>
                  <option value="true">Yes</option>
                  <option value="false">No</option>
                </select>
              }
              @default {
                <input [name]="d.key" [attr.data-testid]="'cf-' + d.key" [disabled]="!canWrite"
                       [type]="d.type === 'NUMBER' ? 'number' : d.type === 'DATE' ? 'date' : 'text'"
                       [attr.step]="d.type === 'NUMBER' ? 'any' : null"
                       [ngModel]="draft()[d.key]" (ngModelChange)="set(d.key, $event)" />
              }
            }
          </label>
        }
        @if (canWrite) {
          <button type="submit" [disabled]="busy()" data-testid="save-custom-fields">Save</button>
        }
        @if (message()) { <p class="ok" role="status" data-testid="custom-fields-saved">{{ message() }}</p> }
        @if (saveError()) { <p class="error" role="alert">{{ saveError() }}</p> }
      </form>
    }
  `,
})
export class CustomFieldsForm {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly kind = input.required<CustomFieldKind>();
  readonly recordId = input.required<string>();

  readonly definitions = signal<FieldDefinition[]>([]);
  readonly draft = signal<Draft>({});
  readonly ready = signal(false);
  readonly error = signal('');
  readonly saveError = signal('');
  readonly message = signal('');
  readonly busy = signal(false);

  get canWrite(): boolean {
    return this.session.can('CUSTOM_FIELD_WRITE');
  }

  constructor() {
    // Reload whenever the panel is pointed at another record.
    effect(() => {
      void this.load(this.kind(), this.recordId());
    });
  }

  set(key: string, value: string): void {
    this.draft.update((d) => ({ ...d, [key]: value }));
    this.message.set('');
  }

  async save(): Promise<void> {
    this.busy.set(true);
    this.saveError.set('');
    this.message.set('');
    const values: CustomValues = {};
    for (const d of this.definitions()) {
      const v = valueOf(d, this.draft()[d.key] ?? '');
      if (v !== undefined) {
        values[d.key] = v;
      }
    }
    try {
      const stored = await this.gateway.saveCustomValues(this.kind(), this.recordId(), values);
      this.draft.set(draftOf(this.definitions(), stored));
      this.message.set('Saved');
    } catch (e) {
      this.saveError.set((e as Error).message);
    } finally {
      this.busy.set(false);
    }
  }

  private async load(kind: CustomFieldKind, recordId: string): Promise<void> {
    this.ready.set(false);
    this.error.set('');
    this.message.set('');
    try {
      const [definitions, stored] = await Promise.all([
        this.gateway.listFields(kind), this.gateway.customValues(kind, recordId),
      ]);
      const active = definitions.filter((d) => d.active);
      this.definitions.set(active);
      this.draft.set(draftOf(active, stored));
      this.ready.set(true);
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }
}
