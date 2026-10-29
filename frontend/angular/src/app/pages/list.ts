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
import { LmsGateway, PARTNER_TYPES, PartnerType, Row } from '../gateway/lms-gateway';
import { Session } from '../session';
import { RESOURCES } from './resources';

@Component({
  selector: 'app-list',
  imports: [FormsModule],
  template: `
    @if (resource(); as r) {
      <h1>{{ r.title }}</h1>
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      @if (loading()) { <p>Loading…</p> }
      @else if (rows().length === 0 && !error()) {
        <p class="muted">Nothing here yet.</p>
      } @else if (rows().length > 0) {
        <table>
          <thead><tr>@for (c of r.columns; track c.field) { <th>{{ c.header }}</th> }</tr></thead>
          <tbody>
            @for (row of rows(); track $index) {
              <tr>@for (c of r.columns; track c.field) { <td>{{ row[c.field] }}</td> }</tr>
            }
          </tbody>
        </table>
      }

      @if (r.key === 'partners') {
        <h2>Register a partner</h2>
        <form class="inline" (ngSubmit)="register()">
          <input name="code" placeholder="Code" [(ngModel)]="code" required />
          <input name="legalName" placeholder="Legal name" [(ngModel)]="legalName" required />
          <select name="partnerType" [(ngModel)]="partnerType">
            @for (t of partnerTypes; track t) { <option [value]="t">{{ t }}</option> }
          </select>
          <button type="submit">Register</button>
        </form>
        @if (formError()) { <p class="error" role="alert">{{ formError() }}</p> }
      }
    }
  `,
})
export class ListPage {
  private readonly session = inject(Session);
  private readonly gateway = inject(LmsGateway);

  private readonly key = toSignal(inject(ActivatedRoute).paramMap.pipe(map((p) => p.get('resource'))));
  readonly resource = computed(() => RESOURCES.find((r) => r.key === this.key()));
  readonly rows = signal<Row[]>([]);
  readonly loading = signal(false);
  readonly error = signal('');

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

  private async load(key: (typeof RESOURCES)[number]['key']): Promise<void> {
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
