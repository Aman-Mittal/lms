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
  Configuration, TripsApi, CreditApi, type CreditLimitChange, type CreditOverrideRequestSubjectTypeEnum,
  type CreditPosition, type ListCreditLimitChangesStatusEnum, YardGateApi, type GatePass as ApiGatePass,
  type GatePurpose as ApiGatePurpose, type GateVisit as ApiGateVisit, type TripStop as ApiTripStop,
} from '../api';
import type {
  LmsGateway, Row, CreditChangeStatus, CreditLimitChangeDraft, CreditLimitState, CreditOverrideDraft,
  CreditPositionView, GateEntryDraft, GateExitDraft, GatePassDraft, GatePassFilter, GatePassQuery, GatePassView,
  GateRegisterFilter, GateVisitView, TripStopView,
} from './lmsGateway';
import { blankAsAbsent, day, guard, noteOf, text } from './gatewayShared';

// Credit

type CreditVerbs = Pick<LmsGateway, 'creditPosition' | 'grantCreditOverride' | 'requestCreditLimitChange'
  | 'creditLimitChanges' | 'partnerCreditLimitChanges' | 'approveCreditLimitChange' | 'rejectCreditLimitChange'>;

/** A customer's credit position, every amount as text and a missing one blank. */
function creditPositionOf(p: CreditPosition): CreditPositionView {
  return {
    partnerId: text(p.partnerId), partnerCode: text(p.partnerCode), partnerStatus: text(p.partnerStatus),
    trading: p.trading, currency: text(p.currency), creditLimit: text(p.creditLimit), softLimit: text(p.softLimit),
    openOrders: text(p.exposure.openOrders), openOrderCount: text(p.exposure.openOrderCount),
    unpricedOpenOrderCount: text(p.exposure.unpricedOpenOrderCount),
    unbilledCharges: text(p.exposure.unbilledCharges), unbilledChargeCount: text(p.exposure.unbilledChargeCount),
    outstandingInvoices: text(p.exposure.outstandingInvoices), exposure: text(p.exposure.total),
    headroom: text(p.headroom), limitState: text(p.limitState) as CreditLimitState,
    pendingChangeId: text(p.pendingChangeId), asOf: text(p.asOf),
  };
}

/** A credit limit change request as the administration desk lists it. */
function creditChangeRow(c: CreditLimitChange): Row {
  return {
    id: text(c.id), partnerId: text(c.partnerId), partnerCode: text(c.partnerCode), status: text(c.status),
    previousLimit: text(c.previousLimit), previousSoftLimit: text(c.previousSoftLimit),
    requestedLimit: text(c.requestedLimit), requestedSoftLimit: text(c.requestedSoftLimit), reason: text(c.reason),
    requestedBy: text(c.requestedBy), requestedAt: text(c.requestedAt), decidedBy: text(c.decidedBy),
    decidedAt: text(c.decidedAt), decisionNote: text(c.decisionNote),
  };
}

/** Credit positions, overrides and limit changes, over the same configuration as the rest of the gateway. */
export function creditVerbs(config: Configuration): CreditVerbs {
  const credit = new CreditApi(config);
  return {
    creditPosition: (partnerId: string) =>
      guard(async () => creditPositionOf(await credit.getCreditPosition({ id: partnerId }))),
    grantCreditOverride: (partnerId: string, draft: CreditOverrideDraft) =>
      guard(async () => text((await credit.grantCreditOverride({
        id: partnerId,
        creditOverrideRequest: {
          subjectType: draft.subjectType as CreditOverrideRequestSubjectTypeEnum, orderId: draft.orderId || undefined,
          period: draft.period ? day(draft.period) : undefined, upTo: draft.upTo,
          reason: draft.reason.trim(),
        },
      })).id)),
    requestCreditLimitChange: (partnerId: string, draft: CreditLimitChangeDraft) =>
      guard(async () => text((await credit.requestCreditLimitChange({
        id: partnerId,
        creditLimitChangeRequest: { creditLimit: draft.creditLimit, softLimit: draft.softLimit, reason: draft.reason.trim() },
      })).id)),
    creditLimitChanges: (status?: CreditChangeStatus) =>
      guard(async () => (await credit.listCreditLimitChanges({
        status: status as ListCreditLimitChangesStatusEnum | undefined, limit: 100,
      })).items.map(creditChangeRow)),
    partnerCreditLimitChanges: (partnerId: string) =>
      guard(async () => (await credit.listPartnerCreditLimitChanges({ id: partnerId, limit: 100 })).items
        .map(creditChangeRow)),
    approveCreditLimitChange: (id: string, note: string) =>
      guard(async () => { await credit.approveCreditLimitChange({ id, creditLimitDecisionRequest: noteOf(note) ?? {} }); }),
    rejectCreditLimitChange: (id: string, note: string) =>
      guard(async () => { await credit.rejectCreditLimitChange({ id, creditLimitDecisionRequest: noteOf(note) ?? {} }); }),
  };
}

