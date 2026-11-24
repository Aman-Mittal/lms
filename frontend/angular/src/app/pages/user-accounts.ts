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
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiProblem, LmsGateway, type OrgUnitView } from '../gateway/lms-gateway';
import type { AccountDraft, AccountRow, RoleCard } from '../gateway/user-admin-types';
import { Session } from '../session';
import { ErrorBanner } from './error-banner';
import { PageHeader } from './page-header';
import { StatusBadge } from './status-badge';
import { ConfirmHost } from './confirm-dialog';
import { confirmWording, type PendingConfirm } from '../../../../shared/confirm';

interface Refusal {
  code: string;
  message: string;
}

function blankDraft(): AccountDraft {
  return { email: '', password: '', fullName: '', roles: [] };
}

function withCode(codes: string[], code: string, on: boolean): string[] {
  return on ? [...codes.filter((c) => c !== code), code] : codes.filter((c) => c !== code);
}

/**
 * User accounts: list and search, create a user by typing the login id and the password, edit the name and roles,
 * switch a user off and on, and set a new password. There is no invitation and no email. The server decides every
 * rule (password policy, the last administrator, switching yourself off); each refusal is shown by its code.
 */
@Component({
  selector: 'app-user-accounts',
  imports: [ConfirmHost, ErrorBanner, FormsModule, PageHeader, StatusBadge],
  template: `
    <app-page-header title="User accounts" />
    <app-confirm-host [(pending)]="pending" />
    <p class="muted">Everyone in your organisational unit and below. You choose each user's login id and password here.</p>

    @if (session.can('USER_MANAGE')) {
      <form ngNativeValidate data-testid="acct-create-form" (ngSubmit)="create()">
        <h2>Create a user</h2>
        <div class="inline">
          <input name="email" type="email" placeholder="Login id (email)" aria-label="Login id" data-testid="acct-email" required
                 autocomplete="off" [value]="draft().email" (input)="setField('email', $any($event.target).value)" />
          <input name="password" type="password" placeholder="Initial password" aria-label="Initial password"
                 data-testid="acct-password" required autocomplete="new-password" [value]="draft().password"
                 (input)="setField('password', $any($event.target).value)" />
          <input name="fullName" placeholder="Full name" aria-label="Full name" data-testid="acct-name" required
                 [value]="draft().fullName" (input)="setField('fullName', $any($event.target).value)" />
        </div>
        <label class="field">Organisational unit
          <select name="orgUnit" aria-label="Organisational unit" data-testid="acct-org-unit" [value]="orgUnitChoice()"
                  (change)="orgUnitChoice.set($any($event.target).value)">
            @for (u of unitOptions(); track u.id) {
              <option [value]="u.id" [selected]="u.id === orgUnitChoice()">{{ u.path ? u.name + ' (' + u.path + ')' : u.name }}</option>
            }
          </select>
        </label>
        <fieldset data-testid="acct-roles">
          <legend>Roles</legend>
          @for (r of roles(); track r.code) {
            <label>
              <input type="checkbox" [attr.data-testid]="'acct-role-' + r.code" [checked]="draft().roles.includes(r.code)"
                     (change)="toggleDraftRole(r.code, $any($event.target).checked)" />
              {{ r.name }}
            </label>
          }
        </fieldset>
        <p class="actions"><button type="submit" data-testid="acct-create">Create user</button></p>
      </form>
      @if (createError(); as r) {
        <app-error-banner [message]="r.message" [code]="r.code" testId="acct-create-error" codeTestId="acct-create-refusal" />
      }
    }

    <form ngNativeValidate class="inline" data-testid="acct-search-form" (ngSubmit)="search()">
      <input name="q" placeholder="Search users" aria-label="Search accounts" data-testid="acct-search" [value]="q()"
             (input)="q.set($any($event.target).value)" />
      <select aria-label="Account status" data-testid="acct-status-filter" [value]="statusFilter()"
              (change)="statusFilter.set($any($event.target).value)">
        <option value="" [selected]="statusFilter() === ''">Any status</option>
        <option value="ACTIVE" [selected]="statusFilter() === 'ACTIVE'">ACTIVE</option>
        <option value="SUSPENDED" [selected]="statusFilter() === 'SUSPENDED'">SUSPENDED</option>
        <option value="LOCKED" [selected]="statusFilter() === 'LOCKED'">LOCKED</option>
      </select>
      <button type="submit" data-testid="acct-search-go">Find users</button>
    </form>

    @if (listError()) { <app-error-banner [message]="listError()" testId="acct-list-error" /> }
    @if (list().length === 0) {
      <p class="muted" data-testid="acct-list-empty">No users match.</p>
    } @else {
      <div class="table-wrap"><table data-testid="acct-list">
        <thead><tr><th>Login id</th><th>Name</th><th>Status</th><th>Roles</th><th></th></tr></thead>
        <tbody>
          @for (a of list(); track a.id) {
            <tr data-testid="acct-item" [attr.data-id]="a.id">
              <td>{{ a.email }}</td><td>{{ a.fullName }}</td>
              <td><app-status-badge [value]="a.status" /></td><td>{{ a.roles.join(', ') || '—' }}</td>
              <td><button type="button" class="link" data-testid="acct-select" [attr.aria-label]="'Select account ' + a.email"
                          (click)="select(a.id)">Select</button></td>
            </tr>
          }
        </tbody>
      </table></div>
    }

    @if (selected(); as a) {
      <section data-testid="acct-panel" [attr.data-id]="a.id">
        <h2>{{ a.fullName }}</h2>
        <dl class="facts">
          <div><dt>Login id</dt><dd data-testid="acct-shown-email">{{ a.email }}</dd></div>
          <div><dt>Status</dt><dd><app-status-badge data-testid="acct-shown-status" [value]="a.status" /></dd></div>
          <div><dt>Unit</dt><dd data-testid="acct-shown-org">{{ a.orgName }}</dd></div>
        </dl>
        @if (done()) { <p class="ok" role="status" data-testid="acct-done">{{ done() }}</p> }
        @if (refusal(); as f) {
          <app-error-banner [message]="f.message" [code]="f.code" testId="acct-error" codeTestId="acct-refusal" />
        }
        @if (session.can('USER_MANAGE')) {
          <form ngNativeValidate data-testid="acct-edit-form" (ngSubmit)="save(a)">
            <h3>Name and roles</h3>
            <input name="editName" aria-label="Edit full name" data-testid="acct-edit-name" required [value]="editName()"
                   (input)="editName.set($any($event.target).value)" />
            <fieldset data-testid="acct-edit-roles">
              <legend>Roles</legend>
              @for (r of roles(); track r.code) {
                <label>
                  <input type="checkbox" [attr.data-testid]="'acct-edit-role-' + r.code" [checked]="editRoles().includes(r.code)"
                         (change)="toggleEditRole(r.code, $any($event.target).checked)" />
                  {{ r.name }}
                </label>
              }
            </fieldset>
            <p class="actions"><button type="submit" data-testid="acct-save">Save changes</button></p>
          </form>

          <div data-testid="acct-switch-form">
            <h3>{{ a.status === 'ACTIVE' ? 'Switch off' : 'Switch on' }}</h3>
            <p class="actions">
              <button type="button" [attr.data-testid]="a.status === 'ACTIVE' ? 'acct-deactivate' : 'acct-reactivate'"
                      (click)="askSwitch(a)">
                {{ a.status === 'ACTIVE' ? 'Deactivate user' : 'Reactivate user' }}
              </button>
            </p>
          </div>

          <form ngNativeValidate data-testid="acct-password-form" (ngSubmit)="newPasswordFor(a)">
            <h3>Set a new password</h3>
            <input name="newPassword" type="password" placeholder="New password" aria-label="New password"
                   data-testid="acct-new-password" required autocomplete="new-password" [value]="newPassword()"
                   (input)="newPassword.set($any($event.target).value)" />
            <p class="actions"><button type="submit" data-testid="acct-set-password">Set password</button></p>
          </form>
        }
      </section>
    }
  `,
})
export class UserAccounts implements OnInit {
  /** The destructive action waiting for its confirmation, shown by the confirm host in the template. */
  protected readonly pending = signal<PendingConfirm | null>(null);
  private readonly gateway = inject(LmsGateway);
  protected readonly session = inject(Session);

