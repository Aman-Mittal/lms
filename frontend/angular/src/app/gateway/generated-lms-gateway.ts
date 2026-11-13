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
import { onEpoch } from '../client-cache';
import { Injectable } from '@angular/core';
import {
  AddSlabRequestRateBasisEnum, DefineGuideRequestStrategyEnum, FreightMode as ApiFreightMode, OrgUnitType,
  SettingScopeType, CreateAccessPolicyRequestEffectEnum, ReviseAccessPolicyRequestEffectEnum,
  NumberedDocumentType as ApiNumberedDocumentType, NumberYearCode as ApiNumberYearCode,
} from '../api';
import {
  type AllocationDetail, type FreightBillDetail, type GuideDraft, type GuideVendorDraft, type RoutingGuideDetail,
  type SlabDraft, type TariffDraft, type PickPage, type PickQuery, Choice, Row, type EffectiveSettingView,
  type MeView, type MyPreferencesView, type OrgUnitDraft, type OrgUnitEdit, type OrgUnitView, type SettingChange,
  type SettingConditions, type SettingContextDraft, type SettingDefinitionView, type SettingHistoryRow,
  type SettingProfileDraft, type SettingProfileView, type SettingScope, type SettingValue, ORG_UNIT_WORDS,
  type AccessAttributesDraft, type AccessPolicyChanges, type AccessPolicyDraft, type AccessPolicyPage,
  type AdminUser, type DocumentDraft, type DocumentOwnerType, type NumberedDocumentType, type NumberFormatDraft,
  type NumberFormatSample, type NumberSeriesDraft, type NumberSeriesScope, type NumberSeriesView,
  type NumberVerdict,
} from './lms-gateway';
import { GeneratedGatewayMoney } from './generated-gateway-money';
import {
  adminAttributesOf, adminAttributesRequest, adminConditionOf, adminDocumentRequest, adminDocumentRow,
  adminPolicyOf, adminUserOf, commercialAllocationRow, commercialBillLineRow, commercialBillRow, commercialBlank,
  commercialGuideOf, commercialNumber, commercialOfferRow, guideLabel,
} from './generated-gateway-mappers';
import {
  changesRequest, conditionsRequest, definitionViewOf, effectiveOf, numberReleaseRow, numberSeriesOf,
  orgUnitViewOf, profileViewOf, settingHistoryRows, text,
} from './generated-gateway-helpers';

@Injectable()
export class GeneratedLmsGateway extends GeneratedGatewayMoney {
  // Number series
  numberSeries(documentType?: NumberedDocumentType): Promise<NumberSeriesView[]> {
    return this.call(this.numbering.listNumberSeries({
      documentType: documentType as unknown as ApiNumberedDocumentType | undefined, limit: 200,
    }), (s) => s.items.map(numberSeriesOf));
  }

  numberSeriesScopes(): Promise<NumberSeriesScope[]> {
    return this.call(this.numbering.listNumberSeriesScopes({ limit: 200 }), (s) => s.items.map((u) => ({
      id: text(u.id), name: text(u.name), path: text(u.path), unitType: text(u.unitType),
    })));
  }

  createNumberSeries(draft: NumberSeriesDraft): Promise<string> {
    return this.call(this.numbering.createNumberSeries({
      createNumberSeriesRequest: {
        code: draft.code.trim(), documentType: draft.documentType as unknown as ApiNumberedDocumentType,
        orgUnitId: draft.orgUnitId || undefined, prefix: draft.prefix.trim(),
        yearCode: draft.yearCode as unknown as ApiNumberYearCode, separator: draft.separator,
        counterLength: draft.counterLength, yearStartMonth: draft.yearStartMonth,
        timeZone: draft.timeZone.trim() || undefined, reuseReleased: draft.reuseReleased, active: draft.active,
        description: draft.description.trim() || undefined,
      },
    }), (r) => text(r.id));
  }

  setNumberSeriesActive(id: string, active: boolean): Promise<void> {
    return this.call(active ? this.numbering.activateNumberSeries({ id }) : this.numbering.deactivateNumberSeries({ id }),
      () => undefined);
  }