// Yard gate and trip stops

type GateVerbs = Pick<LmsGateway, 'openGateVisits' | 'gateVisits' | 'recordGateEntry' | 'recordGateExit' | 'gatePasses'
  | 'issueGatePass' | 'cancelGatePass' | 'checkGatePass' | 'tripStops' | 'arriveAtStop' | 'departFromStop' | 'skipStop'>;

/** Minutes inside as people say them: `45 min`, `2 h 5 min`. */
function dwellOf(minutes: number | undefined): string {
  if (minutes === undefined || minutes === null) {
    return '';
  }
  return minutes >= 60 ? `${Math.floor(minutes / 60)} h ${minutes % 60} min` : `${minutes} min`;
}

function gateVisitOf(v: ApiGateVisit): GateVisitView {
  return {
    id: text(v.id), terminalCode: text(v.terminalCode), registrationNo: text(v.registrationNo),
    driverName: text(v.driverName), purpose: text(v.purpose), tripNo: text(v.tripNo), gatePassNo: text(v.gatePassNo),
    status: text(v.status), enteredAt: text(v.enteredAt), exitedAt: text(v.exitedAt), dwell: dwellOf(v.dwellMinutes),
    entrySeals: (v.entrySealNos ?? []).join(', '), exitSeals: (v.exitSealNos ?? []).join(', '),
    weightRef: text(v.exitWeightRef || v.entryWeightRef), remarks: text(v.exitRemarks || v.entryRemarks),
  };
}

function gatePassOf(p: ApiGatePass): GatePassView {
  return {
    id: text(p.id), passNo: text(p.passNo), terminalCode: text(p.terminalCode), registrationNo: text(p.registrationNo),
    tripNo: text(p.tripNo), purpose: text(p.purpose), validFrom: text(p.validFrom), validUntil: text(p.validUntil),
    status: text(p.status), expired: p.expired === true, cancelReason: text(p.cancelReason),
  };
}

function tripStopOf(s: ApiTripStop): TripStopView {
  return {
    id: text(s.id), seq: s.seq ?? 0, kind: text(s.kind), terminalCode: text(s.terminalCode),
    terminalName: text(s.terminalName), plannedArrivalAt: text(s.plannedArrivalAt), status: text(s.status),
    arrivedAt: text(s.arrivedAt), departedAt: text(s.departedAt), skipReason: text(s.skipReason),
    outOfOrder: s.outOfOrder === true, lateMinutes: s.lateMinutes ?? 0,
  };
}

