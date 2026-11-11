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
import { FormsModule } from '@angular/forms';
import { LmsGateway, ORG_UNIT_RANK, ORG_UNIT_TYPES, ORG_UNIT_WORDS, OrgUnitView } from '../gateway/lms-gateway';
import { Session } from '../session';

/** What the form beneath the tree is doing: adding a child, editing a unit, or nothing. */
type Mode = { kind: 'none' } | { kind: 'child'; parent: OrgUnitView } | { kind: 'edit'; unit: OrgUnitView };

/**
 * Administration > Organisation units: the tree from the caller's own unit
 * down, each unit with its level (head office, line of business, country,
 * zone or region, branch, hub), its codes and the jurisdiction it falls
 * under. A unit is added beneath a parent at a lower level and renamed,
 * retyped or recoded in place; it is never moved. Settings follow the tree:
 * a branch's profile beats its zone's, its zone's its country's.
 */
@Component({
  selector: 'app-org-units',
  imports: [FormsModule],
  template: `
    <h1>Organisation units</h1>
    <p class="muted">Levels run head office, line of business, country, zone (or region), branch, hub; a unit sits
      below its parent's level. A country carries its ISO code and a zone, branch or hub may carry a state code:
      platform rules for that country and state then apply to everything beneath.</p>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }

    <table data-testid="org-units">
      <thead><tr><th>Unit</th><th>Level</th><th>Codes</th><th>Jurisdiction</th><th></th></tr></thead>
      <tbody>
        @for (u of units(); track u.id) {
          <tr data-testid="org-unit-row" [attr.data-id]="u.id" [attr.data-path]="u.path">
            <td [style.padding-left.rem]="indent(u)"><strong>{{ u.name }}</strong> <span class="muted">{{ u.path }}</span></td>
            <td data-testid="org-unit-level">{{ word(u.unitType) }}</td>
            <td>{{ codes(u) }}</td>
            <td data-testid="org-unit-jurisdiction">{{ jurisdiction(u) }}</td>
            <td>
              @if (manage && childTypes(u).length > 0) {
                <button type="button" class="link" data-testid="add-child" (click)="addChild(u)">Add child</button>
              }
              @if (manage) {
                <button type="button" class="link" data-testid="edit-unit" (click)="edit(u)">Edit</button>
              }
            </td>
          </tr>
        } @empty {
          <tr><td colspan="5" class="muted">No units.</td></tr>
        }
      </tbody>
    </table>

    @if (mode().kind !== 'none') {
      <section class="panel in-flow" data-testid="unit-panel">
        <header><h2>{{ heading() }}</h2></header>
        <form class="inline" (ngSubmit)="save()" data-testid="unit-form">
          @if (mode().kind === 'child') {
            <input name="slug" aria-label="Slug" placeholder="slug" data-testid="unit-slug" [(ngModel)]="slug" required />
          }
          <input name="name" aria-label="Unit name" placeholder="Name" data-testid="unit-name" [(ngModel)]="name" required />
          <select name="type" aria-label="Level" data-testid="unit-type" [(ngModel)]="unitType">
            @for (t of allowedTypes(); track t) { <option [value]="t">{{ word(t) }}</option> }
          </select>
          @if (unitType === 'COUNTRY') {
            <input name="country" aria-label="Country code" placeholder="Country (ISO, e.g. IN)" maxlength="2"
                   data-testid="unit-country" [(ngModel)]="countryCode" />
          }
          @if (carriesState()) {
            <input name="state" aria-label="State code" placeholder="State (optional)" maxlength="3"
                   data-testid="unit-state" [(ngModel)]="stateCode" />
          }
          <button type="submit" data-testid="save-unit">{{ mode().kind === 'child' ? 'Add unit' : 'Save' }}</button>
          <button type="button" class="link" (click)="close()">Cancel</button>
        </form>
      </section>
    }
    @if (notice()) { <p class="ok" role="status" data-testid="unit-saved">{{ notice() }}</p> }
  `,
})
export class OrgUnitsPage implements OnInit {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly units = signal<OrgUnitView[]>([]);
  readonly mode = signal<Mode>({ kind: 'none' });
  readonly error = signal('');
  readonly notice = signal('');

