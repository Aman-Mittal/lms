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
import {
  Configuration, type HistoryEntry, OrganisationUnitsApi, SettingsApi, type EffectiveSetting,
  type FreightMode as ApiFreightMode, type OrgUnit, type OrgUnitType, type SettingCombinationConditions,
  type SettingDefinition, type SettingProfile, type SettingScopeType, type SettingSource, UsersApi,
} from '../api';
import type {
  EffectiveSettingView, MeView, MyPreferencesView, OrgUnitDraft, OrgUnitEdit, OrgUnitView, SettingChange,
  SettingConditions, SettingContextDraft, SettingDefinitionView, SettingHistoryRow, SettingProfileDraft,
  SettingProfileView, SettingScope, SettingValue, LmsGateway,
} from './lmsGateway';
import { onEpoch } from '../client-cache';
import { actorOf, guard, text } from './gatewayShared';

// Profile and settings

type SettingsVerbs = Pick<LmsGateway, 'me' | 'settingDefinitions' | 'myPreferences' | 'saveMyPreferences'
  | 'settingProfiles' | 'settingProfile' | 'createSettingProfile' | 'updateSettingProfile' | 'deleteSettingProfile'
  | 'effectiveSettings' | 'settingProfileHistory' | 'updateSettingConditions' | 'orgUnits' | 'orgUnit'
  | 'createOrgUnit' | 'updateOrgUnit'>;

const SCOPE_WORDS: Record<string, string> = {
  PLATFORM: 'Platform', ORG_DEFAULT: 'Organisation default', ORG_UNIT: 'Org unit', CUSTOMER: 'Customer',
  VENDOR: 'Vendor', VEHICLE: 'Vehicle', CONTRACT: 'Contract', USER: 'You',
};

/** A `date` field as the generated client gives it (a Date at midnight UTC), as `YYYY-MM-DD`. */
function isoDay(v: Date | string | null | undefined): string {
  if (!v) {
    return '';
  }
  return v instanceof Date ? v.toISOString().slice(0, 10) : String(v).slice(0, 10);
}

/** Where a value came from, in words: the API's label (a unit with its level, a combination's conditions). */
function sourceText(src: SettingSource): string {
  if (src.kind === 'SYSTEM_DEFAULT') {
    return 'System default';
  }
  const scope = SCOPE_WORDS[String(src.scopeType)] ?? String(src.scopeType);
  const from = src.effectiveFrom ? ` (from ${isoDay(src.effectiveFrom)})` : '';
  return `${src.label || `${scope}: ${text(src.profileName)}`}${from}`;
}

function conditionsOf(c: SettingCombinationConditions | undefined): SettingConditions | null {
  return c ? {
    orgUnitId: text(c.orgUnitId), customerId: text(c.customerId), vendorId: text(c.vendorId),
    vehicleId: text(c.vehicleId), freightMode: text(c.freightMode), priority: c.priority ?? 0,
  } : null;
}

function conditionsRequest(c: SettingConditions): SettingCombinationConditions {
  return {
    orgUnitId: c.orgUnitId || undefined, customerId: c.customerId || undefined, vendorId: c.vendorId || undefined,
    vehicleId: c.vehicleId || undefined, freightMode: (c.freightMode || undefined) as ApiFreightMode | undefined,
    priority: c.priority,
  };
}

function orgUnitViewOf(u: OrgUnit): OrgUnitView {
  return {
    id: text(u.id), parentId: text(u.parentId), slug: text(u.slug), path: text(u.path), name: text(u.name),
    unitType: text(u.unitType), level: u.level, countryCode: text(u.countryCode), stateCode: text(u.stateCode),
    effectiveCountry: text(u.effectiveCountry), effectiveState: text(u.effectiveState), version: text(u.version),
  };
}

