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
import {
  EffectiveSettingView, LmsGateway, PickKind, SETTING_SCOPES, SettingChange, SettingDefinitionView,
  SettingHistoryRow, SettingProfileView, SettingScope,
} from '../gateway/lms-gateway';
import { Session } from '../session';
import { choicesOf, controlOf, fromInput, todayIso, toInput } from '../settings-form';
import { EntityPicker } from './entity-picker';

const SCOPE_LABEL: Record<string, string> = {
  ORG_DEFAULT: 'Organisation default', ORG_UNIT: 'Org unit', CUSTOMER: 'Customer', VENDOR: 'Vendor',
  VEHICLE: 'Vehicle',
};
const PICKER: Record<string, PickKind> = {
  ORG_UNIT: 'org-unit', CUSTOMER: 'customer', VENDOR: 'vendor', VEHICLE: 'vehicle',
};

/**
 * Administration > Profile settings: the company's setting profiles, one per
 * scope (the organisation, a unit, a customer, a vendor, a vehicle), their
 * values with an input for each setting's type, the history of each, and what
 * is in force for a context and where it comes from. A dated setting changes
 * only by a new version from today or later; versions already in force are
 * shown, not edited.
 */
@Component({
  selector: 'app-profile-settings',
  imports: [FormsModule, EntityPicker],
  template: `
    <h1>Profile settings</h1>
    <p class="muted">The most specific profile that sets a value wins: organisation, then unit (deeper first),
      then vehicle, customer or vendor. A person's own preferences sit on top of those, under My profile.</p>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }

    <label>
      Scope
      <select name="filter" data-testid="profile-filter" [ngModel]="filter()" (ngModelChange)="filterBy($event)">
        <option value="">All</option>
        @for (s of scopes; track s) { <option [value]="s">{{ scopeLabel(s) }}</option> }
      </select>
    </label>
    <table data-testid="profiles">
      <thead><tr><th>Name</th><th>Scope</th><th>For</th><th>Settings</th></tr></thead>
      <tbody>
        @for (p of profiles(); track p.id) {
          <tr data-testid="profile-row" [attr.data-id]="p.id" [class.selected]="picked()?.id === p.id">
            <td><button type="button" class="link" (click)="open(p.id)">{{ p.name }}</button></td>
            <td>{{ scopeLabel(p.scopeType) }}</td>
            <td>{{ p.scopeLabel }}</td>
            <td>{{ keysOf(p) }}</td>
          </tr>
        } @empty {
          <tr><td colspan="4" class="muted">No profiles yet.</td></tr>
        }
      </tbody>
    </table>

    @if (manage) {
      <h2>Add a profile</h2>
      <form class="inline" (ngSubmit)="create()" data-testid="add-profile">
        <select name="scope" aria-label="Scope" data-testid="new-scope" [(ngModel)]="newScope">
          @for (s of scopes; track s) { <option [value]="s">{{ scopeLabel(s) }}</option> }
        </select>
        @if (newScope !== 'ORG_DEFAULT') {
          <app-entity-picker [kind]="pickerOf(newScope)" ariaLabel="Profile scope" testId="new-scope-id"
                             placeholder="Which one" [(value)]="newScopeId" />
        }
        <input name="name" placeholder="Name" aria-label="Profile name" data-testid="new-name" [(ngModel)]="newName" />
        <button type="submit" data-testid="create-profile">Add profile</button>
      </form>
    }

    @if (picked(); as p) {
      <section class="panel in-flow" data-testid="profile-panel">
        <header><h2>{{ p.name }}</h2><span class="muted">{{ scopeLabel(p.scopeType) }} {{ p.scopeLabel }}</span></header>
        <form (ngSubmit)="saveProfile()" data-testid="profile-values">
          <table>
            <thead><tr><th>Setting</th><th>Value</th>@if (hasDated()) { <th>From</th> }<th></th></tr></thead>
            <tbody>
              @for (d of allowed(); track d.key) {
                <tr [attr.data-testid]="'setting-' + d.key">
                  <td title="{{ d.description }}">{{ d.label }}<br /><span class="muted">{{ d.key }}</span></td>
                  <td>
                    @if (control(d) === 'select') {
                      <select [name]="'v-' + d.key" [attr.aria-label]="d.label" [attr.data-testid]="'value-' + d.key"
                              [(ngModel)]="typed[d.key]" [disabled]="!manage">
                        <option value="">(not set here)</option>
                        @for (c of choices(d); track c) { <option [value]="c">{{ c }}</option> }
                      </select>
                    } @else {
                      <input [name]="'v-' + d.key" [attr.aria-label]="d.label" [attr.data-testid]="'value-' + d.key"
                             [type]="control(d)" [(ngModel)]="typed[d.key]" [disabled]="!manage"
                             placeholder="(not set here)" />
                    }
                  </td>
                  @if (hasDated()) {
                    <td>
                      @if (d.effectiveDated) {
                        <input type="date" [name]="'f-' + d.key" [attr.aria-label]="d.label + ' from'"
                               [attr.data-testid]="'from-' + d.key" [(ngModel)]="from[d.key]" [disabled]="!manage" />
                      }
                    </td>
                  }
                  <td>
                    @if (manage && !d.effectiveDated && p.values[d.key] !== undefined) {
                      <button type="button" class="link" [attr.data-testid]="'clear-' + d.key" (click)="clear(d.key)">Clear</button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
          @if (manage) {
            <p>
              <button type="submit" data-testid="save-profile">Save</button>
              @if (p.scopeType !== 'ORG_DEFAULT') {
                <button type="button" class="link" data-testid="delete-profile" (click)="remove()">Delete profile</button>
              }
            </p>
          }
          @if (notice()) { <p class="ok" role="status" data-testid="profile-saved">{{ notice() }}</p> }
        </form>
        @if (p.versions.length > 0) {
          <h3>Versions</h3>
          <table data-testid="versions">
            <thead><tr><th>Setting</th><th>From</th><th>Value</th><th>Status</th></tr></thead>
            <tbody>
              @for (v of p.versions; track v.key + v.effectiveFrom) {
                <tr><td>{{ v.key }}</td><td>{{ v.effectiveFrom || 'always' }}</td><td>{{ shown(v.value) }}</td><td>{{ v.status }}</td></tr>
              }
            </tbody>
          </table>
        }
        <h3>History</h3>
        <table data-testid="history">
          <thead><tr><th>When</th><th>Who</th><th>What</th><th>Setting</th><th>Before</th><th>After</th></tr></thead>
          <tbody>
            @for (h of history(); track h.id) {
              <tr data-testid="history-row">
                <td>{{ h.occurredAt }}</td><td>{{ h.actor }}</td><td>{{ h.action }}</td>
                <td>{{ h.setting }}</td><td>{{ h.before }}</td><td>{{ h.after }}</td>
              </tr>
            }
          </tbody>
        </table>
      </section>
    }

    <h2>In force for</h2>
    <form class="inline" (ngSubmit)="resolve()" data-testid="effective-form">
      <app-entity-picker [kind]="'org-unit'" ariaLabel="Context unit" testId="ctx-unit" placeholder="Unit" [(value)]="ctxUnit" />
      <app-entity-picker [kind]="'customer'" ariaLabel="Context customer" testId="ctx-customer" placeholder="Customer"
                         [(value)]="ctxCustomer" />
      <app-entity-picker [kind]="'vehicle'" ariaLabel="Context vehicle" testId="ctx-vehicle" placeholder="Vehicle"
                         [(value)]="ctxVehicle" />
      <app-entity-picker [kind]="'vendor'" ariaLabel="Context vendor" testId="ctx-vendor" placeholder="Vendor"
                         [(value)]="ctxVendor" />
      <input type="date" name="asOf" aria-label="As of" data-testid="ctx-as-of" [(ngModel)]="ctxAsOf" />
      <button type="submit" data-testid="show-effective">Show</button>
    </form>
    @if (effective().length > 0) {
      <table data-testid="effective">
        <thead><tr><th>Setting</th><th>Value</th><th>From</th></tr></thead>
        <tbody>
          @for (e of effective(); track e.key) {
            <tr [attr.data-testid]="'effective-' + e.key">
              <td>{{ e.label }}</td><td>{{ shown(e.value) }}</td><td>{{ e.source }}</td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class ProfileSettingsPage implements OnInit {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly scopes = SETTING_SCOPES;
  readonly filter = signal<string>('');
  readonly profiles = signal<SettingProfileView[]>([]);
  readonly picked = signal<SettingProfileView | null>(null);
  readonly allowed = signal<SettingDefinitionView[]>([]);
  readonly history = signal<SettingHistoryRow[]>([]);
  readonly effective = signal<EffectiveSettingView[]>([]);
  readonly error = signal('');
  readonly notice = signal('');
  private definitions: SettingDefinitionView[] = [];

  newScope: SettingScope = 'ORG_DEFAULT';
  newScopeId = '';
  newName = '';
  typed: Record<string, string> = {};
  from: Record<string, string> = {};
  private loaded: Record<string, string> = {};
  ctxUnit = '';
  ctxCustomer = '';
  ctxVehicle = '';
  ctxVendor = '';
  ctxAsOf = '';

  get manage(): boolean {
    return this.session.can('SETTINGS_MANAGE');
  }

  async ngOnInit(): Promise<void> {
    try {
      this.definitions = await this.gateway.settingDefinitions();
      await this.reload();
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  scopeLabel(s: string): string {
    return SCOPE_LABEL[s] ?? s;
  }

  pickerOf(s: string): PickKind {
    return PICKER[s] ?? 'customer';
  }

  keysOf(p: SettingProfileView): string {
    return Object.keys(p.values).join(', ');
  }

  control(d: SettingDefinitionView): 'select' | 'number' | 'text' {
    return controlOf(d);
  }

  choices(d: SettingDefinitionView): string[] {
    return choicesOf(d);
  }

  hasDated(): boolean {
    return this.allowed().some((d) => d.effectiveDated);
  }

  shown(v: unknown): string {
    return toInput(v);
  }

  async filterBy(scope: string): Promise<void> {
    this.filter.set(scope);
    await this.reload();
  }

  async create(): Promise<void> {
    this.error.set('');
    try {
      const id = await this.gateway.createSettingProfile({
        scopeType: this.newScope, scopeId: this.newScope === 'ORG_DEFAULT' ? '' : this.newScopeId, name: this.newName,
      });
      this.newName = '';
      this.newScopeId = '';
      await this.reload();
      await this.open(id);
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  async open(id: string): Promise<void> {
    this.error.set('');
    this.notice.set('');
    try {
      this.show(await this.gateway.settingProfile(id));
      this.history.set(await this.gateway.settingProfileHistory(id));
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  /** Sends each value that changed: a dated setting as a new version from its date. */
  async saveProfile(): Promise<void> {
    const p = this.picked();
    if (!p) {
      return;
    }
    this.error.set('');
    this.notice.set('');
    const changes: SettingChange[] = [];
    try {
      for (const d of this.allowed()) {
        const text = this.typed[d.key] ?? '';
        if (text === (this.loaded[d.key] ?? '') || text === '') {
          continue;
        }
        changes.push({ key: d.key, effectiveFrom: d.effectiveDated ? (this.from[d.key] || todayIso()) : '',
          value: fromInput(d, text) });
      }
      await this.apply(p, changes);
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  async clear(key: string): Promise<void> {
    const p = this.picked();
    if (!p) {
      return;
    }
    this.error.set('');
    try {
      await this.apply(p, [{ key, effectiveFrom: '', value: null }]);
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  async remove(): Promise<void> {
    const p = this.picked();
    if (!p) {
      return;
    }
    this.error.set('');
    try {
      await this.gateway.deleteSettingProfile(p.id, p.version);
      this.picked.set(null);
      await this.reload();
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  async resolve(): Promise<void> {
    this.error.set('');
    try {
      this.effective.set(await this.gateway.effectiveSettings({
        orgUnitId: this.ctxUnit, customerId: this.ctxCustomer, vehicleId: this.ctxVehicle, vendorId: this.ctxVendor,
        asOf: this.ctxAsOf,
      }));
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  private async apply(p: SettingProfileView, changes: SettingChange[]): Promise<void> {
    const saved = await this.gateway.updateSettingProfile(p.id, p.version, changes);
    this.show(saved);
    this.history.set(await this.gateway.settingProfileHistory(p.id));
    this.notice.set('Saved.');
    await this.reload();
  }

  private show(p: SettingProfileView): void {
    this.picked.set(p);
    this.allowed.set(this.definitions.filter((d) => d.scopes.includes(p.scopeType)));
    this.typed = {};
    this.from = {};
    for (const d of this.allowed()) {
      this.typed[d.key] = toInput(p.values[d.key]);
      this.from[d.key] = todayIso();
    }
    this.loaded = { ...this.typed };
  }

  private async reload(): Promise<void> {
    this.profiles.set(await this.gateway.settingProfiles((this.filter() || undefined) as SettingScope | undefined));
  }
}
