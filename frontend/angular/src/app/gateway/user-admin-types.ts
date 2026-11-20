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

/*
 * What the user and role administration screens know of the platform. Every value is already text, a number or a list
 * of codes; the adapters turn the generated client into these.
 */

/** A user as the accounts screen lists and edits them. */
export interface AccountRow {
  id: string;
  /** The login id: an email address, as sign-in expects. */
  email: string;
  fullName: string;
  status: string;
  orgName: string;
  lastLoginAt: string;
  /** What an edit sends back as `If-Match`. */
  version: number;
  /** Role codes. */
  roles: string[];
}

/** A new user: the administrator types the login id and the password; there is no invitation. */
export interface AccountDraft {
  email: string;
  password: string;
  fullName: string;
  roles: string[];
}

/** A role, with what it grants and how many users hold it. */
export interface RoleCard {
  id: string;
  code: string;
  name: string;
  /** One of the five every tenant starts with. */
  standard: boolean;
  userCount: number;
  version: number;
  permissions: string[];
}

/** One entry of the permission vocabulary. */
export interface PermissionChoice {
  code: string;
  resource: string;
  action: string;
}

/** A new custom role. */
export interface RoleDraft {
  code: string;
  name: string;
  permissions: string[];
}
