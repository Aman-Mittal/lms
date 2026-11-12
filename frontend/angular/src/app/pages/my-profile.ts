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
import { applyDisplay } from '../display';
import {
  EffectiveSettingView, LmsGateway, MeView, SettingDefinitionView, SettingValue,
} from '../gateway/lms-gateway';
import { choicesOf, controlOf, fromInput, toInput } from '../settings-form';

/** One notification event and its two channels. */
interface NotifyRow {
  event: string;
  label: string;
  inApp: string;
  email: string;
}

/**
 * My profile & preferences: who the signed-in person is, as the platform
 * records them, and the preferences they keep for themself. Saving applies
 * the display ones at once (theme, date format and time zone, rows per page).
 */
@Component({
  selector: 'app-my-profile',
  imports: [FormsModule],
  template: `
    <h1>My profile &amp; preferences</h1>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (me(); as m) {
      <section class="panel" data-testid="me-identity">
        <dl>
          <div><dt>Name</dt><dd data-testid="me-name">{{ m.fullName }}</dd></div>
          <div><dt>Email</dt><dd data-testid="me-email">{{ m.email }}</dd></div>
          <div><dt>Company</dt><dd data-testid="me-tenant">{{ m.tenantName }} ({{ m.tenantCode }})</dd></div>
          <div><dt>Unit</dt><dd data-testid="me-unit">{{ m.orgUnitName }} · {{ m.orgPath }}</dd></div>
          <div><dt>Roles</dt><dd data-testid="me-roles">{{ m.roles.join(', ') || 'None' }}</dd></div>
          <div><dt>Permissions</dt><dd data-testid="me-permissions">{{ m.permissions.length }}</dd></div>
        </dl>
      </section>
    }

    <h2>Display</h2>
    <form (ngSubmit)="save()" data-testid="preferences">
      @for (p of display(); track p.key) {
        <label>
          {{ p.label }}
          @if (control(p.key) === 'select') {
            <select [name]="p.key" [attr.data-testid]="'pref-' + p.key" [(ngModel)]="typed[p.key]">
              @for (c of choices(p.key); track c) { <option [value]="c">{{ c }}</option> }
            </select>
          } @else {
            <input [name]="p.key" [attr.data-testid]="'pref-' + p.key" [type]="control(p.key)"
                   [(ngModel)]="typed[p.key]" />
          }
          <span class="muted" [attr.data-testid]="'pref-source-' + p.key">From: {{ p.source }}</span>
        </label>
      }

      <h2>Notifications</h2>
      <p class="muted">Recorded for when the platform sends notifications; nothing is sent from these yet.</p>
      <table data-testid="notifications">
        <thead><tr><th>Event</th><th>In the console</th><th>By e-mail</th></tr></thead>
        <tbody>
          @for (n of notify(); track n.event) {
            <tr>
              <td>{{ n.label }}</td>
              <td><input type="checkbox" [name]="n.inApp" [attr.data-testid]="'pref-' + n.inApp"
                         [checked]="typed[n.inApp] === 'true'" (change)="tick(n.inApp, $any($event.target).checked)" /></td>
              <td><input type="checkbox" [name]="n.email" [attr.data-testid]="'pref-' + n.email"
                         [checked]="typed[n.email] === 'true'" (change)="tick(n.email, $any($event.target).checked)" /></td>
            </tr>
          }
        </tbody>
      </table>
      <p>
        <button type="submit" data-testid="save-preferences" [disabled]="busy()">Save preferences</button>
        <button type="button" class="link" data-testid="reset-preferences" [disabled]="busy()" (click)="reset()">Use the defaults</button>
      </p>
      @if (saved()) { <p class="ok" role="status" data-testid="preferences-saved">{{ saved() }}</p> }
    </form>
  `,
})
export class MyProfilePage implements OnInit {
  private readonly gateway = inject(LmsGateway);

  readonly me = signal<MeView | null>(null);
  readonly display = signal<EffectiveSettingView[]>([]);
  readonly notify = signal<NotifyRow[]>([]);
  readonly error = signal('');
  readonly saved = signal('');
  /** True until the preferences are loaded, and while a save is under way: a save needs the version read. */
  readonly busy = signal(true);
  /** What each field shows, by key. */
  typed: Record<string, string> = {};
  private loaded: Record<string, string> = {};
  private definitions = new Map<string, SettingDefinitionView>();
  private version = '0';
  private own: Record<string, SettingValue> = {};

  async ngOnInit(): Promise<void> {
    try {
      const [me, definitions] = await Promise.all([this.gateway.me(), this.gateway.settingDefinitions()]);
      this.me.set(me);
      this.definitions = new Map(definitions.map((d) => [d.key, d]));
      await this.load();
      this.busy.set(false);
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  control(key: string): 'select' | 'number' | 'text' {
    const d = this.definitions.get(key);
    return d ? controlOf(d) : 'text';
  }

  choices(key: string): string[] {
    const d = this.definitions.get(key);
    return d ? choicesOf(d) : [];
  }

  tick(key: string, on: boolean): void {
    this.typed[key] = String(on);
  }

  /** Saves what differs from what was loaded; the server checks every value. */
  async save(): Promise<void> {
    this.error.set('');
    this.saved.set('');
    const values: Record<string, SettingValue | null> = {};
    try {
      for (const [key, text] of Object.entries(this.typed)) {
        if (text !== this.loaded[key]) {
          values[key] = fromInput(this.definitions.get(key)!, text);
        }
      }
      await this.send(values);
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  /** Clears everything the person has set, so the unit's and the company's choices apply. */
  async reset(): Promise<void> {
    this.error.set('');
    this.saved.set('');
    const values: Record<string, SettingValue | null> = {};
    Object.keys(this.own).forEach((k) => { values[k] = null; });
    try {
      await this.send(values);
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  private async send(values: Record<string, SettingValue | null>): Promise<void> {
    this.busy.set(true);
    try {
      const mine = await this.gateway.saveMyPreferences(this.version, values);
      applyDisplay([...mine.effective, ...mine.policy]);
      this.show(mine.version, mine.values, mine.effective);
      this.saved.set('Saved.');
    } finally {
      this.busy.set(false);
    }
  }

  private async load(): Promise<void> {
    const mine = await this.gateway.myPreferences();
    this.show(mine.version, mine.values, mine.effective);
  }

  private show(version: string, own: Record<string, SettingValue>, effective: EffectiveSettingView[]): void {
    this.version = version;
    this.own = own;
    this.typed = {};
    for (const e of effective) {
      this.typed[e.key] = toInput(e.value);
    }
    this.loaded = { ...this.typed };
    this.display.set(effective.filter((e) => e.category === 'UI'));
    const events = new Map<string, NotifyRow>();
    for (const e of effective.filter((x) => x.category === 'NOTIFICATIONS')) {
      const [, event] = e.key.split('.');
      const row = events.get(event) ?? { event, label: e.label.replace(/^[^:]*: /, ''), inApp: '', email: '' };
      if (e.key.endsWith('.inApp')) {
        row.inApp = e.key;
      } else {
        row.email = e.key;
      }
      events.set(event, row);
    }
    this.notify.set([...events.values()]);
  }
}