  deleteNumberSeries(id: string): Promise<void> {
    return this.call(this.numbering.deleteNumberSeries({ id }), () => undefined);
  }

  previewNumberFormat(format: NumberFormatDraft): Promise<NumberFormatSample> {
    return this.call(this.numbering.previewNumberFormat({
      prefix: format.prefix.trim(), yearCode: format.yearCode as unknown as ApiNumberYearCode,
      separator: format.separator, counterLength: format.counterLength, yearStartMonth: format.yearStartMonth,
      timeZone: format.timeZone.trim() || undefined,
    }), (p) => ({ sample: text(p.sample), nextYearSample: text(p.nextYearSample) }));
  }

  nextNumber(id: string): Promise<string> {
    return this.call(this.numbering.previewNextNumber({ id }), (p) => text(p.number));
  }

  issueNumber(id: string): Promise<string> {
    return this.call(this.numbering.issueNumber({ id }), (n) => text(n.number));
  }

  releaseNumber(id: string, number: string, reason: string): Promise<void> {
    return this.call(this.numbering.releaseNumber({
      id, releaseNumberRequest: { number: number.trim(), reason: reason.trim() },
    }), () => undefined);
  }

  numberReleases(id: string): Promise<Row[]> {
    return this.call(this.numbering.listNumberReleases({ id, limit: 100 }), (s) => s.items.map(numberReleaseRow));
  }

  verifyNumber(documentType: NumberedDocumentType, number: string): Promise<NumberVerdict> {
    return this.call(this.numbering.verifyDocumentNumber({
      documentType: documentType as unknown as ApiNumberedDocumentType, number: number.trim(),
    }), (v) => ({
      belongs: v.belongs, seriesCode: text(v.seriesCode), financialYear: text(v.financialYear),
      counter: text(v.counter), status: text(v.status),
    }));
  }

  // Profile and settings
  me(): Promise<MeView> {
    return this.call(this.users.getMe(), (m) => {
      // Every /me load checks the epoch: rights that changed since the cache was filled empty it.
      void onEpoch(text(m.cacheEpoch));
      return {
        userId: text(m.userId), tenantId: text(m.tenantId), cacheEpoch: text(m.cacheEpoch), email: text(m.email),
        fullName: text(m.fullName), tenantCode: text(m.tenantCode),
        tenantName: text(m.tenantName), orgUnitName: text(m.orgUnitName), orgPath: text(m.orgPath),
        roles: m.roles ?? [], permissions: m.permissions ?? [],
      };
    });
  }

  settingDefinitions(): Promise<SettingDefinitionView[]> {
    return this.call(this.settingsApi.listSettingDefinitions(), (s) => s.items.map(definitionViewOf));
  }

  myPreferences(): Promise<MyPreferencesView> {
    return this.call(this.settingsApi.getMyPreferences(), (m) => ({
      version: text(m.version), values: (m.values ?? {}) as Record<string, SettingValue>,
      effective: m.effective.map(effectiveOf), policy: (m.policy ?? []).map(effectiveOf),
    }));
  }

  saveMyPreferences(version: string, values: Record<string, SettingValue | null>): Promise<MyPreferencesView> {
    return this.call(this.settingsApi.updateMyPreferences({
      ifMatch: `"${version}"`, updateMyPreferencesRequest: { values },
    }), (m) => ({
      version: text(m.version), values: (m.values ?? {}) as Record<string, SettingValue>,
      effective: m.effective.map(effectiveOf), policy: (m.policy ?? []).map(effectiveOf),
    }));
  }

  settingProfiles(scope?: SettingScope): Promise<SettingProfileView[]> {
    return this.call(this.settingsApi.listSettingProfiles({
      scopeType: scope as unknown as SettingScopeType | undefined, limit: 200,
    }), (s) => s.items.map(profileViewOf));
  }

  settingProfile(id: string): Promise<SettingProfileView> {
    return this.call(this.settingsApi.getSettingProfile({ id }), profileViewOf);
  }

