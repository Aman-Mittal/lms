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

import { Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  CUSTOM_FIELD_KINDS, CustomFieldKind, FIELD_TYPES, FieldDefinition, FieldType, LmsGateway,
} from '../gateway/lms-gateway';
import { Session } from '../session';
import { ConfigActions } from './config-actions';
import { PageHeader } from './page-header';
import { StatusFilter } from './status-filter';
import { ErrorBanner } from './error-banner';

const KIND_LABEL: Record<CustomFieldKind, string> = {
  PARTNER: 'Partners', VEHICLE: 'Vehicles', DRIVER: 'Drivers', TERMINAL: 'Terminals',
  ORDER: 'Orders', LOAD: 'Loads', TRIP: 'Trips',
};

/** Settings: which custom fields exist on each kind of record. */
@Component({
  selector: 'app-fields-settings',
  imports: [ErrorBanner, FormsModule, ConfigActions, PageHeader, StatusFilter],
  template: `
    <app-page-header title="Custom fields" />
    <p class="muted">Fields your company tracks on its own records. Deactivating a field keeps what was recorded under it.</p>
    <label>
      Record kind
      <select name="kind" data-testid="kind" [ngModel]="kind()" (ngModelChange)="pick($event)">
        @for (k of kinds; track k) { <option [value]="k">{{ label(k) }}</option> }
      </select>
    </label>
    <app-status-filter [value]="status()" (valueChange)="status.set($event)" />
    @if (error()) { <app-error-banner [message]="error()" /> }
    @if (loading()) { <p>Loading…</p> }
    @else if (definitions().length === 0) {
      <p class="muted">No custom fields yet for {{ label(kind()).toLowerCase() }}.</p>
    } @else {
      <div class="table-wrap"><table data-testid="definitions">
        <thead>
          <tr><th>Key</th><th>Label</th><th>Type</th><th>Required</th><th>Options</th><th>Status</th><th></th></tr>
        </thead>
        <tbody>
          @for (d of definitions(); track d.id) {
            <tr [attr.data-testid]="'def-' + d.key">
              <td>{{ d.key }}</td>
              <td>{{ d.label }}</td>
              <td>{{ d.type }}</td>
              <td>{{ d.required ? 'Yes' : 'No' }}</td>
              <td>{{ d.options.join(', ') }}</td>
              <td>{{ d.active ? 'Active' : 'Inactive' }}</td>
              <td>
                @if (manage) {
                  <app-config-actions kind="customFields" [recordId]="d.id" [active]="d.active" [label]="d.label"
                                      [version]="d.version" (changed)="reload.update(n => n + 1)" />
                }
              </td>
            </tr>
          }
        </tbody>
      </table></div>
    }

    @if (manage) {
      <h2>Add a field</h2>
      <form ngNativeValidate class="inline" (ngSubmit)="add()" data-testid="add-field">
        <input name="key" placeholder="Key" aria-label="Key" [(ngModel)]="key" required />
        <input name="label" placeholder="Label" aria-label="Label" [(ngModel)]="fieldLabel" required />
        <select name="type" aria-label="Type" [(ngModel)]="type">
          @for (t of types; track t) { <option [value]="t">{{ t }}</option> }
        </select>
        @if (type === 'SELECT') {
          <input name="options" placeholder="Options, comma separated" aria-label="Options" [(ngModel)]="options" />
        }
        <label class="check">
          <input type="checkbox" name="required" [(ngModel)]="required" />
          Required
        </label>
        <button type="submit">Add field</button>
      </form>
      @if (addError()) { <app-error-banner [message]="addError()" /> }
    }
  `,
})
export class FieldsSettings {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly kinds = CUSTOM_FIELD_KINDS;
  readonly types = FIELD_TYPES;
  readonly kind = signal<CustomFieldKind>('PARTNER');
  readonly definitions = signal<FieldDefinition[]>([]);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly addError = signal('');
  /** '' is the server's default, the active fields. */
  readonly status = signal('');
  protected readonly reload = signal(0);

  key = '';
  fieldLabel = '';
  type: FieldType = 'TEXT';
  options = '';
  required = false;

  get manage(): boolean {
    return this.session.can('CUSTOM_FIELD_MANAGE');
  }

  constructor() {
    effect(() => {
      void this.load(this.kind(), this.reload(), this.status());
    });
  }

  label(k: CustomFieldKind): string {
    return KIND_LABEL[k];
  }

  pick(kind: CustomFieldKind): void {
    this.kind.set(kind);
  }

  async add(): Promise<void> {
    this.addError.set('');
    try {
      await this.gateway.defineField({
        kind: this.kind(), key: this.key, label: this.fieldLabel, type: this.type,
        required: this.required,
        options: this.type === 'SELECT'
          ? this.options.split(',').map((o) => o.trim()).filter((o) => o !== '')
          : [],
      });
      this.key = '';
      this.fieldLabel = '';
      this.options = '';
      this.required = false;
      this.reload.update((n) => n + 1);
    } catch (e) {
      this.addError.set((e as Error).message);
    }
  }

  private async load(kind: CustomFieldKind, _reload: number, status: string): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.definitions.set(await this.gateway.listFields(kind, status));
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }
}
