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
  type AddSlabRequestRateBasisEnum, type DefineGuideRequestStrategyEnum, UsersApi, type ExceptionCategory,
  type ListExceptionsSeverityEnum,
} from '../api';
import type {
  AllocationDetail, FreightBillDetail, GuideDraft, GuideVendorDraft, RoutingGuideDetail, SlabDraft, TariffDraft,
  Choice, LmsGateway, MapFeature, Mapped, DetectorSettingsView, ExceptionDraft, ExceptionFilter, TripActivity,
  TripWatchDraft, TripWatchView,
} from './lmsGateway';
import { GatewayDeps, absentOn404, day, decimalOrAbsent, guard, noteOf, text, ticketRow } from './gatewayShared';
import {
  commercialAllocationRow, commercialBillLineRow, commercialBillRow, commercialBlank, commercialGuideOf,
  commercialNumber, commercialOfferRow,
} from './gatewayCommercial';

/** The tariffs, routing guides, allocations, freight bills, exceptions and telematics. */
export function sourcingVerbs(d: GatewayDeps): Pick<LmsGateway,
  | 'publishTariff' | 'addTariffSlab' | 'recordFuelSurcharge' | 'defineRoutingGuide' | 'routingGuide'
  | 'addGuideVendor' | 'retireRoutingGuide' | 'allocations' | 'allocationDetail' | 'allocateLoad'
  | 'acceptOffer' | 'rejectOffer' | 'cancelAllocation' | 'freightBillList' | 'freightBill'
  | 'matchFreightBill' | 'resolveFreightBill' | 'releaseProofHold' | 'exceptionTickets'
  | 'exceptionTicket' | 'exceptionHistory' | 'raiseException' | 'assignException'
  | 'investigateException' | 'resolveException' | 'reopenException' | 'closeException' | 'userChoices'
  | 'detectorSettings' | 'saveDetectorSettings' | 'tripWatch' | 'setTripWatch' | 'clearTripWatch'
  | 'tripActivity' | 'vehiclePositions' | 'openDeviations'> {
  const { config, controlTower, freightBills, telematics, sourcing } = d;

  return {
    // Commercial
    publishTariff: (orgUnitId: string, draft: TariffDraft) =>
      guard(async () => text((await freightBills.publishTariff({
        publishTariffRequest: {
          orgUnitId, code: draft.code.trim(), vendorPartnerId: draft.vendorPartnerId.trim(),
          originTerminalId: draft.originTerminalId.trim(), destinationTerminalId: draft.destinationTerminalId.trim(),
          vehicleType: draft.vehicleType.trim(), effectiveFrom: day(draft.effectiveFrom),
          currency: commercialBlank(draft.currency), minimumCharge: commercialNumber(draft.minimumCharge),
          detentionFreeHours: commercialNumber(draft.detentionFreeHours),
          detentionHourlyRate: commercialNumber(draft.detentionHourlyRate),
          additionalDropFee: commercialNumber(draft.additionalDropFee),
        },
      })).id)),
    addTariffSlab: (tariffId: string, slab: SlabDraft) =>
      guard(async () => text((await freightBills.addTariffSlab({
        id: tariffId,
        addSlabRequest: {
          minWeightKg: Number(slab.minWeightKg), maxWeightKg: commercialNumber(slab.maxWeightKg),
          rateBasis: slab.rateBasis as unknown as AddSlabRequestRateBasisEnum, rateAmount: Number(slab.rateAmount),
        },
      })).id)),
    recordFuelSurcharge: (month: string, surchargePct: number) =>
      guard(async () => text((await freightBills.recordFuelSurcharge({
        fuelSurchargeRequest: { month: day(month), surchargePct },
      })).id)),
    defineRoutingGuide: (orgUnitId: string, draft: GuideDraft) =>
      guard(async () => text((await sourcing.defineRoutingGuide({
        defineGuideRequest: {
          orgUnitId, originTerminalId: draft.originTerminalId.trim(),
          destinationTerminalId: draft.destinationTerminalId.trim(), vehicleType: draft.vehicleType.trim(),
          strategy: draft.strategy as unknown as DefineGuideRequestStrategyEnum,
        },
      })).id)),
    routingGuide: (id: string): Promise<RoutingGuideDetail> =>
      guard(async () => commercialGuideOf(await sourcing.getRoutingGuide({ id }))),
    addGuideVendor: (guideId: string, draft: GuideVendorDraft) =>
      guard(async () => {
        await sourcing.addRoutingGuideVendor({
          id: guideId,
          addVendorRequest: {
            vendorPartnerId: draft.vendorPartnerId.trim(), rankNo: draft.rankNo,
            responseSlaMinutes: draft.responseSlaMinutes, agreedRate: commercialNumber(draft.agreedRate),
          },
        });
      }),
    retireRoutingGuide: (id: string) =>
      guard(async () => {
        await sourcing.retireRoutingGuide({ id });
      }),
    allocations: (status?: string) =>
      guard(async () => (await sourcing.listAllocations({ status: status || undefined, limit: 100 }))
        .items.map(commercialAllocationRow)),
    allocationDetail: (id: string): Promise<AllocationDetail> =>
      guard(async () => {
        const [allocation, offers] = await Promise.all([sourcing.getAllocation({ id }), sourcing.listOffers({ id })]);
        return { allocation: commercialAllocationRow(allocation), offers: offers.map(commercialOfferRow) };
      }),
    allocateLoad: (loadId: string) =>
      guard(async () => text((await sourcing.allocateLoad({ allocateRequest: { loadId: loadId.trim() } })).id)),
    acceptOffer: (allocationId: string, vendorPartnerId: string) =>
      guard(async () => {
        await sourcing.acceptOffer({ id: allocationId, vendorResponseRequest: { vendorPartnerId } });
      }),
    rejectOffer: (allocationId: string, vendorPartnerId: string) =>
      guard(async () => {
        await sourcing.rejectOffer({ id: allocationId, vendorResponseRequest: { vendorPartnerId } });
      }),
    cancelAllocation: (id: string) =>
      guard(async () => {
        await sourcing.cancelAllocation({ id });
      }),
    freightBillList: (status?: string) =>
      guard(async () => (await freightBills.listFreightBills({ status: status || undefined, limit: 100 }))
        .items.map(commercialBillRow)),
    freightBill: (id: string): Promise<FreightBillDetail> =>
      guard(async () => {
        const [bill, lines] = await Promise.all([
          freightBills.getFreightBill({ id }), freightBills.getFreightBillLines({ id })]);
        return { bill: commercialBillRow(bill), lines: lines.map(commercialBillLineRow) };
      }),
    matchFreightBill: (id: string, claimedAmount: number) =>
      guard(async () => text((await freightBills.matchFreightBill({ id, matchRequest: { claimedAmount } })).status)),
    resolveFreightBill: (id: string, reason: string) =>
      guard(async () => {
        await freightBills.resolveDispute({ id, resolveDisputeRequest: { reason } });
      }),
    releaseProofHold: (billId: string, reason: string) =>
      guard(async () => {
        await freightBills.releaseProofHold({ id: billId, releaseProofHoldRequest: { reason } });
      }),

    // Control tower
    exceptionTickets: (filter: ExceptionFilter) =>
      guard(async () => (await controlTower.listExceptions({
        status: filter.status || undefined, severity: (filter.severity || undefined) as ListExceptionsSeverityEnum | undefined,
        limit: 50,
      })).items.map(ticketRow)),
    exceptionTicket: (id: string) => guard(async () => ticketRow(await controlTower.getException({ id }))),
    exceptionHistory: (id: string) =>
      guard(async () => (await controlTower.getExceptionHistory({ id })).map((a) => ({
        action: text(a.action), fromStatus: text(a.fromStatus), toStatus: text(a.toStatus), note: text(a.note),
        actor: text(a.actor), occurredAt: text(a.occurredAt),
      }))),
    raiseException: (draft: ExceptionDraft) =>
      guard(async () => text((await controlTower.raiseException({
        raiseExceptionRequest: {
          tripId: draft.tripId.trim(), category: draft.category as ExceptionCategory,
          description: draft.description.trim(),
        },
      })).id)),
    assignException: (id: string, assigneeUserId: string, note: string) =>
      guard(() => controlTower.assignException({
        id, assignExceptionRequest: { assigneeUserId: assigneeUserId.trim(), note: note.trim() || undefined },
      })),
    investigateException: (id: string, note: string) =>
      guard(() => controlTower.investigateException({ id, exceptionNoteRequest: noteOf(note) })),
    resolveException: (id: string, resolution: string) =>
      guard(() => controlTower.resolveException({ id, resolveExceptionRequest: { resolution: resolution.trim() } })),
    reopenException: (id: string, reason: string) =>
      guard(() => controlTower.reopenException({ id, reopenExceptionRequest: { reason: reason.trim() } })),
    closeException: (id: string, note: string) =>
      guard(() => controlTower.closeException({ id, exceptionNoteRequest: noteOf(note) })),
    userChoices: (): Promise<Choice[]> =>
      guard(async () => (await new UsersApi(config).listUsers()).map((u) => ({
        id: text(u.id), label: `${text(u.fullName)} · ${text(u.email)}`,
      }))),
    detectorSettings: (): Promise<DetectorSettingsView> =>
      guard(async () => {
        const d = await controlTower.getDetectorSettings();
        return {
          etaSlipMinutes: d.etaSlipMinutes ?? 0, temperatureExcursionMinutes: d.temperatureExcursionMinutes ?? 0,
          customised: d.customised ?? false,
        };
      }),
    saveDetectorSettings: (etaSlipMinutes: number, temperatureExcursionMinutes: number) =>
      guard(() => controlTower.setDetectorSettings({
        setDetectorSettingsRequest: { etaSlipMinutes, temperatureExcursionMinutes },
      })),
    tripWatch: (tripId: string): Promise<TripWatchView | null> =>
      absentOn404(async () => {
        const w = await controlTower.getTripWatch({ tripId });
        return {
          committedArrivalAt: text(w.committedArrivalAt), tempMinC: text(w.tempMinC), tempMaxC: text(w.tempMaxC),
          outsideRangeSince: text(w.outsideRangeSince), lastReadingAt: text(w.lastReadingAt),
        };
      }),
    setTripWatch: (tripId: string, draft: TripWatchDraft) =>
      guard(() => controlTower.setTripWatch({
        tripId,
        setTripWatchRequest: {
          committedArrivalAt: draft.committedArrivalAt ? new Date(draft.committedArrivalAt) : undefined,
          tempMinC: decimalOrAbsent(draft.tempMinC), tempMaxC: decimalOrAbsent(draft.tempMaxC),
        },
      })),
    clearTripWatch: (tripId: string) => guard(() => controlTower.clearTripWatch({ tripId })),
    tripActivity: (tripId: string): Promise<TripActivity> =>
      guard(async () => {
        const [progress, crossings] = await Promise.all([
          absentOn404(() => telematics.getProgress({ tripId })),
          telematics.listCrossings({ tripId }),
        ]);
        return {
          progress: progress && {
            tripNo: text(progress.tripNo), status: text(progress.status), lastFixAt: text(progress.lastFixAt),
            completionPct: text(progress.completionPct), remainingDistanceM: text(progress.remainingDistanceM),
            estimatedMinutesRemaining: text(progress.estimatedMinutesRemaining),
            offRoute: progress.offRoute ? 'Yes' : '', deviationM: text(progress.deviationM),
          },
          crossings: crossings.map((c) => ({
            id: text(c.id), eventType: text(c.eventType), terminalCode: text(c.terminalCode),
            occurredAt: text(c.occurredAt),
          })),
        };
      }),
    vehiclePositions: (): Promise<Mapped> =>
      guard(async () => {
        const positions = await telematics.listPositions();
        const rows = positions.map((v) => ({
          id: text(v.vehicleId), registrationNo: text(v.registrationNo), tripId: text(v.tripId), tripNo: text(v.tripNo),
          tripStatus: text(v.tripStatus), speedKph: text(v.speedKph), recordedAt: text(v.recordedAt),
          ageSeconds: text(v.ageSeconds),
        }));
        const features: MapFeature[] = positions
          .filter((v) => v.lat !== undefined && v.lon !== undefined)
          .map((v) => ({
            id: text(v.vehicleId), kind: 'vehicle',
            label: `${text(v.registrationNo)}${v.tripNo ? ` · ${v.tripNo}` : ''}`,
            points: [{ lat: v.lat as number, lon: v.lon as number }],
          }));
        return { rows, features };
      }),
    openDeviations: () =>
      guard(async () => (await telematics.listOpenDeviations()).map((d) => ({
        id: text(d.id), tripId: text(d.tripId), tripNo: text(d.tripNo), detectedAt: text(d.detectedAt),
        distanceM: text(d.distanceM), corridorM: text(d.corridorM),
      }))),
  };
}