  readonly q = signal('');
  readonly statusFilter = signal('');
  readonly list = signal<AccountRow[]>([]);
  readonly listError = signal('');
  readonly roles = signal<RoleCard[]>([]);
  readonly orgUnits = signal<OrgUnitView[]>([]);
  /** The unit a new user is created in; starts as the caller's own unit. */
  readonly orgUnitChoice = signal<string>(this.session.orgUnitId() ?? '');
  /** The active units to pick from; the caller's own unit is always offered, even if the list could not be read. */
  readonly unitOptions = computed<Array<{ id: string; name: string; path: string }>>(() => {
    const own = this.session.orgUnitId() ?? '';
    const units = this.orgUnits().filter((u) => u.active).map((u) => ({ id: u.id, name: u.name, path: u.path }));
    return !own || units.some((u) => u.id === own) ? units : [{ id: own, name: 'My organisational unit', path: '' }, ...units];
  });
  readonly draft = signal<AccountDraft>(blankDraft());
  readonly createError = signal<Refusal | null>(null);
  readonly selected = signal<AccountRow | null>(null);
  readonly editName = signal('');
  readonly editRoles = signal<string[]>([]);
  readonly newPassword = signal('');
  readonly done = signal('');
  readonly refusal = signal<Refusal | null>(null);

