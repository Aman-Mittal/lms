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
  ControlTowerApi, Configuration, DriversApi, OrdersApi, MultimodalApi, PartnersApi, PlanningApi, SettlementApi,
  TerminalsApi, SourcingApi, TripsApi, VehiclesApi, type ListPartnersPartnerTypeEnum, SettingsApi,
  type Allocation as CommercialAllocation, type FreightBill as CommercialFreightBill,
  type FreightBillLine as CommercialFreightBillLine, type Offer as CommercialOffer,
  type RoutingGuide as CommercialRoutingGuide, ListTerminalsCategoryEnum, WarehouseApi,
  type RailTariff as RailRateCard, type Tariff as RateCard, type TariffCard as RateCardWithBands,
  type TariffSlab as RateCardBand,
} from '../api';
import {
  ORG_UNIT_WORDS, type PickPage, type PickQuery, type RoutingGuideDetail, type Choice, type LmsGateway, type Row,
  type RailTariffDraft, type RateCardPage, type TariffCardDetail, type TariffFilter,
} from './lmsGateway';
import { day, guard, text } from './gatewayShared';

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
const RATE_CARD_PAGE = 50;

type RateCardVerbs = Pick<LmsGateway, 'tariffs' | 'tariffCard' | 'railTariffs' | 'railTariff' | 'publishRailTariff'>;

export function rateCardVerbs(config: Configuration): RateCardVerbs {
  const settlement = new SettlementApi(config);
  const multimodal = new MultimodalApi(config);
  const figure = (value: string): number | undefined => (value.trim() ? Number(value.trim()) : undefined);
  return {
    tariffs: (filter: TariffFilter, cursor?: string) =>
      guard(async (): Promise<RateCardPage> => {
        const page = await settlement.listTariffs({
          vendorPartnerId: filter.vendorPartnerId.trim() || undefined,
          superseded: filter.state === '' ? undefined : filter.state === 'superseded',
          cursor, limit: RATE_CARD_PAGE,
        });
        return { rows: page.items.map(rateCardRow), nextCursor: page.nextCursor ?? null };
      }),
    tariffCard: (id: string) =>
      guard(async () => rateCardDetailOf(await settlement.getTariff({ id }))),
    railTariffs: (cursor?: string) =>
      guard(async (): Promise<RateCardPage> => {
        const page = await multimodal.listRailTariffs({ cursor, limit: RATE_CARD_PAGE });
        return { rows: (page.items ?? []).map(railRateCardRow), nextCursor: page.nextCursor ?? null };
      }),
    railTariff: (id: string) => guard(async () => railRateCardRow(await multimodal.getRailTariff({ id }))),
    publishRailTariff: (draft: RailTariffDraft) =>
      guard(async () => text((await multimodal.publishRailTariff({
        publishRailTariffRequest: {
          code: draft.code.trim(), carrierPartnerId: draft.carrierPartnerId.trim(),
          originTerminalId: draft.originTerminalId.trim(), destinationTerminalId: draft.destinationTerminalId.trim(),
          effectiveFrom: day(draft.effectiveFrom), currency: draft.currency.trim() || undefined,
          perWagonRate: figure(draft.perWagonRate), perContainerRate: figure(draft.perContainerRate),
          minimumCharge: figure(draft.minimumCharge),
        },
      })).id)),
  };
}

