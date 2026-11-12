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

import { Component, OnInit, inject, signal } from '@angular/core';
import {
  ApiProblem, CHARGE_BASES, CHARGE_DIRECTIONS, ChargeEntryView, LmsGateway,
} from '../gateway/lms-gateway';

/** What an `(input)` or `(change)` event carries, read without a cast in the template. */
function valueOf(event: Event): string {
  return (event.target as HTMLInputElement).value;
}

/**
 * The tenant's catalogue of manual charges: loading, unloading, toll, halting, a penalty. An entry
 * is created, edited under If-Match (its code never changes) and switched off, never deleted.
 *
 * Inputs bind through signals rather than `ngModel`, which without zone.js can write its first
 * value late and wipe what was typed.
 */
@Component({
  selector: 'app-charge-catalogue',
  template: `
    <h1>Charge catalogue</h1>
    <p class="muted">The charges raised by hand against a trip, consignment or order, and their default rates.</p>
    @if (refusal(); as r) {
      <p class="error" role="alert" data-testid="cc-error">
        @if (r.code) { <code>{{ r.code }}</code> }
        {{ r.message }}
      </p>
    }
    @if (saved()) { <p class="ok" role="status" data-testid="cc-saved">{{ saved() }}</p> }

    <form data-testid="cc-form" (submit)="save($event)">
      <h2>{{ editing() ? 'Edit ' + code() : 'New charge' }}</h2>
      <input placeholder="Code" aria-label="Code" data-testid="cc-code" required [disabled]="!!editing()"
             [value]="code()" (input)="code.set(valueOf($event))" />
      <input placeholder="Name" aria-label="Name" data-testid="cc-name" required
             [value]="name()" (input)="name.set(valueOf($event))" />
      <select aria-label="Basis" data-testid="cc-basis" (change)="basis.set(valueOf($event))">
        @for (b of bases; track b) { <option [value]="b" [selected]="b === basis()">{{ b }}</option> }
      </select>
      <input type="number" min="0" step="any" placeholder="Default rate" aria-label="Default rate" data-testid="cc-rate"
             required [value]="rate()" (input)="rate.set(valueOf($event))" />
      <select aria-label="Direction" data-testid="cc-direction" (change)="direction.set(valueOf($event))">
        @for (d of directions; track d) { <option [value]="d" [selected]="d === direction()">{{ d }}</option> }
      </select>
      <label><input type="checkbox" data-testid="cc-active" [checked]="active()"
                    (change)="active.set($any($event.target).checked)" /> Active</label>
      <button type="submit" data-testid="cc-save">{{ editing() ? 'Save changes' : 'Add charge' }}</button>
      <button type="button" class="link" data-testid="cc-reset" (click)="reset()">Reset</button>
    </form>

    <form class="inline" role="search" (submit)="search($event)">
      <input type="search" placeholder="Search code or name" aria-label="Search charges" data-testid="cc-search"
             [value]="q()" (input)="q.set(valueOf($event))" />
      <button type="submit">Search</button>
    </form>
    @if (entries().length === 0) {
      <p class="muted" data-testid="cc-empty">No charges.</p>
    } @else {
      <table data-testid="cc-list">
        <thead><tr><th>Code</th><th>Name</th><th>Basis</th><th>Default rate</th><th>Direction</th><th>Active</th><th></th></tr></thead>
        <tbody>
          @for (e of entries(); track e.id) {
            <tr data-testid="cc-row" [attr.data-code]="e.code">
              <td>{{ e.code }}</td><td>{{ e.name }}</td><td>{{ e.basis }}</td><td>{{ e.defaultRate }}</td>
              <td>{{ e.direction }}</td><td>{{ e.active ? 'Yes' : 'No' }}</td>
              <td><button type="button" class="link" data-testid="cc-edit" [attr.aria-label]="'Edit ' + e.code"
                          (click)="edit(e.id)">Edit</button></td>
            </tr>
          }
        </tbody>
      </table>
      @if (next()) { <button type="button" class="link" (click)="more()">More</button> }
    }
  `,
})
export class ChargeCatalogue implements OnInit {
  private readonly gateway = inject(LmsGateway);
  protected readonly valueOf = valueOf;
  readonly bases = CHARGE_BASES;
  readonly directions = CHARGE_DIRECTIONS;

  readonly entries = signal<ChargeEntryView[]>([]);
  readonly next = signal<string | null>(null);
  readonly q = signal('');
  readonly refusal = signal<{ code: string; message: string } | null>(null);
  readonly saved = signal('');

  /** The entry being edited and the version read with it, which the save sends as If-Match. */
  readonly editing = signal<{ id: string; version: number } | null>(null);
  readonly code = signal('');
  readonly name = signal('');
  readonly basis = signal<string>('FLAT');
  readonly rate = signal('');
  readonly direction = signal<string>('RECEIVABLE');
  readonly active = signal(true);

  async ngOnInit(): Promise<void> {
    await this.attempt(() => this.load(false));
  }

  async search(event: Event): Promise<void> {
    event.preventDefault();
    await this.attempt(() => this.load(false));
  }

  async more(): Promise<void> {
    await this.attempt(() => this.load(true));
  }

  async edit(id: string): Promise<void> {
    await this.attempt(async () => {
      const e = await this.gateway.chargeEntry(id);
      this.editing.set({ id: e.id, version: e.version });
      this.code.set(e.code);
      this.name.set(e.name);
      this.basis.set(e.basis);
      this.rate.set(String(e.defaultRate));
      this.direction.set(e.direction);
      this.active.set(e.active);
    });
  }

  reset(): void {
    this.editing.set(null);
    this.code.set('');
    this.name.set('');
    this.basis.set('FLAT');
    this.rate.set('');
    this.direction.set('RECEIVABLE');
    this.active.set(true);
  }

  async save(event: Event): Promise<void> {
    event.preventDefault();
    await this.attempt(async () => {
      const draft = {
        code: this.code(), name: this.name(), basis: this.basis(), defaultRate: Number(this.rate()),
        direction: this.direction(), active: this.active(),
      };
      const target = this.editing();
      if (target) {
        await this.gateway.updateChargeEntry(target.id, target.version, draft);
      } else {
        await this.gateway.createChargeEntry(draft);
      }
      this.saved.set(target ? `Saved ${draft.code}.` : `Added ${draft.code}.`);
      this.reset();
      await this.load(false);
    });
  }

  private async load(append: boolean): Promise<void> {
    const page = await this.gateway.chargeCatalogue({ q: this.q(), activeOnly: false },
      append ? this.next() ?? undefined : undefined);
    this.entries.set(append ? [...this.entries(), ...page.items] : page.items);
    this.next.set(page.nextCursor);
  }

  private async attempt(action: () => Promise<void>): Promise<void> {
    this.refusal.set(null);
    this.saved.set('');
    try {
      await action();
    } catch (e) {
      this.refusal.set({ code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message });
    }
  }
}
