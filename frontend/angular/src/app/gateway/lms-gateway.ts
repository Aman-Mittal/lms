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
 * The console's view of the platform: the only API the screens know.
 *
 * ADAPTER PATTERN. The generated client is shaped by the OpenAPI contract --
 * optional fields, enums, one class per tag. Screens should not be: they want
 * display-ready values and a handful of verbs. This interface is that shape,
 * and one adapter (the generated gateway) translates the generated client into
 * it. Regenerate the client after a contract change and only the adapter can
 * break; swap in a fake gateway and every screen can be tested without a server.
 */

export interface Credentials {
  tenantCode: string;
  email: string;
  password: string;
}

export interface SignedIn {
  token: string;
  orgUnitId: string;
  permissionCount: number;
}

/** Every count is a real number; the adapter has already defaulted the contract's optionals. */
export interface ControlTowerSummary {
  openP1: number;
  openP2: number;
  openP3: number;
  unassigned: number;
  settlementHolds: number;
  openWithoutTrip: number;
}

export const RESOURCE_KEYS = ['partners', 'vehicles', 'orders', 'loads', 'trips', 'freight-bills'] as const;
export type ResourceKey = (typeof RESOURCE_KEYS)[number];

/** One table row, every cell already formatted for display. */
export type Row = Record<string, string>;

export const PARTNER_TYPES = ['CUSTOMER', 'VENDOR', 'BROKER', 'CONSIGNEE'] as const;
export type PartnerType = (typeof PARTNER_TYPES)[number];

export interface PartnerDraft {
  code: string;
  legalName: string;
  partnerType: PartnerType;
}

/** The one error screens handle: a message fit to show, and the HTTP status if there was one. */
export class ApiProblem extends Error {
  constructor(message: string, readonly status?: number) {
    super(message);
    this.name = 'ApiProblem';
  }
}

export abstract class LmsGateway {
  abstract signIn(credentials: Credentials): Promise<SignedIn>;
  abstract summary(): Promise<ControlTowerSummary>;
  abstract list(resource: ResourceKey): Promise<Row[]>;
  abstract registerPartner(orgUnitId: string, draft: PartnerDraft): Promise<void>;
}
