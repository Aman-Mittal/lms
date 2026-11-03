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
import { Component, ElementRef, OnDestroy, effect, inject, input, model, signal, untracked, viewChild } from '@angular/core';
import { Choice, LmsGateway, PickKind } from '../gateway/lms-gateway';

/** A pasted id is taken as it is, without a search. */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DEBOUNCE_MS = 250;

/**
 * A search-as-you-type picker for one kind of record. `value` is the chosen
 * id ('' when nothing is chosen) and binds both ways; what is typed is kept
 * apart from it, so "steel" can be typed while nothing is chosen yet.
 *
 * The text is a signal read through `[value]` and written from `(input)`,
 * not ngModel: ngModel connects after the screen is shown and would wipe an
 * id typed or pasted before it.
 */
@Component({
  selector: 'app-entity-picker',
  template: `
    <span class="picker" [attr.data-testid]="testId() ? testId() + '-picker' : null">
      <input #box [attr.aria-label]="ariaLabel()" [attr.placeholder]="placeholder() || null" [required]="required()"
             [attr.data-testid]="testId() || null" [value]="text()" autocomplete="off" role="combobox"
             [attr.aria-expanded]="open()"
             (input)="typed($any($event.target).value)" (focus)="focused()" (keydown.escape)="open.set(false)"
             (blur)="closeSoon()" />
      @if (open()) {
        <ul class="picker-options" role="listbox" aria-label="Suggestions">
          @if (options().length === 0) { <li class="muted">Nothing matches.</li> }
          @for (o of options(); track o.id) {
            <li><button type="button" role="option" [attr.aria-selected]="o.id === value()"
                        (mousedown)="$event.preventDefault()" (click)="pick(o)">{{ o.label }}</button></li>
          }
          @if (next()) {
            <li><button type="button" class="more" (mousedown)="$event.preventDefault()" (click)="more()">More…</button></li>
          }
        </ul>
      }
      @if (error()) { <span class="error" role="alert">{{ error() }}</span> }
    </span>
  `,
})
export class EntityPicker implements OnDestroy {
  private readonly gateway = inject(LmsGateway);

  readonly kind = input.required<PickKind>();
  readonly value = model('');
  readonly ariaLabel = input.required<string>();
  readonly placeholder = input('');
  readonly required = input(false);
  /** For `bin`: the warehouse whose bins are offered. */
  readonly warehouseId = input<string | undefined>(undefined);
  readonly testId = input('');

  readonly text = signal('');
  readonly options = signal<Choice[]>([]);
  readonly next = signal<string | null>(null);
  readonly open = signal(false);
  readonly error = signal('');

  private readonly box = viewChild.required<ElementRef<HTMLInputElement>>('box');
  private chosen = '';
  private timer: ReturnType<typeof setTimeout> | null = null;
  // Numbers each search, so an answer that arrives after a newer one -- or
  // after an id was pasted -- is dropped rather than reopening a stale list.
  private asked = 0;

  constructor() {
    // A value set from outside (an order read back, a form reset) is shown as it is.
    effect(() => {
      const id = this.value();
      untracked(() => {
        if (id !== this.chosen) {
          this.chosen = id;
          this.text.set(id);
        }
      });
    });
    // Text that names nothing is not an answer.
    effect(() => {
      const shown = this.text();
      this.value();
      this.box().nativeElement.setCustomValidity(
        shown.trim() && !this.chosen ? 'Choose one of the suggestions, or paste an id.' : '');
    });
  }

  ngOnDestroy(): void {
    if (this.timer) { clearTimeout(this.timer); }
  }

  typed(t: string): void {
    this.text.set(t);
    if (this.timer) { clearTimeout(this.timer); }
    const trimmed = t.trim();
    if (UUID.test(trimmed)) {
      this.asked++;
      this.chosen = trimmed;
      this.value.set(trimmed);
      this.open.set(false);
      return;
    }
    if (this.chosen) {
      this.chosen = '';
      this.value.set('');
    }
    this.timer = setTimeout(() => { void this.search(t, null); }, DEBOUNCE_MS);
  }

  focused(): void {
    if (!this.text().trim() && !this.open()) { void this.search('', null); }
  }

  closeSoon(): void {
    setTimeout(() => this.open.set(false), 150);
  }

  more(): void {
    void this.search(this.text(), this.next());
  }

  pick(choice: Choice): void {
    this.chosen = choice.id;
    this.text.set(choice.label);
    this.value.set(choice.id);
    this.open.set(false);
  }

  private async search(q: string, cursor: string | null): Promise<void> {
    const ticket = ++this.asked;
    try {
      const page = await this.gateway.pick({ kind: this.kind(), q, cursor, warehouseId: this.warehouseId() });
      if (ticket !== this.asked || (this.chosen && !cursor)) { return; }
      this.options.update((before) => (cursor ? [...before, ...page.items] : page.items));
      this.next.set(page.nextCursor);
      this.error.set('');
      this.open.set(true);
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }
}
