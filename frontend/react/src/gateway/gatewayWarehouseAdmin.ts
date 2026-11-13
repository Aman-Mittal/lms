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
  Configuration, DriversApi, PartnersApi, TerminalsApi, VehiclesApi, ListTerminalsCategoryEnum, WarehouseApi,
  AccessPoliciesApi, ComplianceApi, UsersApi, type AccessPolicy, type AttachDocumentRequest,
  type ComplianceDocument, type CreateAccessPolicyRequestEffectEnum, type ReviseAccessPolicyRequestEffectEnum,
  type SetUserAccessAttributesRequest, type SetUserAccessAttributesRequestFreightModesEnum, type User,
  type UserAccessAttributes,
} from '../api';
import {
  ApiProblem, type Choice, type LmsGateway, type Row, type AsnDraft, type DispatchDraft, type OutboundDraft,
  type ReceiptCountDraft, type SkuDraft, type StockMoveDraft, type WarehouseDocument, type AccessAttributesDraft,
  type AccessFreightMode, type AccessPolicyChanges, type AccessPolicyDraft, type AccessPolicyPage,
  type AccessPolicyView, type AdminUser, type DocumentDraft, type DocumentOwnerType, type PolicyEffect,
} from './lmsGateway';
import { blankAsAbsent, day, guard, numberOrAbsent, text } from './gatewayShared';

// Warehouse operations

/** A record's scalar fields as display cells; nested lists are mapped by the caller. */
function flatRow(record: object): Row {
  return Object.fromEntries(Object.entries(record)
    .filter(([, v]) => v === null || v === undefined || v instanceof Date || typeof v !== 'object')
    .map(([k, v]) => [k, text(v)]));
}

function stockMove(d: StockMoveDraft) {
  return { binId: d.binId.trim(), skuId: d.skuId.trim(), lotNo: blankAsAbsent(d.lotNo), quantity: Number(d.quantity) };
}

