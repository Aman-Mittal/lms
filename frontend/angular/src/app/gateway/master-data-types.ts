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

/** The four registers that can be created, edited and switched off from their list screens. */
export type MasterKind = 'partners' | 'vehicles' | 'drivers' | 'terminals';

export type MasterAction = 'deactivate' | 'reactivate' | 'blacklist';

/** A list's status filter: '' is the server default (the active records), 'ALL' every record. */
export interface ListFilter {
  status?: string;
}

/** One record as its form shows it: every value as the text a field holds, with the version it was read at. */
export interface MasterRecord {
  id: string;
  version: number;
  status: string;
  values: Record<string, string>;
}
