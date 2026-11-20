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
import { ApiProblem, LmsGateway } from '../gateway/lms-gateway';
import type { PermissionChoice, RoleCard, RoleDraft } from '../gateway/user-admin-types';
import { Session } from '../session';
import { ErrorBanner } from './error-banner';
import { PageHeader } from './page-header';

interface Refusal {
  code: string;
  message: string;
}

/** The Administrator always grants everything; the server refuses to change or delete it. */
const ADMINISTRATOR = 'ADMIN';

function blankDraft(): RoleDraft {
  return { code: '', name: '', permissions: [] };
}

function withCode(codes: string[], code: string, on: boolean): string[] {
  return on ? [...codes.filter((c) => c !== code), code] : codes.filter((c) => c !== code);
}

/** The vocabulary grouped by what it is about, in the order it arrives. */
function grouped(choices: PermissionChoice[]): { resource: string; items: PermissionChoice[] }[] {
  const groups = new Map<string, PermissionChoice[]>();
  for (const c of choices) {
    groups.set(c.resource, [...(groups.get(c.resource) ?? []), c]);
  }
  return [...groups.entries()].map(([resource, items]) => ({ resource, items }));
}

/**
 * Roles: the tenant's roles and what each grants, custom roles built from the permission list, a role's permissions
 * edited under the version read, and an unused role deleted. The server refuses to delete a role somebody holds and to
 * change the Administrator; each refusal is shown by its code.
 */
@Component({
  selector: 'app-role-desk',
  imports: [ErrorBanner, FormsModule, PageHeader],
  template: `
    <app-page-header title="Roles" />
    <p class="muted">A role is a named set of permissions. Give users roles on the user accounts screen.</p>

    @if (session.can('ROLE_MANAGE')) {
      <form ngNativeValidate data-testid="role-create-form" (ngSubmit)="create()">
        <h2>Create a custom role</h2>
        <div class="inline">
          <input name="code" placeholder="Role code" aria-label="Role code" data-testid="role-code" required
                 [value]="draft().code" (input)="setField('code', $any($event.target).value)" />
          <input name="name" placeholder="Role name" aria-label="Role name" data-testid="role-name" required
                 [value]="draft().name" (input)="setField('name', $any($event.target).value)" />
        </div>
        @for (g of groups(); track g.resource) {
          <fieldset>
            <legend>{{ g.resource }}</legend>
            @for (p of g.items; track p.code) {
              <label>
                <input type="checkbox" [attr.data-testid]="'role-perm-' + p.code" [checked]="draft().permissions.includes(p.code)"
                       (change)="toggleDraft(p.code, $any($event.target).checked)" />
                {{ p.code }}
              </label>
            }
          </fieldset>
        }
        <p class="actions"><button type="submit" data-testid="role-create">Create role</button></p>
      </form>
      @if (createError(); as r) {
        <app-error-banner [message]="r.message" [code]="r.code" testId="role-create-error" codeTestId="role-create-refusal" />
      }
    }

    @if (listError()) { <app-error-banner [message]="listError()" testId="role-list-error" /> }
    @if (roles().length === 0) {
      <p class="muted" data-testid="role-list-empty">No roles.</p>
    } @else {
      <div class="table-wrap"><table data-testid="role-list">
        <thead><tr><th>Code</th><th>Name</th><th>Permissions</th><th>Users</th><th></th></tr></thead>
        <tbody>
          @for (r of roles(); track r.id) {
            <tr data-testid="role-item" [attr.data-id]="r.id" [attr.data-code]="r.code">
              <td>{{ r.code }}</td><td>{{ r.name }}</td><td>{{ r.permissions.length }}</td><td>{{ r.userCount }}</td>
              <td><button type="button" class="link" data-testid="role-select" [attr.aria-label]="'Select role ' + r.code"
                          (click)="select(r.id)">Select</button></td>
            </tr>
          }
        </tbody>
      </table></div>
    }

    @if (selected(); as r) {
      <section data-testid="role-panel" [attr.data-id]="r.id">
        <h2>{{ r.name }}</h2>
        @if (done()) { <p class="ok" role="status" data-testid="role-done">{{ done() }}</p> }
        @if (refusal(); as f) {
          <app-error-banner [message]="f.message" [code]="f.code" testId="role-error" codeTestId="role-refusal" />
        }
        @if (r.code === administrator) {
          <p class="muted" data-testid="role-fixed">The Administrator role always grants everything and cannot be changed or deleted.</p>
        } @else if (session.can('ROLE_MANAGE')) {
          <form ngNativeValidate data-testid="role-edit-form" (ngSubmit)="save(r)">
            <input name="editName" aria-label="Edit role name" data-testid="role-edit-name" required [value]="editName()"
                   (input)="editName.set($any($event.target).value)" />
            @for (g of groups(); track g.resource) {
              <fieldset>
                <legend>{{ g.resource }}</legend>
                @for (p of g.items; track p.code) {
                  <label>
                    <input type="checkbox" [attr.data-testid]="'role-edit-perm-' + p.code" [checked]="editPermissions().includes(p.code)"
                           (change)="toggleEdit(p.code, $any($event.target).checked)" />
                    {{ p.code }}
                  </label>
                }
              </fieldset>
            }
            <p class="actions">
              <button type="submit" data-testid="role-save">Save role</button>
              <button type="button" data-testid="role-delete" (click)="remove(r)">Delete role</button>
            </p>
          </form>
        } @else {
          <ul data-testid="role-permissions">@for (p of r.permissions; track p) { <li>{{ p }}</li> }</ul>
        }
      </section>
    }
  `,
})
export class RoleDesk implements OnInit {
  private readonly gateway = inject(LmsGateway);
  protected readonly session = inject(Session);
  protected readonly administrator = ADMINISTRATOR;

