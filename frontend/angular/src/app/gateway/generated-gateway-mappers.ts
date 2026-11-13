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
  Allocation as CommercialAllocation, FreightBill as CommercialFreightBill,
  FreightBillLine as CommercialFreightBillLine, Offer as CommercialOffer, RoutingGuide as CommercialRoutingGuide,
  RailTariff as RailRateCard, Tariff as RateCard, TariffCard as RateCardWithBands, TariffSlab as RateCardBand,
  AccessPolicy, AttachDocumentRequest, ComplianceDocument, SetUserAccessAttributesRequest,
  SetUserAccessAttributesRequestFreightModesEnum, User, UserAccessAttributes,
} from '../api';
import {
  type RoutingGuideDetail, type TariffCardDetail, ApiProblem, Row, type StockMoveDraft, type WarehouseDocument,
  type AccessAttributesDraft, type AccessFreightMode, type AccessPolicyView, type AdminUser, type DocumentDraft,
  type PolicyEffect,
} from './lms-gateway';
import { blankAsAbsent, numberOrAbsent, text } from './generated-gateway-helpers';

// Administration

export function adminUserOf(u: User): AdminUser {
  return {
    id: text(u.id), email: text(u.email), fullName: text(u.fullName), status: text(u.status),
    orgName: text(u.orgName), lastLoginAt: text(u.lastLoginAt),
  };
}

export function adminAttributesOf(a: UserAccessAttributes): AccessAttributesDraft {
  return {
    region: text(a.region), freightModes: (a.freightModes ?? []).map((m) => m as string as AccessFreightMode),
    partnerId: text(a.partnerId), approvalLimit: text(a.approvalLimit),
  };
}

/** An empty set of modes is left out rather than sent: absent means unrestricted, `[]` would mean none. */
export function adminAttributesRequest(d: AccessAttributesDraft): SetUserAccessAttributesRequest {
  return {
    region: blankAsAbsent(d.region),
    freightModes: d.freightModes.length > 0
      ? d.freightModes.map((m) => m as SetUserAccessAttributesRequestFreightModesEnum) : undefined,
    partnerId: blankAsAbsent(d.partnerId), approvalLimit: numberOrAbsent(d.approvalLimit),
  };
}

export function adminPolicyOf(p: AccessPolicy): AccessPolicyView {
  return {
    id: text(p.id), code: text(p.code), action: text(p.action), effect: text(p.effect) as PolicyEffect,
    description: text(p.description), condition: JSON.stringify(p.condition ?? {}, null, 2),
    enabled: p.enabled === true, updatedAt: text(p.updatedAt),
  };
}

/** The condition a desk typed, as the object the API takes; anything but a JSON object is refused here. */
export function adminConditionOf(condition: string): { [key: string]: unknown } {
  let parsed: unknown;
  try {
    parsed = JSON.parse(condition);
  } catch {
    parsed = undefined;
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new ApiProblem('The condition must be a JSON object, such as {"attr": "subject.region", "op": "present"}.',
      undefined, {}, 'condition-not-json');
  }
  return parsed as { [key: string]: unknown };
}

/** `state` reads Expired, Expiring (within 30 days), Valid, or No expiry. */
export function adminDocumentRow(d: ComplianceDocument): Row {
  const days = d.daysToExpiry;
  const state = d.expired ? 'Expired' : days === undefined || days === null ? 'No expiry'
    : days <= 30 ? 'Expiring' : 'Valid';
  return {
    id: text(d.id), ownerType: text(d.ownerType), ownerLabel: text(d.ownerLabel),
    documentType: text(d.documentType), documentNo: text(d.documentNo), issuingAuthority: text(d.issuingAuthority),
    issuedOn: text(d.issuedOn), expiresOn: text(d.expiresOn), daysToExpiry: text(days), state,
  };
}

export function adminDocumentRequest(d: DocumentDraft): AttachDocumentRequest {
  return {
    documentType: d.documentType.trim(), documentNo: d.documentNo.trim(),
    issuingAuthority: blankAsAbsent(d.issuingAuthority), issuedOn: blankAsAbsent(d.issuedOn),
    expiresOn: blankAsAbsent(d.expiresOn),
  };
}

// Warehouse operations

/** A record's scalar fields as display cells; nested lists are mapped by the caller. */
export function flatRow(record: object): Row {
  return Object.fromEntries(Object.entries(record)
    .filter(([, v]) => v === null || v === undefined || typeof v !== 'object')
    .map(([k, v]) => [k, text(v)]));
}

export function documentOf(header: object, lines: object[] | undefined): WarehouseDocument {
  return { header: flatRow(header), lines: (lines ?? []).map(flatRow) };
}

export function stockMove(d: StockMoveDraft) {
  return { binId: d.binId.trim(), skuId: d.skuId.trim(), lotNo: blankAsAbsent(d.lotNo), quantity: Number(d.quantity) };
}

// Commercial

/** A blank optional text left out of a request. */
export function commercialBlank(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed ? trimmed : undefined;
}

/** A blank optional figure left out of a request. */
export function commercialNumber(value: string): number | undefined {
  const trimmed = value.trim();
  return trimmed ? Number(trimmed) : undefined;
}

