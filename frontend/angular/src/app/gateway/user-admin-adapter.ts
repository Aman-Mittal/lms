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

import { Injector } from '@angular/core';
import type { Observable } from 'rxjs';
import { RolesService, UserAdministrationService, UsersService } from '../api';
import type { PermissionEntry, Role, User } from '../api';
import type { AccountDraft, AccountRow, PermissionChoice, RoleCard, RoleDraft } from './user-admin-types';
import { text } from './generated-gateway-helpers';

/** The gateway's own call helper: awaits a request, maps it, and turns a failure into an ApiProblem. */
export type Call = <T, R>(request: Observable<T>, map: (value: T) => R) => Promise<R>;

function accountOf(u: User): AccountRow {
  return {
    id: text(u.id), email: text(u.email), fullName: text(u.fullName), status: text(u.status), orgName: text(u.orgName),
    lastLoginAt: text(u.lastLoginAt), version: u.version ?? 0, roles: u.roles ?? [],
  };
}

function roleOf(r: Role): RoleCard {
  return {
    id: text(r.id), code: text(r.code), name: text(r.name), standard: r.standard ?? false, userCount: r.userCount ?? 0,
    version: r.version ?? 0, permissions: r.permissions ?? [],
  };
}

function choiceOf(p: PermissionEntry): PermissionChoice {
  return { code: text(p.code), resource: text(p.resource), action: text(p.action) };
}

/**
 * Everything the user and role screens ask of the generated clients. Loaded on first use, so the generated services and
 * the mapping stay out of the initial bundle; the root gateway keeps only thin methods that import this.
 */
export class UserAdminAdapter {
  private readonly users: UsersService;
  private readonly admin: UserAdministrationService;
  private readonly roles: RolesService;

  constructor(injector: Injector, private readonly call: Call) {
    this.users = injector.get(UsersService);
    this.admin = injector.get(UserAdministrationService);
    this.roles = injector.get(RolesService);
  }

  accounts(q: string, status: string): Promise<AccountRow[]> {
    return this.call(this.users.listUsers({
      q: q.trim() || undefined, status: (status || undefined) as 'ACTIVE' | 'SUSPENDED' | 'LOCKED' | undefined,
    }), (list) => list.map(accountOf));
  }

  createAccount(draft: AccountDraft): Promise<string> {
    return this.call(this.admin.createUser({
      createUserRequest: {
        email: draft.email.trim(), password: draft.password, fullName: draft.fullName.trim(), roles: draft.roles,
        orgUnitId: draft.orgUnitId || undefined,
      },
    }), (r) => text(r.id));
  }

  updateAccount(id: string, version: number, fullName: string, roles: string[]): Promise<void> {
    return this.call(this.admin.updateUser({
      id, ifMatch: `"${version}"`, updateUserRequest: { fullName: fullName.trim(), roles },
    }), () => undefined);
  }

  deactivateAccount(id: string, version: number, reason: string): Promise<void> {
    return this.call(this.admin.deactivateUser({
      id, ifMatch: `"${version}"`, reasonRequest: { reason: reason.trim() },
    }), () => undefined);
  }

  reactivateAccount(id: string, version: number, reason: string): Promise<void> {
    return this.call(this.admin.reactivateUser({
      id, ifMatch: `"${version}"`, reasonRequest: { reason: reason.trim() },
    }), () => undefined);
  }

  setAccountPassword(id: string, password: string): Promise<void> {
    return this.call(this.admin.setUserPassword({ id, setPasswordRequest: { password } }), () => undefined);
  }

  roleCards(): Promise<RoleCard[]> {
    return this.call(this.roles.listRoles(), (list) => list.map(roleOf));
  }

  permissionChoices(): Promise<PermissionChoice[]> {
    return this.call(this.roles.listPermissions(), (list) => list.map(choiceOf));
  }

  createRole(draft: RoleDraft): Promise<string> {
    return this.call(this.roles.createRole({
      createRoleRequest: { code: draft.code.trim(), name: draft.name.trim(), permissions: draft.permissions },
    }), (r) => text(r.id));
  }

  updateRole(id: string, version: number, name: string, permissions: string[]): Promise<void> {
    return this.call(this.roles.updateRole({
      id, ifMatch: `"${version}"`, updateRoleRequest: { name: name.trim(), permissions },
    }), () => undefined);
  }

  deleteRole(id: string): Promise<void> {
    return this.call(this.roles.deleteRole({ id }), () => undefined);
  }
}
