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
  ApprovalsApi, EWayBillsApi, NumberSeriesApi, OrdersApi, TelematicsApi, type ApprovalExpiry, type ApprovalPolicyView,
  type Configuration, type NumberYearCode,
} from '../api';
import { corridorPoints, permissionCodes } from '../../../shared/small-ops';
import type { LmsGateway } from './lmsGateway';
import type { ApprovalPolicyRow, CorridorRow, OrderCloseSettingsView } from './smallOpsTypes';
import { blankAsAbsent, guard, instantOrAbsent, lineRequest, numberOrAbsent, text } from './gatewayShared';

type SmallOpsVerbs = Pick<LmsGateway, 'approvalPolicies' | 'setApprovalPolicy' | 'resetApprovalPolicy'
  | 'numberSeriesRevisionState' | 'reviseNumberSeries' | 'orderCloseSettings' | 'setOrderCloseSettings'
  | 'addOrderLine' | 'cancelOrderLine' | 'recordEwayBill' | 'corridors' | 'importCorridor' | 'clearCorridor'>;

function policyOf(p: ApprovalPolicyView): ApprovalPolicyRow {
  return {
    subjectType: text(p.subjectType), required: p.required, mandatory: p.mandatory,
    decidePermissions: p.decidePermissions ?? [], ordered: p.ordered, allowSelfApproval: p.allowSelfApproval,
    dueMinutes: text(p.dueMinutes), onExpiry: String(p.onExpiry) as ApprovalPolicyRow['onExpiry'],
  };
}

function closeOf(s: { tolerancePercent?: number; autoFulfil?: boolean; customised?: boolean }): OrderCloseSettingsView {
  return { tolerancePercent: text(s.tolerancePercent ?? 0), autoFulfil: s.autoFulfil === true, customised: s.customised === true };
}

/** Approval policies, number series revision, order lines and close settings, recording an e-way bill, corridors. */
export function smallOpsVerbs(config: Configuration): SmallOpsVerbs {
  const approvals = new ApprovalsApi(config);
  const numbering = new NumberSeriesApi(config);
  const orders = new OrdersApi(config);
  const eway = new EWayBillsApi(config);
  const telematics = new TelematicsApi(config);

  return {
    approvalPolicies: () => guard(async () => (await approvals.listApprovalPolicies()).map(policyOf)),

    setApprovalPolicy: (subjectType, draft) =>
      guard(async () => policyOf(await approvals.setApprovalPolicy({
        subjectType,
        approvalPolicyRequest: {
          required: draft.required, decidePermissions: permissionCodes(draft.decidePermissions), ordered: draft.ordered,
          allowSelfApproval: draft.allowSelfApproval, dueMinutes: numberOrAbsent(draft.dueMinutes),
          onExpiry: draft.onExpiry as ApprovalExpiry,
        },
      }))),

    resetApprovalPolicy: (subjectType) => guard(async () => policyOf(await approvals.resetApprovalPolicy({ subjectType }))),

    numberSeriesRevisionState: (id) =>
      guard(async () => {
        const n = await numbering.getNumberSeries({ id });
        return {
          version: text(n.version), issuedThisYear: text(n.issuedThisYear),
          draft: {
            prefix: text(n.prefix), yearCode: String(n.yearCode), separator: text(n.separator),
            counterLength: text(n.counterLength), yearStartMonth: text(n.yearStartMonth), timeZone: text(n.timeZone),
            reuseReleased: n.reuseReleased, description: text(n.description),
          },
        };
      }),

    reviseNumberSeries: (id, version, draft) =>
      guard(async () => {
        await numbering.reviseNumberSeries({
          id, ifMatch: version,
          reviseNumberSeriesRequest: {
            prefix: draft.prefix.trim(), yearCode: draft.yearCode as NumberYearCode, separator: draft.separator,
            counterLength: Number(draft.counterLength), yearStartMonth: Number(draft.yearStartMonth),
            timeZone: blankAsAbsent(draft.timeZone), reuseReleased: draft.reuseReleased,
            description: draft.description.trim(),
          },
        });
      }),

    orderCloseSettings: () => guard(async () => closeOf(await orders.getOrderCloseSettings())),

    setOrderCloseSettings: (tolerancePercent, autoFulfil) =>
      guard(async () => closeOf(await orders.setOrderCloseSettings({
        setOrderCloseSettingsRequest: { tolerancePercent: Number(tolerancePercent), autoFulfil },
      }))),

    addOrderLine: (orderId, line) =>
      guard(async () => text((await orders.addOrderLine({ id: orderId, addOrderLineRequest: lineRequest(line) })).id)),

    cancelOrderLine: (orderId, version, lineId, reason) =>
      guard(async () => {
        const r = await orders.cancelOrderLine({
          id: orderId, lineId, ifMatch: version, cancelOrderRequest: { reason: blankAsAbsent(reason) },
        });
        return { cancelledConsignments: r.cancelledConsignmentIds?.length ?? 0, releasedLines: r.releasedLineIds?.length ?? 0 };
      }),

    recordEwayBill: (draft) =>
      guard(async () => text((await eway.recordEwayBill({
        consignmentId: draft.consignmentId.trim(),
        recordEwayBillRequest: {
          billNo: draft.billNo.trim(), validUntil: instantOrAbsent(draft.validUntil)!,
          generatedAt: instantOrAbsent(draft.generatedAt), vehicleNo: blankAsAbsent(draft.vehicleNo),
          distanceKm: numberOrAbsent(draft.distanceKm),
        },
      })).id)),

    corridors: () =>
      guard(async () => (await telematics.listRouteCorridors({ limit: 100 })).items.map((c): CorridorRow => ({
        id: text(c.id), name: text(c.name), origin: text(c.originTerminalCode),
        destination: text(c.destinationTerminalCode), pointCount: text(c.pointCount), widthMetres: text(c.widthMetres),
        updatedAt: text(c.updatedAt),
      }))),

    // The points are read before the guard: a typo is the person's to fix, not a failure to reach the API.
    importCorridor: async (draft) => {
      const points = corridorPoints(draft.points);
      return guard(async () => text((await telematics.importRouteCorridor({
        routeCorridorRequest: {
          originTerminalId: draft.originTerminalId.trim(), destinationTerminalId: draft.destinationTerminalId.trim(),
          name: draft.name.trim(), points, widthMetres: numberOrAbsent(draft.widthMetres),
        },
      })).id));
    },

    clearCorridor: (id) => guard(async () => { await telematics.clearRouteCorridor({ id }); }),
  };
}
