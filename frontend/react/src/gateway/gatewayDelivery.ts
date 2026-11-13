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
  ResponseError, type ClaimStatus as ApiClaimStatus, type ClaimType as ApiClaimType, type CustomFieldEntityType,
  type CustomFieldType, type RegisterPartnerRequestPartnerTypeEnum, type GoodsCondition as ApiGoodsCondition,
} from '../api';
import type {
  DemoResetOutcome, ClaimDraft, ClaimMove, ConsignmentCard, ConsignmentParticularsDraft, ControlTowerSummary,
  CustomFieldKind, CustomValues, Credentials, DeliveryProofView, PodDocument, PodDocumentType, PodLineDraft,
  PodPaperDraft, PodStatus, PodSubmitDraft, PodTimings, FieldChanges, FieldDraft, LmsGateway, IssuedTrackingLink,
  OtpIssued, PartnerDraft, ProofDraft, PublicTrackingView, SignedIn, SplitPart, SplitPartDraft, TrackingLinkView,
} from './lmsGateway';
import {
  GatewayDeps, consignmentCard, definitionOf, guard, instantOrAbsent, numberOrAbsent, particularsRequest, text,
} from './gatewayShared';

/** The sign-in, claims, proof of delivery, consignments and tracking links. */
export function deliveryVerbs(d: GatewayDeps): Pick<LmsGateway,
  | 'signIn' | 'summary' | 'resetDemoData' | 'list' | 'claims' | 'registerPartner' | 'listFields'
  | 'defineField' | 'reviseField' | 'customValues' | 'saveCustomValues' | 'raiseClaim'
  | 'transitionClaim' | 'issueDeliveryOtp' | 'recordDeliveryProof' | 'deliveryProof' | 'ensureProof'
  | 'feedProofLines' | 'setProofDocuments' | 'setProofTimings' | 'submitProof' | 'keyPaperProof'
  | 'markProofNotReceived' | 'consignmentByRef' | 'consignment' | 'consignmentParts'
  | 'splitConsignment' | 'amendConsignment' | 'cancelConsignment' | 'issueTrackingLink'
  | 'trackingLinks' | 'revokeTrackingLink' | 'publicTracking'> {
  const { auth, controlTower, demo, partners, loads, customFields, delivery, links, publicTracking, listClaims, listers } = d;

  return {
    signIn: (credentials: Credentials): Promise<SignedIn> =>
      guard(async () => {
        const r = await auth.login({ loginRequest: credentials });
        return { token: r.accessToken, orgUnitId: r.orgUnitId ?? '', permissions: r.permissions ?? [] };
      }),

    summary: (): Promise<ControlTowerSummary> =>
      guard(async () => {
        const s = await controlTower.summariseExceptions();
        return {
          openP1: s.openP1 ?? 0, openP2: s.openP2 ?? 0, openP3: s.openP3 ?? 0,
          unassigned: s.unassigned ?? 0, settlementHolds: s.settlementHolds ?? 0,
          openWithoutTrip: s.openWithoutTrip ?? 0,
        };
      }),

    resetDemoData: (confirm: string): Promise<DemoResetOutcome> =>
      guard(async () => {
        const r = await demo.resetDemoData({ demoResetRequest: { confirm } });
        return { tenantCode: r.tenantCode, rowsDeleted: r.rowsDeleted, signInAgain: r.signInAgain };
      }),

    list: (resource) => guard(listers[resource]),
    claims: (consignmentId?: string) => guard(() => listClaims(consignmentId)),

    registerPartner: (orgUnitId: string, draft: PartnerDraft) =>
      guard(async () => {
        await partners.registerPartner({
          registerPartnerRequest: {
            orgUnitId, code: draft.code, legalName: draft.legalName,
            partnerType: draft.partnerType as RegisterPartnerRequestPartnerTypeEnum,
          },
        });
      }),

    listFields: (kind?: CustomFieldKind) =>
      guard(async () => {
        const slice = await customFields.listCustomFieldDefinitions({
          entityType: kind as CustomFieldEntityType | undefined,
        });
        return slice.items.map(definitionOf);
      }),

    defineField: (draft: FieldDraft) =>
      guard(async () => {
        await customFields.defineCustomField({
          defineCustomFieldRequest: {
            entityType: draft.kind as CustomFieldEntityType, key: draft.key, label: draft.label,
            fieldType: draft.type as CustomFieldType, required: draft.required,
            options: draft.options,
          },
        });
      }),

    reviseField: (id: string, changes: FieldChanges) =>
      guard(async () => {
        await customFields.reviseCustomField({
          id,
          reviseCustomFieldRequest: {
            label: changes.label, required: changes.required, options: changes.options,
            active: changes.active,
          },
        });
      }),

    customValues: (kind: CustomFieldKind, recordId: string) =>
      guard(async () => {
        const got = await customFields.getCustomFieldValues({
          entityType: kind as CustomFieldEntityType, entityId: recordId,
        });
        return got.values as CustomValues;
      }),

    saveCustomValues: (kind: CustomFieldKind, recordId: string, values: CustomValues) =>
      guard(async () => {
        const saved = await customFields.setCustomFieldValues({
          entityType: kind as CustomFieldEntityType, entityId: recordId, customFieldValues: { values },
        });
        return saved.values as CustomValues;
      }),

    raiseClaim: (draft: ClaimDraft) =>
      guard(async () => {
        const made = await delivery.raiseClaim({
          raiseClaimRequest: {
            consignmentId: draft.consignmentId, claimType: draft.claimType as ApiClaimType,
            amount: draft.amount, description: draft.description,
          },
        });
        return made.id ?? '';
      }),

    transitionClaim: (id: string, move: ClaimMove) =>
      guard(async () => {
        await delivery.transitionClaim({
          id,
          transitionClaimRequest: {
            target: move.target as ApiClaimStatus, approvedAmount: move.approvedAmount,
            resolutionNote: move.resolutionNote,
          },
        });
      }),

    issueDeliveryOtp: (consignmentId: string): Promise<OtpIssued> =>
      guard(async () => {
        const issued = await delivery.issueDeliveryOtp({ consignmentId });
        return { expiresAt: text(issued.expiresAt), otp: issued.otp };
      }),

    recordDeliveryProof: (consignmentId: string, draft: ProofDraft) =>
      guard(async () => {
        await delivery.recordDeliveryProof({
          consignmentId,
          recordDeliveryProofRequest: {
            otp: draft.otp, recipientName: draft.recipientName, photoRef: draft.photoRef || undefined,
            goodsCondition: draft.goodsCondition as ApiGoodsCondition | undefined, remark: draft.remark || undefined,
          },
        });
      }),

    deliveryProof: (consignmentId: string): Promise<DeliveryProofView | null> =>
      guard(async () => {
        try {
          const p = await delivery.getDeliveryProof({ consignmentId });
          return {
            consignmentId: p.consignmentId ?? consignmentId,
            status: (['PENDING', 'OTP_ISSUED', 'CONFIRMED', 'SUBMITTED', 'NOT_RECEIVED', 'REVERSED']
              .includes(p.status as string) ? p.status : 'OTP_ISSUED') as PodStatus,
            recipientName: text(p.recipientName), photoRef: text(p.photoRef),
            deliveredAt: text(p.deliveredAt), otpExpiresAt: text(p.otpExpiresAt),
            goodsCondition: text(p.goodsCondition) || 'CLEAN', remark: text(p.remark),
            paper: !!p.paper, keyedBy: text(p.keyedBy), reportedAt: text(p.reportedAt),
            unloadingStartedAt: text(p.unloadingStartedAt), unloadingEndedAt: text(p.unloadingEndedAt),
            lines: (p.lines ?? []).map((l) => ({
              orderLineId: l.orderLineId, lineNo: l.lineNo, materialDescription: text(l.materialDescription),
              uom: text(l.uom), grossQty: text(l.grossQty), receivedQty: text(l.receivedQty), shortQty: text(l.shortQty),
              damagedQty: text(l.damagedQty), excessQty: text(l.excessQty),
            })),
            documents: (p.documents ?? []).map((d) => ({ type: d.type as unknown as PodDocumentType, reference: d.reference })),
          };
        } catch (e) {
          if (e instanceof ResponseError && e.response.status === 404) {
            return null;
          }
          throw e;
        }
      }),

    ensureProof: (consignmentId: string) =>
      guard(async () => {
        await delivery.ensureProofOfDelivery({ consignmentId });
      }),

    feedProofLines: (consignmentId: string, lines: PodLineDraft[]) =>
      guard(async () => {
        await delivery.feedProofLines({
          consignmentId,
          feedProofLinesRequest: {
            lines: lines.map((l) => ({
              orderLineId: l.orderLineId, receivedQty: numberOrAbsent(l.receivedQty), shortQty: numberOrAbsent(l.shortQty),
              damagedQty: numberOrAbsent(l.damagedQty), excessQty: numberOrAbsent(l.excessQty),
            })),
          },
        });
      }),

    setProofDocuments: (consignmentId: string, documents: PodDocument[]) =>
      guard(async () => {
        await delivery.setProofDocuments({
          consignmentId,
          setProofDocumentsRequest: { documents: documents.map((d) => ({ type: d.type as never, reference: d.reference })) },
        });
      }),

    setProofTimings: (consignmentId: string, t: PodTimings) =>
      guard(async () => {
        await delivery.setProofTimings({
          consignmentId,
          setProofTimingsRequest: {
            reportedAt: instantOrAbsent(t.reportedAt), unloadingStartedAt: instantOrAbsent(t.unloadingStartedAt),
            unloadingEndedAt: instantOrAbsent(t.unloadingEndedAt),
          },
        });
      }),

    submitProof: (consignmentId: string, d: PodSubmitDraft) =>
      guard(async () => {
        await delivery.submitProofOfDelivery({
          consignmentId,
          submitProofRequest: {
            otp: d.otp, recipientName: d.recipientName,
            goodsCondition: d.goodsCondition as ApiGoodsCondition | undefined, remark: d.remark || undefined,
          },
        });
      }),

    keyPaperProof: (consignmentId: string, d: PodPaperDraft) =>
      guard(async () => {
        await delivery.keyPaperProof({
          consignmentId,
          keyPaperProofRequest: {
            recipientName: d.recipientName,
            goodsCondition: d.goodsCondition as ApiGoodsCondition | undefined, remark: d.remark || undefined,
          },
        });
      }),

    markProofNotReceived: (consignmentId: string, remark: string) =>
      guard(async () => {
        await delivery.markProofNotReceived({ consignmentId, notReceivedRequest: { remark } });
      }),

    consignmentByRef: (trackingRef: string): Promise<ConsignmentCard> =>
      guard(async () => consignmentCard(await loads.trackConsignment({ trackingRef }))),

    consignment: (id: string): Promise<ConsignmentCard> =>
      guard(async () => consignmentCard(await loads.getConsignment({ id }))),

    consignmentParts: (id: string): Promise<ConsignmentCard[]> =>
      guard(async () => {
        // At most 500 parts, so at most three pages: read them all.
        const parts: ConsignmentCard[] = [];
        let cursor: string | undefined;
        do {
          const page = await loads.listConsignmentParts({ id, cursor, limit: 200 });
          parts.push(...page.items.map(consignmentCard));
          cursor = page.hasMore ? page.nextCursor ?? undefined : undefined;
        } while (cursor);
        return parts;
      }),

    splitConsignment: (id: string, parts: SplitPartDraft[]): Promise<SplitPart[]> =>
      guard(async () => (await loads.splitConsignment({
        id,
        splitConsignmentRequest: {
          parts: parts.map((p) => ({ deadWeightKg: Number(p.deadWeightKg), volumeM3: Number(p.volumeM3) })),
        },
      })).map((p) => ({ id: p.id, trackingRef: p.trackingRef }))),

    amendConsignment: (id: string, version: string, particulars: ConsignmentParticularsDraft) =>
      guard(async () => {
        await loads.amendConsignment({
          id, ifMatch: version, consignmentAmendmentRequest: particularsRequest(particulars),
        });
      }),

    cancelConsignment: (id: string, reason: string) =>
      guard(async () => {
        await loads.cancelConsignment({
          id, cancelConsignmentRequest: reason.trim() ? { reason: reason.trim() } : undefined,
        });
      }),

    issueTrackingLink: (consignmentId: string, expiresInDays?: number): Promise<IssuedTrackingLink> =>
      guard(async () => {
        const issued = await links.issueTrackingLink({
          consignmentId, issueTrackingLinkRequest: expiresInDays ? { expiresInDays } : undefined,
        });
        return { id: issued.id, token: issued.token, expiresAt: text(issued.expiresAt) };
      }),

    trackingLinks: (consignmentId: string): Promise<TrackingLinkView[]> =>
      guard(async () => (await links.listTrackingLinks({ consignmentId })).items.map((l) => ({
        id: text(l.id), createdAt: text(l.createdAt), expiresAt: text(l.expiresAt),
        revokedAt: text(l.revokedAt), status: (l.status ?? 'ACTIVE') as TrackingLinkView['status'],
      }))),

    revokeTrackingLink: (id: string) =>
      guard(async () => {
        await links.revokeTrackingLink({ id });
      }),

    publicTracking: (token: string): Promise<PublicTrackingView | null> =>
      guard(async () => {
        try {
          const t = await publicTracking.getPublicTracking({ token });
          return {
            reference: text(t.reference), status: text(t.status), originName: text(t.originName),
            destinationName: text(t.destinationName), requestedDeliveryAt: text(t.requestedDeliveryAt),
            deliveredAt: text(t.deliveredAt), proofRecorded: t.proofRecorded ?? false,
            position: t.position
              ? { lat: text(t.position.lat), lon: text(t.position.lon), recordedAt: text(t.position.recordedAt) }
              : null,
            partsTotal: t.partsTotal ?? null, partsDelivered: t.partsDelivered ?? null,
          };
        } catch (e) {
          if (e instanceof ResponseError && e.response.status === 404) {
            return null;
          }
          throw e;
        }
      }),
  };
}