function effectiveOf(e: EffectiveSetting): EffectiveSettingView {
  return {
    key: text(e.key), type: text(e.type), category: text(e.category), label: text(e.label),
    value: (e.value ?? null) as SettingValue | null, source: sourceText(e.source),
    sourceScope: e.source.kind === 'SYSTEM_DEFAULT' ? '' : text(e.source.scopeType),
    effectiveFrom: isoDay(e.source.effectiveFrom),
  };
}

function definitionViewOf(d: SettingDefinition): SettingDefinitionView {
  return {
    key: d.key, type: text(d.type), category: text(d.category), label: d.label, description: d.description,
    defaultValue: (d.defaultValue ?? null) as SettingValue | null, scopes: (d.scopes ?? []).map(String),
    allowedValues: d.allowedValues ?? [], min: d.min ?? null, max: d.max ?? null,
    userPreference: d.userPreference, effectiveDated: d.effectiveDated, tenantOverridable: d.tenantOverridable,
  };
}

function profileViewOf(p: SettingProfile): SettingProfileView {
  return {
    id: text(p.id), scopeType: text(p.scopeType), scopeId: text(p.scopeId), scopeLabel: text(p.scopeLabel),
    name: text(p.name), version: text(p.version), values: (p.values ?? {}) as Record<string, SettingValue>,
    versions: (p.versions ?? []).map((v) => ({
      key: text(v.key), effectiveFrom: isoDay(v.effectiveFrom), value: (v.value ?? null) as SettingValue | null,
      status: text(v.status),
    })),
    conditions: conditionsOf(p.conditions),
    updatedAt: text(p.updatedAt),
  };
}

function shown(v: unknown): string {
  return v === null || v === undefined ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v);
}

/** One row per setting version a history entry changed. */
function settingHistoryRows(e: HistoryEntry): SettingHistoryRow[] {
  const before = (e.before ?? {}) as Record<string, unknown>;
  const after = (e.after ?? {}) as Record<string, unknown>;
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].filter((k) => k !== 'scopeType').sort();
  return keys.map((k) => ({
    id: `${text(e.id)}:${k}`, action: text(e.action), actor: actorOf(e), occurredAt: text(e.occurredAt),
    setting: k, before: shown(before[k]), after: shown(after[k]),
  }));
}

/** Undated changes go in `values`, dated ones in `versions`. */
function changesRequest(changes: SettingChange[]) {
  const values: Record<string, unknown> = {};
  const versions: Array<{ key: string; effectiveFrom: Date; value: unknown }> = [];
  for (const c of changes) {
    if (c.effectiveFrom) {
      versions.push({ key: c.key, effectiveFrom: new Date(`${c.effectiveFrom}T00:00:00Z`), value: c.value });
    } else {
      values[c.key] = c.value;
    }
  }
  return { values, versions };
}

