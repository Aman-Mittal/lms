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

import { Component, ElementRef, afterNextRender, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { ApiProblem, LmsGateway } from '../gateway/lms-gateway';
import type { MasterKind } from '../gateway/master-data-types';
import { EntityPicker } from './entity-picker';
import { KIND_WORD, MASTER_FIELDS, blankValues, validate } from './master-data-fields';
import { ErrorBanner } from './error-banner';
import { showToast } from '../../../../shared/feedback';

/** Said when somebody else saved the record first; the raw problem code is never shown. */
export const STALE_MESSAGE = 'Someone else changed this record while you were editing it. It has been reloaded with '
  + 'their changes; check the values and save again.';

/**
 * Creates a partner, vehicle, driver or terminal, or edits one. An edit reads the record fresh, keeps its version
 * and sends it as If-Match; a stale version reloads the record into the form and says so.
 */
@Component({
  selector: 'app-master-data-form',
  imports: [ErrorBanner, EntityPicker],
  template: `
    <form class="record-form" data-testid="record-form" novalidate (submit)="submit($event)" [attr.aria-label]="heading()">
      <header>
        <h2 #title tabindex="-1">{{ heading() }}</h2>
      </header>
      @if (loading()) { <p class="muted">Loading…</p> } @else {
        @if (conflict()) { <p class="notice" role="alert" data-testid="conflict-notice">{{ conflict() }}</p> }
        @for (field of visibleFields(); track field.id) {
          @if (field.type === 'checkbox') {
            <label class="check">
              <input type="checkbox" [attr.data-testid]="field.testId" [name]="field.id"
                     [checked]="values()[field.id] === 'true'"
                     (change)="set(field.id, $any($event.target).checked ? 'true' : 'false')" />
              {{ field.label }}
            </label>
          } @else {
            <label [attr.for]="'md-' + field.id">{{ field.label }}@if (field.required && !locked(field)) { <span aria-hidden="true"> *</span> }
              @switch (field.type) {
                @case ('select') {
                  <select [id]="'md-' + field.id" [attr.data-testid]="field.testId" [disabled]="locked(field)"
                          [value]="values()[field.id]" (change)="set(field.id, $any($event.target).value)">
                    @for (o of field.options; track o) { <option [value]="o" [selected]="o === values()[field.id]">{{ o }}</option> }
                  </select>
                }
                @case ('textarea') {
                  <textarea [id]="'md-' + field.id" rows="4" [attr.data-testid]="field.testId" [value]="values()[field.id]"
                            [attr.aria-invalid]="problems()[field.id] ? true : null"
                            (input)="set(field.id, $any($event.target).value)"></textarea>
                }
                @case ('partner') {
                  <app-entity-picker [kind]="'partner'" [ariaLabel]="field.label" [testId]="field.testId"
                                     [value]="values()[field.id]" (valueChange)="set(field.id, $event)" />
                }
                @default {
                  <input [id]="'md-' + field.id" [type]="field.type === 'number' ? 'text' : field.type"
                         [attr.inputmode]="field.type === 'number' ? 'decimal' : null"
                         [attr.data-testid]="field.testId" [disabled]="locked(field)" [value]="values()[field.id]"
                         [attr.aria-invalid]="problems()[field.id] ? true : null"
                         (input)="set(field.id, $any($event.target).value)" />
                }
              }
            </label>
          }
          @if (field.hint && !problems()[field.id]) { <p class="muted hint">{{ field.hint }}</p> }
          @if (problems()[field.id]) { <p class="error" role="alert" [attr.data-testid]="field.errorId">{{ problems()[field.id] }}</p> }
        }
        @if (error()) { <app-error-banner [message]="error()" testId="form-error" /> }
        <div class="actions">
          <button type="submit" data-testid="save-record" [disabled]="saving()">{{ saving() ? 'Saving…' : 'Save' }}</button>
          <button type="button" class="link" data-testid="cancel-form" (click)="closed.emit()">Cancel</button>
        </div>
      }
    </form>
  `,
})
export class MasterDataForm {
  private readonly gateway = inject(LmsGateway);

  readonly kind = input.required<MasterKind>();
  readonly orgUnitId = input.required<string>();
  /** The record being edited; null for a new one. */
  readonly recordId = input<string | null>(null);
  readonly saved = output<string>();
  readonly closed = output<void>();

  private readonly headingRef = viewChild<ElementRef<HTMLElement>>('title');
  protected readonly values = signal<Record<string, string>>({});
  protected readonly problems = signal<Record<string, string>>({});
  /** True until the form has its values: a form is not offered before it knows what to show. */
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly conflict = signal('');
  private version = 0;
  private attempted = false;

  protected readonly heading = computed(() =>
    `${this.recordId() ? 'Edit' : 'New'} ${KIND_WORD[this.kind()]}`);
  protected readonly visibleFields = computed(() => {
    const shape = this.values()['geofenceType'];
    return MASTER_FIELDS[this.kind()].filter((f) => !f.shape || f.shape === shape);
  });

  constructor() {
    afterNextRender(() => {
      this.start();
      this.headingRef()?.nativeElement.focus();
    });
  }

  protected locked(field: { createOnly?: boolean }): boolean {
    return !!this.recordId() && !!field.createOnly;
  }

  private start(): void {
    const id = this.recordId();
    if (!id) {
      this.values.set(blankValues(this.kind()));
      this.loading.set(false);
      return;
    }
    void this.reload(id);
  }

  private async reload(id: string): Promise<void> {
    this.loading.set(true);
    try {
      const record = await this.gateway.masterRecord(this.kind(), id);
      this.version = record.version;
      this.values.set({ ...blankValues(this.kind()), ...record.values });
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }

  protected set(id: string, value: string): void {
    this.values.update((v) => ({ ...v, [id]: value }));
    if (this.attempted) {
      this.problems.set(validate(this.kind(), this.values(), !this.recordId()));
    }
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    this.attempted = true;
    this.error.set('');
    const found = validate(this.kind(), this.values(), !this.recordId());
    this.problems.set(found);
    if (Object.keys(found).length > 0) {
      return;
    }
    const id = this.recordId();
    this.saving.set(true);
    try {
      const savedId = await this.gateway.saveMasterRecord(this.kind(), this.orgUnitId(), id, this.values(), this.version);
      this.conflict.set('');
      showToast('Saved.');
      this.saved.emit(savedId);
    } catch (e) {
      if (id && e instanceof ApiProblem && e.code === 'concurrent-modification') {
        await this.reload(id);
        this.conflict.set(STALE_MESSAGE);
      } else {
        this.error.set((e as Error).message);
      }
    } finally {
      this.saving.set(false);
    }
  }
}