  createSettingProfile(draft: SettingProfileDraft): Promise<string> {
    return this.call(this.settingsApi.createSettingProfile({
      createSettingProfileRequest: {
        scopeType: draft.scopeType as unknown as SettingScopeType, scopeId: draft.scopeId || undefined,
        name: draft.name.trim() || undefined,
        conditions: draft.conditions ? conditionsRequest(draft.conditions) : undefined,
      },
    }), (r) => text(r.id));
  }

  updateSettingConditions(id: string, version: string, conditions: SettingConditions): Promise<SettingProfileView> {
    return this.call(this.settingsApi.updateSettingProfile({
      id, ifMatch: `"${version}"`, updateSettingProfileRequest: { conditions: conditionsRequest(conditions) },
    }), profileViewOf);
  }

  // Organisation units
  orgUnits(): Promise<OrgUnitView[]> {
    return this.call(this.orgUnitsApi.listOrgUnits({ limit: 200 }), (s) => s.items.map(orgUnitViewOf));
  }

  orgUnit(id: string): Promise<OrgUnitView> {
    return this.call(this.orgUnitsApi.getOrgUnit({ id }), orgUnitViewOf);
  }

  createOrgUnit(draft: OrgUnitDraft): Promise<string> {
    return this.call(this.orgUnitsApi.createOrgUnit({
      createOrgUnitRequest: {
        parentId: draft.parentId, slug: draft.slug.trim(), name: draft.name.trim(),
        unitType: draft.unitType as unknown as OrgUnitType, countryCode: draft.countryCode.trim() || undefined,
        stateCode: draft.stateCode.trim() || undefined,
      },
    }), (r) => text(r.id));
  }

  updateOrgUnit(id: string, version: string, edit: OrgUnitEdit): Promise<OrgUnitView> {
    return this.call(this.orgUnitsApi.updateOrgUnit({
      id, ifMatch: `"${version}"`, updateOrgUnitRequest: {
        name: edit.name.trim(), unitType: edit.unitType as unknown as OrgUnitType,
        countryCode: edit.countryCode.trim() || undefined, stateCode: edit.stateCode.trim() || undefined,
      },
    }), orgUnitViewOf);
  }

  updateSettingProfile(id: string, version: string, changes: SettingChange[]): Promise<SettingProfileView> {
    return this.call(this.settingsApi.updateSettingProfile({
      id, ifMatch: `"${version}"`, updateSettingProfileRequest: changesRequest(changes),
    }), profileViewOf);
  }

  deleteSettingProfile(id: string, version: string): Promise<void> {
    return this.call(this.settingsApi.deleteSettingProfile({ id, ifMatch: `"${version}"` }), () => undefined);
  }

  effectiveSettings(context: SettingContextDraft): Promise<EffectiveSettingView[]> {
    return this.call(this.settingsApi.getEffectiveSettings({
      orgUnitId: context.orgUnitId || undefined, customerId: context.customerId || undefined,
      vehicleId: context.vehicleId || undefined, vendorId: context.vendorId || undefined,
      asOf: context.asOf || undefined, freightMode: (context.freightMode || undefined) as ApiFreightMode | undefined,
    }), (s) => s.items.map(effectiveOf));
  }

  settingProfileHistory(id: string): Promise<SettingHistoryRow[]> {
    return this.call(this.settingsApi.getSettingProfileHistory({ id, limit: 100 }),
      (s) => s.items.flatMap(settingHistoryRows));
  }