  ngOnInit(): void {
    void this.loadRoles();
    void this.loadUnits();
    void this.search();
  }

  setField(key: 'email' | 'password' | 'fullName', value: string): void {
    this.draft.update((d) => ({ ...d, [key]: value }));
  }

  toggleDraftRole(code: string, on: boolean): void {
    this.draft.update((d) => ({ ...d, roles: withCode(d.roles, code, on) }));
  }

  toggleEditRole(code: string, on: boolean): void {
    this.editRoles.update((c) => withCode(c, code, on));
  }

  private problem(e: unknown): Refusal {
    return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
  }

  private async loadRoles(): Promise<void> {
    try {
      this.roles.set(await this.gateway.roleCards());
    } catch {
      this.roles.set([]);
    }
  }

  private async loadUnits(): Promise<void> {
    try {
      this.orgUnits.set(await this.gateway.orgUnits());
    } catch {
      this.orgUnits.set([]);
    }
  }

  async search(): Promise<void> {
    this.listError.set('');
    try {
      this.list.set(await this.gateway.accounts(this.q(), this.statusFilter()));
    } catch (e) {
      this.listError.set((e as Error).message);
    }
  }

  async create(): Promise<void> {
    this.createError.set(null);
    try {
      const id = await this.gateway.createAccount({ ...this.draft(), orgUnitId: this.orgUnitChoice() || undefined });
      this.draft.set(blankDraft());
      this.orgUnitChoice.set(this.session.orgUnitId() ?? '');
      await this.search();
      this.select(id);
      this.done.set('The user is created. They can sign in with the login id and password you typed.');
    } catch (e) {
      this.createError.set(this.problem(e));
    }
  }

  select(id: string): void {
    const found = this.list().find((a) => a.id === id) ?? null;
    if (found?.id !== this.selected()?.id) {
      this.done.set('');
      this.refusal.set(null);
      this.newPassword.set('');
    }
    this.selected.set(found);
    if (found) {
      this.editName.set(found.fullName);
      this.editRoles.set([...found.roles]);
    }
  }

  /** Runs one change on the user, then reloads the list so the next change carries the fresh version. */
  private async act(a: AccountRow, change: () => Promise<void>, ok: string): Promise<void> {
    this.done.set('');
    this.refusal.set(null);
    try {
      await change();
      this.done.set(ok);
      this.newPassword.set('');
    } catch (e) {
      this.refusal.set(this.problem(e));
    }
    await this.search();
    const done = this.done();
    const refusal = this.refusal();
    this.select(a.id);
    this.done.set(done);
    this.refusal.set(refusal);
  }

  save(a: AccountRow): Promise<void> {
    return this.act(a, () => this.gateway.updateAccount(a.id, a.version, this.editName(), this.editRoles()),
      'The changes are saved.');
  }

  askSwitch(a: AccountRow): void {
    const off = a.status === 'ACTIVE';
    this.pending.set({
      wording: confirmWording(off ? 'Deactivate user' : 'Reactivate user', a.email,
        off ? 'They can no longer sign in until they are switched on again.' : 'They can sign in again.'),
      reason: { label: 'Reason', testId: 'acct-reason', placeholder: 'Reason', required: true },
      tone: off ? 'danger' : 'neutral',
      run: (why) => off
        ? this.act(a, () => this.gateway.deactivateAccount(a.id, a.version, why), 'The user is switched off.')
        : this.act(a, () => this.gateway.reactivateAccount(a.id, a.version, why), 'The user is switched on.'),
    });
  }

  newPasswordFor(a: AccountRow): Promise<void> {
    return this.act(a, () => this.gateway.setAccountPassword(a.id, this.newPassword()), 'The new password is set.');
  }
}