function rateCardRow(t: RateCard | RateCardWithBands): Row {
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

function rateCardDetailOf(t: RateCardWithBands): TariffCardDetail {
  return {
    card: rateCardRow(t),
    slabs: (t.slabs ?? []).map((b: RateCardBand) => ({
      minWeightKg: text(b.minWeightKg), maxWeightKg: b.maxWeightKg === undefined || b.maxWeightKg === null ? '—' : text(b.maxWeightKg),
      rateBasis: text(b.rateBasis), rateAmount: text(b.rateAmount),
    })),
  };
}

function railRateCardRow(r: RailRateCard): Row {
  return {
    id: text(r.id), code: text(r.code), carrierCode: text(r.carrierCode), originTerminalCode: text(r.originTerminalCode),
    destinationTerminalCode: text(r.destinationTerminalCode), currency: text(r.currency),
    perWagonRate: text(r.perWagonRate), perContainerRate: text(r.perContainerRate), minimumCharge: text(r.minimumCharge),
    effectiveFrom: text(r.effectiveFrom), effectiveTo: text(r.effectiveTo),
  };
}

// Pickers
/** A page of choices for one picker, from the listing that kind is kept in. */
interface PickerApis {
  partners: PartnersApi; terminals: TerminalsApi; vehicles: VehiclesApi; drivers: DriversApi;
  warehouse: WarehouseApi; orders: OrdersApi; loads: PlanningApi; trips: TripsApi;
  controlTower: ControlTowerApi; sourcing: SourcingApi; freightBills: SettlementApi; settings: SettingsApi;
}

export function pickerVerbs({
  partners, terminals, vehicles, drivers, warehouse, orders, loads, trips, controlTower, sourcing, freightBills,
  settings,
}: PickerApis): Pick<LmsGateway, 'pick'> {
  const page = <T>(items: T[], nextCursor: string | null | undefined, label: (item: T) => Choice): PickPage =>
    ({ items: items.map(label), nextCursor: nextCursor ?? null });
  const PICK_LIMIT = 20;
  return {
    pick: ({ kind, q, cursor, warehouseId }: PickQuery): Promise<PickPage> =>
      guard(async () => {
        const search = q.trim() || undefined;
        const at = cursor || undefined;
        switch (kind) {
          case 'partner':
          case 'customer':
          case 'vendor': {
            const s = await partners.listPartners({
              partnerType: kind === 'partner' ? undefined
                : (kind.toUpperCase() as unknown as ListPartnersPartnerTypeEnum),
              q: search, cursor: at, limit: PICK_LIMIT,
            });
            return page(s.items, s.nextCursor, (p) => ({ id: text(p.id), label: `${text(p.code)} · ${text(p.legalName)}` }));
          }
          case 'terminal':
          case 'warehouse': {
            const s = await terminals.listTerminals({
              category: kind === 'warehouse' ? ListTerminalsCategoryEnum.Warehouse : undefined,
              q: search, cursor: at, limit: PICK_LIMIT,
            });
            return page(s.items, s.nextCursor, (t) => ({ id: text(t.id), label: `${text(t.code)} · ${text(t.name)}` }));
          }
          case 'vehicle': {
            const s = await vehicles.listVehicles({ q: search, cursor: at, limit: PICK_LIMIT });
            return page(s.items, s.nextCursor, (v) => ({
              id: text(v.id), label: `${text(v.registrationNo)} · ${text(v.vehicleType)}`,
            }));
          }
          case 'driver': {
            const s = await drivers.listDrivers({ q: search, cursor: at, limit: PICK_LIMIT });
            return page(s.items, s.nextCursor, (d) => ({ id: text(d.id), label: `${text(d.licenceNo)} · ${text(d.fullName)}` }));
          }
          case 'sku': {
            const s = await warehouse.listSkus({ q: search, cursor: at, limit: PICK_LIMIT });
            return page(s.items, s.nextCursor, (k) => ({ id: text(k.id), label: `${text(k.code)} · ${text(k.description)}` }));
          }
          case 'bin': {
            if (!warehouseId) {
              return { items: [], nextCursor: null };
            }
            const s = await warehouse.listBins({ warehouseId, q: search, cursor: at, limit: PICK_LIMIT });
            return page(s.items, s.nextCursor, (b) => ({ id: text(b.id), label: text(b.code) }));
          }
          case 'order': {
            const s = await orders.listOrders({ q: search, cursor: at, limit: PICK_LIMIT });
            return page(s.items, s.nextCursor, (o) => ({ id: text(o.id), label: `${text(o.orderNo)} · ${text(o.status)}` }));
          }
          case 'load': {
            const s = await loads.listLoads({ q: search, cursor: at, limit: PICK_LIMIT });
            return page(s.items, s.nextCursor, (l) => ({ id: text(l.id), label: `${text(l.loadNo)} · ${text(l.status)}` }));
          }
          case 'trip': {
            const s = await trips.listTrips({ q: search, cursor: at, limit: PICK_LIMIT });
            return page(s.items, s.nextCursor, (t) => ({ id: text(t.id), label: `${text(t.tripNo)} · ${text(t.status)}` }));
          }
          case 'ticket': {
            const s = await controlTower.listExceptions({ q: search, cursor: at, limit: PICK_LIMIT });
            return page(s.items, s.nextCursor, (x) => ({
              id: text(x.id), label: `${text(x.ticketNo)} · ${text(x.category)} · ${text(x.status)}`,
            }));
          }
          case 'allocation': {
            const s = await sourcing.listAllocations({ q: search, cursor: at, limit: PICK_LIMIT });
            return page(s.items, s.nextCursor, (a) => ({ id: text(a.id), label: `${text(a.loadNo)} · ${text(a.status)}` }));
          }
          case 'bill': {
            const s = await freightBills.listFreightBills({ q: search, cursor: at, limit: PICK_LIMIT });
            return page(s.items, s.nextCursor, (b) => ({ id: text(b.id), label: `${text(b.billNo)} · ${text(b.status)}` }));
          }
          case 'consignment': {
            const s = await loads.listConsignments({ q: search, cursor: at, limit: PICK_LIMIT });
            return page(s.items, s.nextCursor, (c) => ({ id: text(c.id), label: `${text(c.trackingRef)} · ${text(c.status)}` }));
          }
          case 'unassigned': {
            const s = await loads.listUnassignedConsignments({ q: search, cursor: at, limit: PICK_LIMIT });
            return page(s.items, s.nextCursor, (c) => ({ id: text(c.id), label: `${text(c.trackingRef)} · ${text(c.status)}` }));
          }
          case 'routing-guide': {
            const s = await sourcing.listRoutingGuides({ q: search, cursor: at, limit: PICK_LIMIT });
            return page(s.items, s.nextCursor, (g) => ({ id: text(g.id), label: guideLabel(g) }));
          }
          case 'org-unit': {
            const s = await settings.listSettingOrgUnits({ q: search, cursor: at, limit: PICK_LIMIT });
            return page(s.items, s.nextCursor, (u) => ({
              id: text(u.id), label: `${ORG_UNIT_WORDS[text(u.unitType)] ?? text(u.unitType)}: ${text(u.name)} · ${text(u.path)}`,
            }));
          }
        }
      }),
  };
}

/** A routing guide is its lane and vehicle type; a retired one says so. */
function guideLabel(g: CommercialRoutingGuide): string {
  const lane = `${text(g.originTerminalCode)} → ${text(g.destinationTerminalCode)} · ${text(g.vehicleType)}`;
  return g.active === false ? `${lane} · retired` : lane;
}