  readonly roles = signal<RoleCard[]>([]);
  readonly groups = signal<{ resource: string; items: PermissionChoice[] }[]>([]);
  readonly listError = signal('');
  readonly draft = signal<RoleDraft>(blankDraft());
  readonly createError = signal<Refusal | null>(null);
  readonly selected = signal<RoleCard | null>(null);
  readonly editName = signal('');
  readonly editPermissions = signal<string[]>([]);
  readonly done = signal('');
  readonly refusal = signal<Refusal | null>(null);

  ngOnInit(): void {
    void this.loadPermissions();
    void this.load();
  }

  setField(key: 'code' | 'name', value: string): void {
    this.draft.update((d) => ({ ...d, [key]: value }));
  }

  toggleDraft(code: string, on: boolean): void {
    this.draft.update((d) => ({ ...d, permissions: withCode(d.permissions, code, on) }));
  }

  toggleEdit(code: string, on: boolean): void {
    this.editPermissions.update((c) => withCode(c, code, on));
  }

  private problem(e: unknown): Refusal {
    return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
  }

  private async loadPermissions(): Promise<void> {
    try {
      this.groups.set(grouped(await this.gateway.permissionChoices()));
    } catch (e) {
      this.listError.set((e as Error).message);
    }
  }

  async load(): Promise<void> {
    this.listError.set('');
    try {
      this.roles.set(await this.gateway.roleCards());
    } catch (e) {
      this.listError.set((e as Error).message);
    }
  }

  async create(): Promise<void> {
    this.createError.set(null);
    try {
      const id = await this.gateway.createRole(this.draft());
      this.draft.set(blankDraft());
      await this.load();
      this.select(id);
      this.done.set('The role is created.');
    } catch (e) {
      this.createError.set(this.problem(e));
    }
  }

  select(id: string): void {
    const found = this.roles().find((r) => r.id === id) ?? null;
    if (found?.id !== this.selected()?.id) {
      this.done.set('');
      this.refusal.set(null);
    }
    this.selected.set(found);
    if (found) {
      this.editName.set(found.name);
      this.editPermissions.set([...found.permissions]);
    }
  }

  async save(r: RoleCard): Promise<void> {
    this.done.set('');
    this.refusal.set(null);
    let ok = '';
    try {
      await this.gateway.updateRole(r.id, r.version, this.editName(), this.editPermissions());
      ok = 'The role is saved.';
    } catch (e) {
      this.refusal.set(this.problem(e));
    }
    await this.reselect(r.id, ok);
  }

  async remove(r: RoleCard): Promise<void> {
    this.done.set('');
    this.refusal.set(null);
    try {
      await this.gateway.deleteRole(r.id);
      await this.load();
      this.selected.set(null);
      this.done.set('The role is deleted.');
    } catch (e) {
      this.refusal.set(this.problem(e));
      await this.reselect(r.id, '');
    }
  }

  private async reselect(id: string, ok: string): Promise<void> {
    const refusal = this.refusal();
    await this.load();
    this.select(id);
    this.refusal.set(refusal);
    this.done.set(ok);
  }
}