  slug = '';
  name = '';
  unitType = '';
  countryCode = '';
  stateCode = '';

  get manage(): boolean {
    return this.session.can('ORG_UNIT_MANAGE');
  }

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  word(type: string): string {
    return ORG_UNIT_WORDS[type] ?? type;
  }

  /** Deeper units sit further in, by their place in the tree. */
  indent(u: OrgUnitView): number {
    return Math.max(0, u.path.split('/').length - 3) * 1.25;
  }

  codes(u: OrgUnitView): string {
    return [u.countryCode, u.stateCode].filter((c) => c).join(' / ');
  }

  jurisdiction(u: OrgUnitView): string {
    return u.effectiveCountry ? (u.effectiveState ? `${u.effectiveCountry}-${u.effectiveState}` : u.effectiveCountry) : '';
  }

  /** The levels a child of `u` may have: those ranking below it. */
  childTypes(u: OrgUnitView): string[] {
    return ORG_UNIT_TYPES.filter((t) => ORG_UNIT_RANK[t] > ORG_UNIT_RANK[u.unitType]);
  }

  allowedTypes(): string[] {
    const m = this.mode();
    if (m.kind === 'child') {
      return this.childTypes(m.parent);
    }
    if (m.kind === 'edit') {
      const parent = this.units().find((p) => p.id === m.unit.parentId);
      const children = this.units().filter((c) => c.parentId === m.unit.id);
      const floor = parent ? ORG_UNIT_RANK[parent.unitType] : -1;
      const ceiling = Math.min(6, ...children.map((c) => ORG_UNIT_RANK[c.unitType]));
      return ORG_UNIT_TYPES.filter((t) => ORG_UNIT_RANK[t] > floor && ORG_UNIT_RANK[t] < ceiling);
    }
    return [];
  }

  carriesState(): boolean {
    return ORG_UNIT_RANK[this.unitType] >= ORG_UNIT_RANK['ZONE'];
  }

  heading(): string {
    const m = this.mode();
    return m.kind === 'child' ? `Add a unit beneath ${m.parent.name}` : m.kind === 'edit' ? `Edit ${m.unit.name}` : '';
  }

  addChild(parent: OrgUnitView): void {
    this.error.set('');
    this.notice.set('');
    this.mode.set({ kind: 'child', parent });
    this.slug = '';
    this.name = '';
    this.unitType = this.childTypes(parent)[0] ?? '';
    this.countryCode = '';
    this.stateCode = '';
  }

  edit(unit: OrgUnitView): void {
    this.error.set('');
    this.notice.set('');
    this.mode.set({ kind: 'edit', unit });
    this.name = unit.name;
    this.unitType = unit.unitType;
    this.countryCode = unit.countryCode;
    this.stateCode = unit.stateCode;
  }

  close(): void {
    this.mode.set({ kind: 'none' });
  }

  async save(): Promise<void> {
    const m = this.mode();
    this.error.set('');
    this.notice.set('');
    const codes = {
      countryCode: this.unitType === 'COUNTRY' ? this.countryCode : '',
      stateCode: this.carriesState() ? this.stateCode : '',
    };
    try {
      if (m.kind === 'child') {
        await this.gateway.createOrgUnit({
          parentId: m.parent.id, slug: this.slug, name: this.name, unitType: this.unitType, ...codes,
        });
        this.notice.set(`${this.name} added.`);
      } else if (m.kind === 'edit') {
        const saved = await this.gateway.updateOrgUnit(m.unit.id, m.unit.version,
          { name: this.name, unitType: this.unitType, ...codes });
        this.notice.set(`${saved.name} saved.`);
      }
      this.mode.set({ kind: 'none' });
      await this.reload();
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  private async reload(): Promise<void> {
    try {
      this.units.set(await this.gateway.orgUnits());
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }
}
