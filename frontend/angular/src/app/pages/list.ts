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

import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs';
import { ApiProblem, LmsGateway, PARTNER_TYPES, PartnerType, ResourceKey, Row } from '../gateway/lms-gateway';
import { Session } from '../session';
import { RecordPanel } from './record-panel';
import { CREATE_RIGHTS, RESOURCES, STATUS_FILTERS } from './resources';
import { MasterDataForm } from './master-data-form';
import { PageHeader } from './page-header';
import { Cell } from './cell';
import { isDateColumn, isNumericColumn } from '../../../../shared/format';
import { ErrorBanner } from './error-banner';
import { showToast } from '../../../../shared/feedback';

@Component({
  selector: 'app-list',
  imports: [ErrorBanner, FormsModule, RecordPanel, PageHeader, Cell, MasterDataForm],
  template: `
    @if (resource(); as r) {
      <div [class.with-panel]="selected() || creating()">
        <div class="list">
          <app-page-header [title]="r.title">
            @if (createRight(); as right) {
              <button type="button" data-testid="new-record" (click)="startCreate()">New {{ right.word }}</button>
            }
          </app-page-header>
          <input type="search" aria-label="Search" placeholder="Search" data-testid="search"
                 name="search" [ngModel]="query()" (ngModelChange)="query.set($event)" />
          @if (statuses(); as statuses) {
            <label class="filter">Status
              <select data-testid="status-filter" name="status" [ngModel]="status()" (ngModelChange)="setStatus($event)">
                <option value="">Active</option>
                <option value="ALL">All</option>
                @for (s of statuses; track s) { <option [value]="s">{{ s }}</option> }
              </select>
            </label>
          }
          @if (error()) { <app-error-banner [message]="error()" /> }
          <div class="table-wrap">
            <table>
              <thead><tr>@for (c of r.columns; track c.field) { <th [class.num]="numeric(c.field)">{{ c.header }}</th> }</tr></thead>
              <tbody>
                @if (loading()) {
                  <tr class="loading-row"><td class="table-state" [attr.colspan]="r.columns.length">Loading…</td></tr>
                } @else if (visible().length === 0 && !error()) {
                  <tr class="empty-row">
                    <td class="table-state" [attr.colspan]="r.columns.length">{{ rows().length === 0 ? 'Nothing here yet.' : 'No match.' }}</td>
                  </tr>
                } @else {
                  @for (row of visible(); track row['id']) {
                    <tr data-testid="row" [attr.data-id]="row['id']" tabindex="0" [class.selected]="row['id'] === selectedId()"
                        (click)="selectedId.set(row['id'])" (keydown.enter)="selectedId.set(row['id'])">
                      @for (c of r.columns; track c.field) {
                        <td [class.num]="numeric(c.field)" [class.nowrap]="date(c.field)"><app-cell [field]="c.field" [value]="row[c.field]" /></td>
                      }
                    </tr>
                  }
                }
              </tbody>
            </table>
          </div>

          @if (r.key === 'partners' && session.orgUnitId()) {
            <h2>Register a partner</h2>
            <form ngNativeValidate class="inline" (ngSubmit)="register()" data-testid="register-partner">
              <input name="code" placeholder="Code" [(ngModel)]="code" required />
              <input name="legalName" placeholder="Legal name" [(ngModel)]="legalName" required />
              <select name="partnerType" aria-label="Partner type" [(ngModel)]="partnerType">
                @for (t of partnerTypes; track t) { <option [value]="t">{{ t }}</option> }
              </select>
              <button type="submit">Register</button>
            </form>
            @if (formError()) { <app-error-banner [message]="formError()" [code]="formCode()" testId="register-error" /> }
          }
        </div>
        @if (creating() && session.orgUnitId(); as unit) {
          <aside class="panel" aria-label="New record">
            @defer (on immediate) {
              <app-master-data-form [kind]="$any(r.key)" [orgUnitId]="unit" (saved)="created($event)" (closed)="creating.set(false)" />
            }
          </aside>
        } @else if (selected(); as row) {
          <app-record-panel [resource]="r" [row]="row" (closed)="selectedId.set(null)" (changed)="reload()" />
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
  readonly status = signal('');
  readonly creating = signal(false);
  readonly statuses = computed(() => STATUS_FILTERS[this.resource()?.key as keyof typeof STATUS_FILTERS] ?? null);
  /** The button that opens a blank form, for those who may create and a register that can be created. */
  readonly createRight = computed(() => {
    const right = CREATE_RIGHTS[this.resource()?.key as keyof typeof CREATE_RIGHTS];
    return right && this.session.can(right.permission) && this.session.orgUnitId() ? right : null;
  });
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

  protected readonly numeric = isNumericColumn;
  protected readonly date = isDateColumn;
  readonly partnerTypes = PARTNER_TYPES;
  code = '';
  legalName = '';
  partnerType: PartnerType = 'CUSTOMER';
  readonly formError = signal('');
  readonly formCode = signal('');

  constructor() {
    // Re-fetch whenever the route's :resource changes.
    effect(() => {
      const r = this.resource();
      if (r) {
        // Only the route's resource re-runs this; the status the load reads is the filter's own to change.
        untracked(() => {
          this.selectedId.set(null);
          this.creating.set(false);
          this.status.set('');
          void this.load(r.key);
        });
      }
    });
  }

  setStatus(value: string): void {
    this.status.set(value);
    this.selectedId.set(null);
    void this.load(this.resource()!.key);
  }

  startCreate(): void {
    this.selectedId.set(null);
    this.creating.set(true);
  }

  /** A record was created: the list is read again and the new record opened. */
  async created(id: string): Promise<void> {
    this.creating.set(false);
    await this.load(this.resource()!.key);
    this.selectedId.set(id);
  }

  reload(): void {
    void this.load(this.resource()!.key, false);
  }

  async register(): Promise<void> {
    const orgUnitId = this.session.orgUnitId();
    if (!orgUnitId) {
      return;
    }
    this.formError.set('');
    this.formCode.set('');
    try {
      await this.gateway.registerPartner(orgUnitId,
        { code: this.code, legalName: this.legalName, partnerType: this.partnerType });
      showToast(`Partner ${this.code} registered.`);
      this.code = '';
      this.legalName = '';
      await this.load('partners');
    } catch (e) {
      this.formError.set((e as Error).message);
      this.formCode.set(e instanceof ApiProblem ? e.code : '');
    }
  }

  private async load(key: ResourceKey, clear = true): Promise<void> {
    this.loading.set(clear);
    this.error.set('');
    if (clear) {
      this.rows.set([]);
    }
    try {
      this.rows.set(await this.gateway.list(key, { status: this.status() }));
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }
}
