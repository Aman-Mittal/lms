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

import { Component, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs';
import { LmsGateway, PARTNER_TYPES, PartnerType, ResourceKey, Row } from '../gateway/lms-gateway';
import { Session } from '../session';
import { RecordPanel } from './record-panel';
import { RESOURCES } from './resources';

@Component({
  selector: 'app-list',
  imports: [FormsModule, RecordPanel],
  template: `
    @if (resource(); as r) {
      <div [class.with-panel]="selected()">
        <div class="list">
          <h1>{{ r.title }}</h1>
          <input type="search" aria-label="Search" placeholder="Search" data-testid="search"
                 name="search" [ngModel]="query()" (ngModelChange)="query.set($event)" />
          @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
          @if (loading()) { <p>Loading…</p> }
          @else if (visible().length === 0 && !error()) {
            <p class="muted">{{ rows().length === 0 ? 'Nothing here yet.' : 'No match.' }}</p>
          } @else if (visible().length > 0) {
            <table>
              <thead><tr>@for (c of r.columns; track c.field) { <th>{{ c.header }}</th> }</tr></thead>
              <tbody>
                @for (row of visible(); track row['id']) {
                  <tr data-testid="row" tabindex="0" [class.selected]="row['id'] === selectedId()"
                      (click)="selectedId.set(row['id'])" (keydown.enter)="selectedId.set(row['id'])">
                    @for (c of r.columns; track c.field) { <td>{{ row[c.field] }}</td> }
                  </tr>
                }
              </tbody>
            </table>
          }

          @if (r.key === 'partners' && session.orgUnitId()) {
            <h2>Register a partner</h2>
            <form class="inline" (ngSubmit)="register()" data-testid="register-partner">
              <input name="code" placeholder="Code" [(ngModel)]="code" required />
              <input name="legalName" placeholder="Legal name" [(ngModel)]="legalName" required />
              <select name="partnerType" aria-label="Partner type" [(ngModel)]="partnerType">
                @for (t of partnerTypes; track t) { <option [value]="t">{{ t }}</option> }
              </select>
              <button type="submit">Register</button>
            </form>
            @if (formError()) { <p class="error" role="alert">{{ formError() }}</p> }
          }
        </div>
        @if (selected(); as row) {
          <app-record-panel [resource]="r" [row]="row" (closed)="selectedId.set(null)" />
        }
      </div>
    }
  `,
})
export class ListPage {
  protected readonly session = inject(Session);
  private readonly gateway = inject(LmsGateway);

  private readonly key = toSignal(inject(ActivatedRoute).paramMap.pipe(map((p) => p.get('resource'))));
  readonly resource = computed(() => RESOURCES.find((r) => r.key === this.key()));
  readonly rows = signal<Row[]>([]);
  readonly query = signal('');
  readonly selectedId = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');

  /** Derived, never stored: a second copy of the rows could only drift from the first. */
  readonly visible = computed(() => {
    const needle = this.query().trim().toLowerCase();
    return needle
      ? this.rows().filter((row) => Object.values(row).some((cell) => cell.toLowerCase().includes(needle)))
      : this.rows();
  });
  readonly selected = computed(() => this.rows().find((row) => row['id'] === this.selectedId()) ?? null);

  readonly partnerTypes = PARTNER_TYPES;
  code = '';
  legalName = '';
  partnerType: PartnerType = 'CUSTOMER';
  readonly formError = signal('');

  constructor() {
    // Re-fetch whenever the route's :resource changes.
    effect(() => {
      const r = this.resource();
      if (r) {
        this.selectedId.set(null);
        void this.load(r.key);
      }
    });
  }

  async register(): Promise<void> {
    const orgUnitId = this.session.orgUnitId();
    if (!orgUnitId) {
      return;
    }
    this.formError.set('');
    try {
      await this.gateway.registerPartner(orgUnitId,
        { code: this.code, legalName: this.legalName, partnerType: this.partnerType });
      this.code = '';
      this.legalName = '';
      await this.load('partners');
    } catch (e) {
      this.formError.set((e as Error).message);
    }
  }

  private async load(key: ResourceKey): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    this.rows.set([]);
    try {
      this.rows.set(await this.gateway.list(key));
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }
}