/** The warehouse verbs of the gateway, over the generated Warehouse client. */
export function warehouseVerbs(warehouse: WarehouseApi, terminals: TerminalsApi) {
  const document = <T extends object>(header: T, lines: object[] | undefined): WarehouseDocument =>
    ({ header: flatRow(header), lines: (lines ?? []).map(flatRow) });
  return {
    warehouseChoices: (): Promise<Choice[]> =>
      guard(async () => (await terminals.listTerminals({ category: ListTerminalsCategoryEnum.Warehouse, limit: 200 }))
        .items.map((t) => ({ id: text(t.id), label: `${text(t.code)} · ${text(t.name)}` }))),
    warehouseBins: (warehouseId: string) =>
      guard(async () => (await warehouse.listBins({ warehouseId, limit: 200 })).items.map(flatRow)),
    createBin: (warehouseId: string, code: string, capacityUnits: number) =>
      guard(async () => (await warehouse.createBin({
        warehouseId, createBinRequest: { code: code.trim(), capacityUnits },
      })).id),
    skus: () => guard(async () => (await warehouse.listSkus({ limit: 200 })).items.map(flatRow)),
    createSku: (draft: SkuDraft) =>
      guard(async () => (await warehouse.createSku({
        createSkuRequest: {
          code: draft.code.trim(), description: draft.description.trim(),
          ownerPartnerId: blankAsAbsent(draft.ownerPartnerId), uom: blankAsAbsent(draft.uom),
        },
      })).id),
    asns: (warehouseId: string) =>
      guard(async () => (await warehouse.listAsns({ warehouseId, limit: 100 })).items.map(flatRow)),
    asn: (id: string) => guard(async () => {
      const a = await warehouse.getAsn({ id });
      return document(a, a.lines);
    }),
    createAsn: (draft: AsnDraft) =>
      guard(async () => (await warehouse.createAsn({
        createAsnRequest: {
          warehouseId: draft.warehouseId, asnNo: draft.asnNo.trim(),
          supplierPartnerId: blankAsAbsent(draft.supplierPartnerId),
          lines: draft.lines.map((l) => ({
            skuId: l.skuId.trim(), lotNo: blankAsAbsent(l.lotNo), quantity: Number(l.quantity),
          })),
        },
      })).id),
    receiveAsn: (id: string, counts: ReceiptCountDraft[], crossDock: boolean) =>
      guard(async () => {
        await warehouse.receiveAsn({
          id, receiveAsnRequest: {
            crossDock,
            lines: counts.map((c) => ({
              lineNo: c.lineNo, receivedQty: Number(c.receivedQty), damagedQty: Number(c.damagedQty || 0),
            })),
          },
        });
      }),
    putaway: (draft: StockMoveDraft) =>
      guard(async () => { await warehouse.putaway({ putawayRequest: stockMove(draft) }); }),
    stock: (warehouseId: string) =>
      guard(async () => (await warehouse.listStock({ warehouseId, limit: 200 })).items.map(flatRow)),
    stockMovements: (skuId: string) =>
      guard(async () => (await warehouse.listStockMovements({ skuId, limit: 100 })).items.map(flatRow)),
    adjustStock: (draft: StockMoveDraft) =>
      guard(async () => (await warehouse.adjustStock({
        adjustStockRequest: { ...stockMove(draft), reason: draft.reason.trim() },
      })).id),
    releaseQuarantine: (draft: StockMoveDraft) =>
      guard(async () => (await warehouse.releaseQuarantinedStock({
        releaseQuarantineRequest: { ...stockMove(draft), reason: draft.reason.trim() },
      })).id),
    writeOffQuarantine: (warehouseId: string, draft: StockMoveDraft) =>
      guard(async () => (await warehouse.writeOffQuarantinedStock({
        writeOffQuarantineRequest: {
          warehouseId, skuId: draft.skuId.trim(), lotNo: blankAsAbsent(draft.lotNo), quantity: Number(draft.quantity),
          reason: draft.reason.trim(),
        },
      })).id),
    outboundOrders: (warehouseId: string) =>
      guard(async () => (await warehouse.listOutboundOrders({ warehouseId, limit: 100 })).items.map(flatRow)),
    outboundOrder: (id: string) => guard(async () => {
      const o = await warehouse.getOutboundOrder({ id });
      return document(o, o.lines);
    }),
    createOutboundOrder: (draft: OutboundDraft) =>
      guard(async () => (await warehouse.createOutboundOrder({
        createOutboundOrderRequest: {
          warehouseId: draft.warehouseId, orderNo: draft.orderNo.trim(), shipTo: blankAsAbsent(draft.shipTo),
          lines: draft.lines.map((l) => ({ skuId: l.skuId.trim(), quantity: Number(l.quantity) })),
        },
      })).id),
    pickOutboundOrder: (id: string) => guard(async () => { await warehouse.pickOutboundOrder({ id }); }),
    packOutboundOrder: (id: string) => guard(async () => (await warehouse.packOutboundOrder({ id })).id),
    cancelBackOrder: (id: string) => guard(async () => { await warehouse.cancelOutboundOrderBackOrder({ id }); }),
    handlingUnits: (warehouseId: string) =>
      guard(async () => (await warehouse.listHandlingUnits({ warehouseId, limit: 100 })).items.map(flatRow)),
    handlingUnit: (id: string) => guard(async () => {
      const u = await warehouse.getHandlingUnit({ id });
      return document(u, u.contents);
    }),
    dispatchHandlingUnit: (id: string, draft: DispatchDraft) =>
      guard(async () => {
        await warehouse.dispatchHandlingUnit({
          id, dispatchRequest: {
            reference: blankAsAbsent(draft.reference), destinationTerminalId: blankAsAbsent(draft.destinationTerminalId),
            consigneePartnerId: blankAsAbsent(draft.consigneePartnerId), grossWeightKg: numberOrAbsent(draft.grossWeightKg),
          },
        });
      }),
    pickWaves: (warehouseId: string) =>
      guard(async () => (await warehouse.listPickWaves({ warehouseId, limit: 100 })).items.map(flatRow)),
    pickWave: (id: string) => guard(async () => {
      const w = await warehouse.getPickWave({ id });
      return document(w, w.orders);
    }),
    releasePickWave: (warehouseId: string, orderIds: string[]) =>
      guard(async () => (await warehouse.releasePickWave({ releasePickWaveRequest: { warehouseId, orderIds } })).id),
    pickPickWave: (id: string) => guard(async () => { await warehouse.pickPickWave({ id }); }),
  };
}

// Administration

type AdminVerbs = Pick<LmsGateway, 'adminUsers' | 'userPermissions' | 'accessAttributes' | 'setAccessAttributes'
  | 'accessPolicies' | 'createAccessPolicy' | 'reviseAccessPolicy' | 'deleteAccessPolicy' | 'expiringDocuments'
  | 'ownerDocuments' | 'attachDocument'>;