/** The yard gate and a trip's stops, over the same configuration as the rest of the gateway. */
export function gateVerbs(config: Configuration, trips: TripsApi): GateVerbs {
  const gate = new YardGateApi(config);
  return {
    openGateVisits: () => guard(async () => (await gate.listOpenGateVisits({})).map(gateVisitOf)),
    gateVisits: (filter: GateRegisterFilter, cursor?: string) =>
      guard(async () => {
        const s = await gate.listGateVisits({
          status: filter.status || undefined, q: filter.q.trim() || undefined, cursor: cursor || undefined, limit: 25,
        });
        return { items: s.items.map(gateVisitOf), nextCursor: s.hasMore ? s.nextCursor ?? null : null };
      }),
    recordGateEntry: (draft: GateEntryDraft) =>
      guard(async () => text((await gate.recordGateEntry({
        gateEntryRequest: {
          terminalId: draft.terminalId || undefined, vehicleId: draft.vehicleId || undefined,
          registrationNo: draft.vehicleId ? undefined : blankAsAbsent(draft.registrationNo),
          driverId: draft.driverId || undefined, driverName: draft.driverId ? undefined : blankAsAbsent(draft.driverName),
          purpose: draft.purpose as unknown as ApiGatePurpose, tripId: draft.tripId || undefined,
          gatePassNo: blankAsAbsent(draft.gatePassNo), sealNos: draft.sealNos.length ? draft.sealNos : undefined,
          weightRef: blankAsAbsent(draft.weightRef), remarks: blankAsAbsent(draft.remarks),
        },
      })).id)),
    recordGateExit: (visitId: string, draft: GateExitDraft) =>
      guard(async () => {
        await gate.recordGateExit({
          id: visitId,
          gateExitRequest: {
            sealNos: draft.sealNos.length ? draft.sealNos : undefined, weightRef: blankAsAbsent(draft.weightRef),
            remarks: blankAsAbsent(draft.remarks),
          },
        });
      }),
    gatePasses: (filter: GatePassFilter, cursor?: string) =>
      guard(async () => {
        const s = await gate.listGatePasses({
          status: filter.status || undefined, q: filter.q.trim() || undefined, cursor: cursor || undefined, limit: 25,
        });
        return { items: s.items.map(gatePassOf), nextCursor: s.hasMore ? s.nextCursor ?? null : null };
      }),
    issueGatePass: (draft: GatePassDraft) =>
      guard(async () => text((await gate.issueGatePass({
        issueGatePassRequest: {
          terminalId: draft.terminalId || undefined, vehicleId: draft.vehicleId || undefined,
          registrationNo: draft.vehicleId ? undefined : blankAsAbsent(draft.registrationNo),
          tripId: draft.tripId || undefined, purpose: draft.purpose as unknown as ApiGatePurpose,
          validFrom: new Date(draft.validFrom), validUntil: new Date(draft.validUntil),
          passNo: blankAsAbsent(draft.passNo),
        },
      })).id)),
    cancelGatePass: (id: string, reason: string) =>
      guard(async () => {
        // The version is read fresh: a cancellation must not rest on what the list showed earlier.
        const pass = await gate.getGatePass({ id });
        await gate.cancelGatePass({
          id, ifMatch: `"${pass.version ?? 0}"`, cancelGatePassRequest: { reason: reason.trim() },
        });
      }),
    checkGatePass: (query: GatePassQuery) =>
      guard(async () => {
        const v = await gate.checkGatePass({
          terminalId: query.terminalId || undefined, vehicleId: query.vehicleId || undefined,
          registrationNo: query.vehicleId ? undefined : blankAsAbsent(query.registrationNo),
          tripId: query.tripId || undefined, passNo: blankAsAbsent(query.passNo),
        });
        return {
          admits: v.admits, passRequired: v.passRequired, passNo: text(v.passNo), code: text(v.code),
          message: text(v.message),
        };
      }),
    tripStops: (tripId: string) => guard(async () => (await trips.getTripStops({ id: tripId })).stops.map(tripStopOf)),
    arriveAtStop: (tripId: string, stopId: string) =>
      guard(async () => { await trips.arriveAtTripStop({ id: tripId, stopId, milestoneRequest: {} }); }),
    departFromStop: (tripId: string, stopId: string) =>
      guard(async () => { await trips.departFromTripStop({ id: tripId, stopId, milestoneRequest: {} }); }),
    skipStop: (tripId: string, stopId: string, reason: string) =>
      guard(async () => {
        await trips.skipTripStop({ id: tripId, stopId, skipTripStopRequest: { reason: reason.trim() } });
      }),
  };
}
