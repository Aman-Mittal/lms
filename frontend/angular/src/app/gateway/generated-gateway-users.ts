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

import { Injector, inject } from '@angular/core';
import type { AccountDraft, AccountRow, PermissionChoice, RoleCard, RoleDraft } from './user-admin-types';
import type { UserAdminAdapter } from './user-admin-adapter';
import { GeneratedGatewaySmallOps } from './generated-gateway-small-ops';

/**
 * User and role administration. Thin on purpose: the generated services and mapping live in user-admin-adapter.ts,
 * imported on first use, so the root gateway (and the initial bundle) carries none of it.
 */
export abstract class GeneratedGatewayUsers extends GeneratedGatewaySmallOps {
  private readonly injector = inject(Injector);

  private async adapter(): Promise<UserAdminAdapter> {
    const { UserAdminAdapter: Adapter } = await import('./user-admin-adapter');
    return new Adapter(this.injector, (request, map) => this.call(request, map));
  }

  async accounts(q: string, status: string): Promise<AccountRow[]> { return (await this.adapter()).accounts(q, status); }
  async createAccount(draft: AccountDraft): Promise<string> { return (await this.adapter()).createAccount(draft); }
  async updateAccount(id: string, version: number, fullName: string, roles: string[]): Promise<void> {
    return (await this.adapter()).updateAccount(id, version, fullName, roles);
  }
  async deactivateAccount(id: string, version: number, reason: string): Promise<void> {
    return (await this.adapter()).deactivateAccount(id, version, reason);
  }
  async reactivateAccount(id: string, version: number, reason: string): Promise<void> {
    return (await this.adapter()).reactivateAccount(id, version, reason);
  }
  async setAccountPassword(id: string, password: string): Promise<void> {
    return (await this.adapter()).setAccountPassword(id, password);
  }
  async roleCards(): Promise<RoleCard[]> { return (await this.adapter()).roleCards(); }
  async permissionChoices(): Promise<PermissionChoice[]> { return (await this.adapter()).permissionChoices(); }
  async createRole(draft: RoleDraft): Promise<string> { return (await this.adapter()).createRole(draft); }
  async updateRole(id: string, version: number, name: string, permissions: string[]): Promise<void> {
    return (await this.adapter()).updateRole(id, version, name, permissions);
  }
  async deleteRole(id: string): Promise<void> { return (await this.adapter()).deleteRole(id); }
}