/** The administration desk's verbs, over the same configuration as the rest of the gateway. */
export function adminGateway(config: Configuration): AdminVerbs {
  const users = new UsersApi(config);
  const policies = new AccessPoliciesApi(config);
  const compliance = new ComplianceApi(config);
  const partners = new PartnersApi(config);
  const vehicles = new VehiclesApi(config);
  const drivers = new DriversApi(config);
  return {
    adminUsers: () => guard(async () => (await users.listUsers()).map(adminUserOf)),
    userPermissions: (userId: string) =>
      guard(async () => [...(await users.getUserPermissions({ id: userId }))].sort()),
    accessAttributes: (userId: string) =>
      guard(async () => adminAttributesOf(await policies.getUserAccessAttributes({ id: userId }))),
    setAccessAttributes: (userId: string, draft: AccessAttributesDraft) =>
      guard(async () => {
        await policies.setUserAccessAttributes({ id: userId, setUserAccessAttributesRequest: adminAttributesRequest(draft) });
      }),
    accessPolicies: (cursor?: string): Promise<AccessPolicyPage> =>
      guard(async () => {
        const page = await policies.listAccessPolicies({ cursor, limit: 100 });
        return { policies: page.items.map(adminPolicyOf), nextCursor: page.hasMore ? page.nextCursor ?? null : null };
      }),
    createAccessPolicy: async (draft: AccessPolicyDraft) => {
      const condition = adminConditionOf(draft.condition);
      return guard(async () => (await policies.createAccessPolicy({
        createAccessPolicyRequest: {
          code: draft.code.trim(), action: draft.action.trim(),
          effect: draft.effect as CreateAccessPolicyRequestEffectEnum, description: draft.description.trim(),
          condition, enabled: draft.enabled,
        },
      })).id);
    },
    reviseAccessPolicy: async (id: string, changes: AccessPolicyChanges) => {
      const condition = adminConditionOf(changes.condition);
      return guard(async () => {
        await policies.reviseAccessPolicy({
          id,
          reviseAccessPolicyRequest: {
            effect: changes.effect as ReviseAccessPolicyRequestEffectEnum, description: changes.description.trim(),
            condition, enabled: changes.enabled,
          },
        });
      });
    },
    deleteAccessPolicy: (id: string) =>
      guard(async () => {
        await policies.deleteAccessPolicy({ id });
      }),
    expiringDocuments: (withinDays: number) =>
      guard(async () => (await compliance.listExpiringDocuments({ withinDays })).map(adminDocumentRow)),
    ownerDocuments: (ownerType: DocumentOwnerType, ownerId: string) =>
      guard(async () => {
        const id = ownerId.trim();
        const docs = ownerType === 'PARTNER' ? await partners.listPartnerDocuments({ id })
          : ownerType === 'VEHICLE' ? await vehicles.listVehicleDocuments({ id })
            : await drivers.listDriverDocuments({ id });
        return docs.map(adminDocumentRow);
      }),
    attachDocument: (ownerType: DocumentOwnerType, ownerId: string, draft: DocumentDraft) =>
      guard(async () => {
        const id = ownerId.trim();
        const attachDocumentRequest = adminDocumentRequest(draft);
        const made = ownerType === 'PARTNER' ? await partners.attachPartnerDocument({ id, attachDocumentRequest })
          : ownerType === 'VEHICLE' ? await vehicles.attachVehicleDocument({ id, attachDocumentRequest })
            : await drivers.attachDriverDocument({ id, attachDocumentRequest });
        return text(made.id);
      }),
  };
}

function adminUserOf(u: User): AdminUser {
  return {
    id: text(u.id), email: text(u.email), fullName: text(u.fullName), status: text(u.status),
    orgName: text(u.orgName), lastLoginAt: text(u.lastLoginAt),
  };
}

function adminAttributesOf(a: UserAccessAttributes): AccessAttributesDraft {
  return {
    region: text(a.region), freightModes: (a.freightModes ?? []).map((m) => m as AccessFreightMode),
    partnerId: text(a.partnerId), approvalLimit: text(a.approvalLimit),
  };
}

/** An empty set of modes is left out rather than sent: absent means unrestricted, `[]` would mean none. */
function adminAttributesRequest(d: AccessAttributesDraft): SetUserAccessAttributesRequest {
  return {
    region: blankAsAbsent(d.region),
    freightModes: d.freightModes.length > 0
      ? d.freightModes.map((m) => m as SetUserAccessAttributesRequestFreightModesEnum) : undefined,
    partnerId: blankAsAbsent(d.partnerId), approvalLimit: numberOrAbsent(d.approvalLimit),
  };
}

function adminPolicyOf(p: AccessPolicy): AccessPolicyView {
  return {
    id: text(p.id), code: text(p.code), action: text(p.action), effect: text(p.effect) as PolicyEffect,
    description: text(p.description), condition: JSON.stringify(p.condition ?? {}, null, 2),
    enabled: p.enabled === true, updatedAt: text(p.updatedAt),
  };
}

/** The condition a desk typed, as the object the API takes; anything but a JSON object is refused here. */
function adminConditionOf(condition: string): { [key: string]: unknown } {
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
function adminDocumentRow(d: ComplianceDocument): Row {
  const days = d.daysToExpiry;
  const state = d.expired ? 'Expired' : days === undefined || days === null ? 'No expiry'
    : days <= 30 ? 'Expiring' : 'Valid';
  return {
    id: text(d.id), ownerType: text(d.ownerType), ownerLabel: text(d.ownerLabel),
    documentType: text(d.documentType), documentNo: text(d.documentNo), issuingAuthority: text(d.issuingAuthority),
    issuedOn: text(d.issuedOn), expiresOn: text(d.expiresOn), daysToExpiry: text(days), state,
  };
}

function adminDocumentRequest(d: DocumentDraft): AttachDocumentRequest {
  return {
    documentType: d.documentType.trim(), documentNo: d.documentNo.trim(),
    issuingAuthority: blankAsAbsent(d.issuingAuthority),
    issuedOn: d.issuedOn.trim() ? day(d.issuedOn.trim()) : undefined,
    expiresOn: d.expiresOn.trim() ? day(d.expiresOn.trim()) : undefined,
  };
}
