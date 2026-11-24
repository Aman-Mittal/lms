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

import { RolesApi, UserAdministrationApi, UsersApi, type Configuration, type ListUsersStatusEnum, type PermissionEntry, type Role, type User } from '../api';
import type { LmsGateway } from './lmsGateway';
import type { AccountRow, PermissionChoice, RoleCard } from './userAdminTypes';
import { guard, text } from './gatewayShared';

type UserAdminVerbs = Pick<LmsGateway, 'accounts' | 'createAccount' | 'updateAccount' | 'deactivateAccount'
  | 'reactivateAccount' | 'setAccountPassword' | 'roleCards' | 'permissionChoices' | 'createRole' | 'updateRole'
  | 'deleteRole'>;

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

/** Creating, editing, switching off and on, and setting the password of users; and the roles they hold. */
export function userAdminVerbs(config: Configuration): UserAdminVerbs {
  const users = new UsersApi(config);
  const admin = new UserAdministrationApi(config);
  const roles = new RolesApi(config);

  return {
    accounts: (q, status) =>
      guard(async () => (await users.listUsers({
        q: q.trim() || undefined, status: (status || undefined) as ListUsersStatusEnum | undefined,
      })).map(accountOf)),

    createAccount: (d) =>
      guard(async () => text((await admin.createUser({
        createUserRequest: { email: d.email.trim(), password: d.password, fullName: d.fullName.trim(), roles: d.roles,
          orgUnitId: d.orgUnitId || undefined },
      })).id)),

    updateAccount: (id, version, fullName, roles) =>
      guard(async () => {
        await admin.updateUser({ id, ifMatch: `"${version}"`, updateUserRequest: { fullName: fullName.trim(), roles } });
      }),

    deactivateAccount: (id, version, reason) =>
      guard(async () => {
        await admin.deactivateUser({ id, ifMatch: `"${version}"`, reasonRequest: { reason: reason.trim() } });
      }),

    reactivateAccount: (id, version, reason) =>
      guard(async () => {
        await admin.reactivateUser({ id, ifMatch: `"${version}"`, reasonRequest: { reason: reason.trim() } });
      }),

    setAccountPassword: (id, password) =>
      guard(async () => { await admin.setUserPassword({ id, setPasswordRequest: { password } }); }),

    roleCards: () => guard(async () => (await roles.listRoles()).map(roleOf)),

    permissionChoices: () => guard(async () => (await roles.listPermissions()).map(choiceOf)),

    createRole: (d) =>
      guard(async () => text((await roles.createRole({
        createRoleRequest: { code: d.code.trim(), name: d.name.trim(), permissions: d.permissions },
      })).id)),

    updateRole: (id, version, name, permissions) =>
      guard(async () => {
        await roles.updateRole({ id, ifMatch: `"${version}"`, updateRoleRequest: { name: name.trim(), permissions } });
      }),

    deleteRole: (id) => guard(async () => { await roles.deleteRole({ id }); }),
  };
}