export function commercialGuideOf(g: CommercialRoutingGuide): RoutingGuideDetail {
  return {
    guide: {
      id: text(g.id), originTerminalCode: text(g.originTerminalCode), destinationTerminalCode: text(g.destinationTerminalCode),
      vehicleType: text(g.vehicleType), strategy: text(g.strategy), active: g.active ? 'Yes' : '',
    },
    entries: (g.entries ?? []).map((e) => ({
      rankNo: text(e.rankNo), vendorPartnerId: text(e.vendorPartnerId), vendorName: text(e.vendorName),
      responseSlaMinutes: text(e.responseSlaMinutes), agreedRate: text(e.agreedRate),
    })),
  };
}

export function commercialAllocationRow(a: CommercialAllocation): Row {
  return {
    id: text(a.id), loadId: text(a.loadId), loadNo: text(a.loadNo), strategy: text(a.strategy), status: text(a.status),
    pendingVendorName: text(a.pendingVendorName), respondsBy: text(a.respondsBy),
    awardedVendorName: text(a.awardedVendorName), awardedAt: text(a.awardedAt), currentRank: text(a.currentRank),
    offersMade: text(a.offersMade), appliedRuleName: text(a.appliedRuleName), createdAt: text(a.createdAt),
  };
}

export function commercialOfferRow(o: CommercialOffer): Row {
  return {
    id: text(o.id), rankNo: text(o.rankNo), vendorPartnerId: text(o.vendorPartnerId), vendorName: text(o.vendorName),
    outcome: text(o.outcome), offeredAt: text(o.offeredAt), respondsBy: text(o.respondsBy),
    respondedAt: text(o.respondedAt),
  };
}

export function commercialBillRow(b: CommercialFreightBill): Row {
  return {
    id: text(b.id), billNo: text(b.billNo), status: text(b.status), source: text(b.source), currency: text(b.currency),
    tripNo: text(b.tripNo), loadNo: text(b.loadNo), vendorName: text(b.vendorName), tariffCode: text(b.tariffCode),
    pricedOn: text(b.pricedOn), chargeableWeightKg: text(b.chargeableWeightKg), baseFreight: text(b.baseFreight),
    detentionAmount: text(b.detentionAmount), multiDropAmount: text(b.multiDropAmount),
    fuelSurchargeAmount: text(b.fuelSurchargeAmount), computedAmount: text(b.computedAmount),
    claimedAmount: text(b.claimedAmount), varianceAmount: text(b.varianceAmount), variancePct: text(b.variancePct),
    resolution: text(b.resolution), matchedAt: text(b.matchedAt), settlementHeldBy: text(b.settlementHeldBy),
    awaitingProof: b.awaitingProof ? 'Yes' : '', proofHoldReleaseNote: text(b.proofHoldReleaseNote),
  };
}

export function commercialBillLineRow(l: CommercialFreightBillLine): Row {
  return {
    lineNo: text(l.lineNo), chargeType: text(l.chargeType), narrative: text(l.narrative), quantity: text(l.quantity),
    unit: text(l.unit), rate: text(l.rate), amount: text(l.amount),
  };
}

// Rate cards and rail tariffs

/** Rate cards read a page at a time; the desk follows the cursor for more. */
export const RATE_CARD_PAGE = 50;

export function rateCardRow(t: RateCard | RateCardWithBands): Row {
  return {
    id: text(t.id), code: text(t.code), orgUnitName: text(t.orgUnitName), vendorCode: text(t.vendorCode),
    vendorName: text(t.vendorName), originTerminalCode: text(t.originTerminalCode),
    destinationTerminalCode: text(t.destinationTerminalCode), vehicleType: text(t.vehicleType),
    currency: text(t.currency), effectiveFrom: text(t.effectiveFrom), effectiveTo: text(t.effectiveTo),
    superseded: t.superseded ? 'Yes' : '', minimumCharge: text(t.minimumCharge),
    detentionFreeHours: text(t.detentionFreeHours), detentionHourlyRate: text(t.detentionHourlyRate),
    additionalDropFee: text(t.additionalDropFee), slabCount: text(t.slabCount),
  };
}

export function rateCardDetailOf(t: RateCardWithBands): TariffCardDetail {
  return {
    card: rateCardRow(t),
    slabs: (t.slabs ?? []).map((b: RateCardBand) => ({
      minWeightKg: text(b.minWeightKg), maxWeightKg: b.maxWeightKg === undefined || b.maxWeightKg === null ? '—' : text(b.maxWeightKg),
      rateBasis: text(b.rateBasis), rateAmount: text(b.rateAmount),
    })),
  };
}

export function railRateCardRow(r: RailRateCard): Row {
  return {
    id: text(r.id), code: text(r.code), carrierCode: text(r.carrierCode), originTerminalCode: text(r.originTerminalCode),
    destinationTerminalCode: text(r.destinationTerminalCode), currency: text(r.currency),
    perWagonRate: text(r.perWagonRate), perContainerRate: text(r.perContainerRate), minimumCharge: text(r.minimumCharge),
    effectiveFrom: text(r.effectiveFrom), effectiveTo: text(r.effectiveTo),
  };
}

/** A routing guide is its lane and vehicle type; a retired one says so. */
export function guideLabel(g: CommercialRoutingGuide): string {
  const lane = `${text(g.originTerminalCode)} → ${text(g.destinationTerminalCode)} · ${text(g.vehicleType)}`;
  return g.active === false ? `${lane} · retired` : lane;
}