/** Who the caller is, their preferences, and the tenant's setting profiles. */
export function settingsVerbs(config: Configuration): SettingsVerbs {
  const settings = new SettingsApi(config);
  const units = new OrganisationUnitsApi(config);
  const users = new UsersApi(config);
  const mine = (m: { version: number; values: object; effective: EffectiveSetting[]; policy?: EffectiveSetting[] }):
    MyPreferencesView => ({
    version: text(m.version), values: m.values as Record<string, SettingValue>, effective: m.effective.map(effectiveOf),
    policy: (m.policy ?? []).map(effectiveOf),
  });
  return {
    me: () => guard(async () => {
      const m = await users.getMe();
      // Every /me load checks the epoch: rights that changed since the cache was filled empty it.
      void onEpoch(text(m.cacheEpoch));
      return {
        userId: text(m.userId), tenantId: text(m.tenantId), cacheEpoch: text(m.cacheEpoch), email: text(m.email), fullName: text(m.fullName), tenantCode: text(m.tenantCode),
        tenantName: text(m.tenantName), orgUnitName: text(m.orgUnitName), orgPath: text(m.orgPath),
        roles: m.roles ?? [], permissions: m.permissions ?? [],
      } satisfies MeView;
    }),
    settingDefinitions: () => guard(async () => (await settings.listSettingDefinitions()).items.map(definitionViewOf)),
    myPreferences: () => guard(async () => mine(await settings.getMyPreferences())),
    saveMyPreferences: (version: string, values: Record<string, SettingValue | null>) =>
      guard(async () => mine(await settings.updateMyPreferences({
        ifMatch: `"${version}"`, updateMyPreferencesRequest: { values },
      }))),
    settingProfiles: (scope?: SettingScope) =>
      guard(async () => (await settings.listSettingProfiles({
        scopeType: scope as unknown as SettingScopeType | undefined, limit: 200,
      })).items.map(profileViewOf)),
    settingProfile: (id: string) => guard(async () => profileViewOf(await settings.getSettingProfile({ id }))),
    createSettingProfile: (draft: SettingProfileDraft) =>
      guard(async () => text((await settings.createSettingProfile({
        createSettingProfileRequest: {
          scopeType: draft.scopeType as unknown as SettingScopeType, scopeId: draft.scopeId || undefined,
          name: draft.name.trim() || undefined,
          conditions: draft.conditions ? conditionsRequest(draft.conditions) : undefined,
        },
      })).id)),
    updateSettingConditions: (id: string, version: string, conditions: SettingConditions) =>
      guard(async () => profileViewOf(await settings.updateSettingProfile({
        id, ifMatch: `"${version}"`, updateSettingProfileRequest: { conditions: conditionsRequest(conditions) },
      }))),
    orgUnits: () => guard(async () => (await units.listOrgUnits({ limit: 200 })).items.map(orgUnitViewOf)),
    orgUnit: (id: string) => guard(async () => orgUnitViewOf(await units.getOrgUnit({ id }))),
    createOrgUnit: (draft: OrgUnitDraft) =>
      guard(async () => text((await units.createOrgUnit({
        createOrgUnitRequest: {
          parentId: draft.parentId, slug: draft.slug.trim(), name: draft.name.trim(),
          unitType: draft.unitType as unknown as OrgUnitType, countryCode: draft.countryCode.trim() || undefined,
          stateCode: draft.stateCode.trim() || undefined,
        },
      })).id)),
    updateOrgUnit: (id: string, version: string, edit: OrgUnitEdit) =>
      guard(async () => orgUnitViewOf(await units.updateOrgUnit({
        id, ifMatch: `"${version}"`, updateOrgUnitRequest: {
          name: edit.name.trim(), unitType: edit.unitType as unknown as OrgUnitType,
          countryCode: edit.countryCode.trim() || undefined, stateCode: edit.stateCode.trim() || undefined,
        },
      }))),
    updateSettingProfile: (id: string, version: string, changes: SettingChange[]) =>
      guard(async () => profileViewOf(await settings.updateSettingProfile({
        id, ifMatch: `"${version}"`, updateSettingProfileRequest: changesRequest(changes),
      }))),
    deleteSettingProfile: (id: string, version: string) =>
      guard(async () => { await settings.deleteSettingProfile({ id, ifMatch: `"${version}"` }); }),
    effectiveSettings: (context: SettingContextDraft) =>
      guard(async () => (await settings.getEffectiveSettings({
        orgUnitId: context.orgUnitId || undefined, customerId: context.customerId || undefined,
        vehicleId: context.vehicleId || undefined, vendorId: context.vendorId || undefined,
        asOf: context.asOf ? new Date(`${context.asOf}T00:00:00Z`) : undefined,
        freightMode: (context.freightMode || undefined) as ApiFreightMode | undefined,
      })).items.map(effectiveOf)),
    settingProfileHistory: (id: string) =>
      guard(async () => (await settings.getSettingProfileHistory({ id, limit: 100 })).items.flatMap(settingHistoryRows)),
  };
}