  // Pickers
  pick({ kind, q, cursor, warehouseId }: PickQuery): Promise<PickPage> {
    const search = q.trim() || undefined;
    const at = cursor || undefined;
    const limit = 20;
    const page = <T>(items: T[], nextCursor: string | null | undefined, label: (item: T) => Choice): PickPage =>
      ({ items: items.map(label), nextCursor: nextCursor ?? null });
    switch (kind) {
      case 'partner':
      case 'customer':
      case 'vendor':
        return this.call(this.partners.listPartners({
          partnerType: kind === 'partner' ? undefined : kind === 'customer' ? 'CUSTOMER' : 'VENDOR',
          q: search, cursor: at, limit,
        }), (s) => page(s.items, s.nextCursor, (p) => ({ id: text(p.id), label: `${text(p.code)} · ${text(p.legalName)}` })));
      case 'terminal':
      case 'warehouse':
        return this.call(this.terminals.listTerminals({
          category: kind === 'warehouse' ? 'WAREHOUSE' : undefined, q: search, cursor: at, limit,
        }), (s) => page(s.items, s.nextCursor, (t) => ({ id: text(t.id), label: `${text(t.code)} · ${text(t.name)}` })));
      case 'vehicle':
        return this.call(this.vehicles.listVehicles({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (v) => ({ id: text(v.id), label: `${text(v.registrationNo)} · ${text(v.vehicleType)}` })));
      case 'driver':
        return this.call(this.drivers.listDrivers({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (d) => ({ id: text(d.id), label: `${text(d.licenceNo)} · ${text(d.fullName)}` })));
      case 'sku':
        return this.call(this.warehouse.listSkus({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (k) => ({ id: text(k.id), label: `${text(k.code)} · ${text(k.description)}` })));
      case 'bin':
        if (!warehouseId) {
          return Promise.resolve({ items: [], nextCursor: null });
        }
        return this.call(this.warehouse.listBins({ warehouseId, q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (b) => ({ id: text(b.id), label: text(b.code) })));
      case 'order':
        return this.call(this.orders.listOrders({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (o) => ({ id: text(o.id), label: `${text(o.orderNo)} · ${text(o.status)}` })));
      case 'load':
        return this.call(this.loads.listLoads({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (l) => ({ id: text(l.id), label: `${text(l.loadNo)} · ${text(l.status)}` })));
      case 'trip':
        return this.call(this.trips.listTrips({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (t) => ({ id: text(t.id), label: `${text(t.tripNo)} · ${text(t.status)}` })));
      case 'ticket':
        return this.call(this.controlTower.listExceptions({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (x) => ({
            id: text(x.id), label: `${text(x.ticketNo)} · ${text(x.category)} · ${text(x.status)}`,
          })));
      case 'allocation':
        return this.call(this.sourcing.listAllocations({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (a) => ({ id: text(a.id), label: `${text(a.loadNo)} · ${text(a.status)}` })));
      case 'bill':
        return this.call(this.freightBills.listFreightBills({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (b) => ({ id: text(b.id), label: `${text(b.billNo)} · ${text(b.status)}` })));
      case 'consignment':
        return this.call(this.loads.listConsignments({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (c) => ({ id: text(c.id), label: `${text(c.trackingRef)} · ${text(c.status)}` })));
      case 'unassigned':
        return this.call(this.loads.listUnassignedConsignments({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (c) => ({ id: text(c.id), label: `${text(c.trackingRef)} · ${text(c.status)}` })));
      case 'routing-guide':
        return this.call(this.sourcing.listRoutingGuides({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (g) => ({ id: text(g.id), label: guideLabel(g) })));
      case 'org-unit':
        return this.call(this.settingsApi.listSettingOrgUnits({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (u) => ({
            id: text(u.id), label: `${ORG_UNIT_WORDS[text(u.unitType)] ?? text(u.unitType)}: ${text(u.name)} · ${text(u.path)}`,
          })));
    }
  }

  // Administration
  adminUsers(): Promise<AdminUser[]> {
    return this.call(this.users.listUsers(), (list) => list.map(adminUserOf));
  }

  userPermissions(userId: string): Promise<string[]> {
    return this.call(this.users.getUserPermissions({ id: userId }), (codes) => [...codes].sort());
  }

  accessAttributes(userId: string): Promise<AccessAttributesDraft> {
    return this.call(this.accessPolicyApi.getUserAccessAttributes({ id: userId }), adminAttributesOf);
  }

  setAccessAttributes(userId: string, draft: AccessAttributesDraft): Promise<void> {
    return this.call(this.accessPolicyApi.setUserAccessAttributes({
      id: userId, setUserAccessAttributesRequest: adminAttributesRequest(draft),
    }), () => undefined);
  }

  accessPolicies(cursor?: string): Promise<AccessPolicyPage> {
    return this.call(this.accessPolicyApi.listAccessPolicies({ cursor, limit: 100 }), (page) => ({
      policies: page.items.map(adminPolicyOf), nextCursor: page.hasMore ? page.nextCursor ?? null : null,
    }));
  }

  async createAccessPolicy(draft: AccessPolicyDraft): Promise<string> {
    const condition = adminConditionOf(draft.condition);
    return this.call(this.accessPolicyApi.createAccessPolicy({
      createAccessPolicyRequest: {
        code: draft.code.trim(), action: draft.action.trim(),
        effect: draft.effect as CreateAccessPolicyRequestEffectEnum, description: draft.description.trim(),
        condition, enabled: draft.enabled,
      },
    }), (made) => text(made.id));
  }

  async reviseAccessPolicy(id: string, changes: AccessPolicyChanges): Promise<void> {
    const condition = adminConditionOf(changes.condition);
    return this.call(this.accessPolicyApi.reviseAccessPolicy({
      id,
      reviseAccessPolicyRequest: {
        effect: changes.effect as ReviseAccessPolicyRequestEffectEnum, description: changes.description.trim(),
        condition, enabled: changes.enabled,
      },
    }), () => undefined);
  }

  deleteAccessPolicy(id: string): Promise<void> {
    return this.call(this.accessPolicyApi.deleteAccessPolicy({ id }), () => undefined);
  }

  expiringDocuments(withinDays: number): Promise<Row[]> {
    return this.call(this.compliance.listExpiringDocuments({ withinDays }), (docs) => docs.map(adminDocumentRow));
  }

  ownerDocuments(ownerType: DocumentOwnerType, ownerId: string): Promise<Row[]> {
    const id = ownerId.trim();
    const listed = ownerType === 'PARTNER' ? this.partners.listPartnerDocuments({ id })
      : ownerType === 'VEHICLE' ? this.vehicles.listVehicleDocuments({ id })
        : this.drivers.listDriverDocuments({ id });
    return this.call(listed, (docs) => docs.map(adminDocumentRow));
  }

  attachDocument(ownerType: DocumentOwnerType, ownerId: string, draft: DocumentDraft): Promise<string> {
    const id = ownerId.trim();
    const attachDocumentRequest = adminDocumentRequest(draft);
    const made = ownerType === 'PARTNER' ? this.partners.attachPartnerDocument({ id, attachDocumentRequest })
      : ownerType === 'VEHICLE' ? this.vehicles.attachVehicleDocument({ id, attachDocumentRequest })
        : this.drivers.attachDriverDocument({ id, attachDocumentRequest });
    return this.call(made, (r) => text(r.id));
  }

  // Commercial
  publishTariff(orgUnitId: string, draft: TariffDraft): Promise<string> {
    return this.call(this.freightBills.publishTariff({
      publishTariffRequest: {
        orgUnitId, code: draft.code.trim(), vendorPartnerId: draft.vendorPartnerId.trim(),
        originTerminalId: draft.originTerminalId.trim(), destinationTerminalId: draft.destinationTerminalId.trim(),
        vehicleType: draft.vehicleType.trim(), effectiveFrom: draft.effectiveFrom,
        currency: commercialBlank(draft.currency), minimumCharge: commercialNumber(draft.minimumCharge),
        detentionFreeHours: commercialNumber(draft.detentionFreeHours),
        detentionHourlyRate: commercialNumber(draft.detentionHourlyRate),
        additionalDropFee: commercialNumber(draft.additionalDropFee),
      },
    }), (r) => text(r.id));
  }

  addTariffSlab(tariffId: string, slab: SlabDraft): Promise<string> {
    return this.call(this.freightBills.addTariffSlab({
      id: tariffId,
      addSlabRequest: {
        minWeightKg: Number(slab.minWeightKg), maxWeightKg: commercialNumber(slab.maxWeightKg),
        rateBasis: slab.rateBasis as unknown as AddSlabRequestRateBasisEnum, rateAmount: Number(slab.rateAmount),
      },
    }), (r) => text(r.id));
  }

  recordFuelSurcharge(month: string, surchargePct: number): Promise<string> {
    return this.call(this.freightBills.recordFuelSurcharge({ fuelSurchargeRequest: { month, surchargePct } }),
      (r) => text(r.id));
  }

  defineRoutingGuide(orgUnitId: string, draft: GuideDraft): Promise<string> {
    return this.call(this.sourcing.defineRoutingGuide({
      defineGuideRequest: {
        orgUnitId, originTerminalId: draft.originTerminalId.trim(),
        destinationTerminalId: draft.destinationTerminalId.trim(), vehicleType: draft.vehicleType.trim(),
        strategy: draft.strategy as unknown as DefineGuideRequestStrategyEnum,
      },
    }), (r) => text(r.id));
  }

  routingGuide(id: string): Promise<RoutingGuideDetail> {
    return this.call(this.sourcing.getRoutingGuide({ id }), commercialGuideOf);
  }

  addGuideVendor(guideId: string, draft: GuideVendorDraft): Promise<void> {
    return this.call(this.sourcing.addRoutingGuideVendor({
      id: guideId,
      addVendorRequest: {
        vendorPartnerId: draft.vendorPartnerId.trim(), rankNo: draft.rankNo,
        responseSlaMinutes: draft.responseSlaMinutes, agreedRate: commercialNumber(draft.agreedRate),
      },
    }), () => undefined);
  }

  retireRoutingGuide(id: string): Promise<void> {
    return this.call(this.sourcing.retireRoutingGuide({ id }), () => undefined);
  }

  allocations(status?: string): Promise<Row[]> {
    return this.call(this.sourcing.listAllocations({ status: status || undefined, limit: 100 }),
      (page) => page.items.map(commercialAllocationRow));
  }

  async allocationDetail(id: string): Promise<AllocationDetail> {
    const [allocation, offers] = await Promise.all([
      this.call(this.sourcing.getAllocation({ id }), commercialAllocationRow),
      this.call(this.sourcing.listOffers({ id }), (rows) => rows.map(commercialOfferRow)),
    ]);
    return { allocation, offers };
  }

  allocateLoad(loadId: string): Promise<string> {
    return this.call(this.sourcing.allocateLoad({ allocateRequest: { loadId: loadId.trim() } }), (r) => text(r.id));
  }

  acceptOffer(allocationId: string, vendorPartnerId: string): Promise<void> {
    return this.call(this.sourcing.acceptOffer({ id: allocationId, vendorResponseRequest: { vendorPartnerId } }),
      () => undefined);
  }

  rejectOffer(allocationId: string, vendorPartnerId: string): Promise<void> {
    return this.call(this.sourcing.rejectOffer({ id: allocationId, vendorResponseRequest: { vendorPartnerId } }),
      () => undefined);
  }

  cancelAllocation(id: string): Promise<void> {
    return this.call(this.sourcing.cancelAllocation({ id }), () => undefined);
  }

  freightBillList(status?: string): Promise<Row[]> {
    return this.call(this.freightBills.listFreightBills({ status: status || undefined, limit: 100 }),
      (page) => page.items.map(commercialBillRow));
  }

  async freightBill(id: string): Promise<FreightBillDetail> {
    const [bill, lines] = await Promise.all([
      this.call(this.freightBills.getFreightBill({ id }), commercialBillRow),
      this.call(this.freightBills.getFreightBillLines({ id }), (rows) => rows.map(commercialBillLineRow)),
    ]);
    return { bill, lines };
  }

  matchFreightBill(id: string, claimedAmount: number): Promise<string> {
    return this.call(this.freightBills.matchFreightBill({ id, matchRequest: { claimedAmount } }), (r) => text(r.status));
  }

  resolveFreightBill(id: string, reason: string): Promise<void> {
    return this.call(this.freightBills.resolveDispute({ id, resolveDisputeRequest: { reason } }), () => undefined);
  }

  releaseProofHold(billId: string, reason: string): Promise<void> {
    return this.call(this.freightBills.releaseProofHold({ id: billId, releaseProofHoldRequest: { reason } }),
      () => undefined);
  }
}
