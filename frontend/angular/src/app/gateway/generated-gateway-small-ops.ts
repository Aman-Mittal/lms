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

import { inject } from '@angular/core';
import { NumberYearCode as ApiYearCode, ApprovalExpiry as ApiExpiry } from '../api';
import type { ApprovalPolicyView } from '../api';
import { OrderLineDraft } from './lms-gateway';
import type {
  ApprovalPolicyDraft, ApprovalPolicyRow, CorridorDraft, CorridorRow, EwayRecordDraft, LineCancellationView,
  OrderCloseSettingsView, SeriesRevisionDraft, SeriesRevisionState,
} from './small-ops-types';
import { GeneratedGatewayMilk } from './generated-gateway-milk';
import { blankAsAbsent, instant, instantOf, lineRequest, text } from './generated-gateway-helpers';
import { corridorPoints, permissionCodes } from '../../../../shared/small-ops';

function policyOf(p: ApprovalPolicyView): ApprovalPolicyRow {
  return {
    subjectType: text(p.subjectType), required: p.required, mandatory: p.mandatory,
    decidePermissions: p.decidePermissions ?? [], ordered: p.ordered, allowSelfApproval: p.allowSelfApproval,
    dueMinutes: text(p.dueMinutes), onExpiry: String(p.onExpiry) as ApprovalPolicyRow['onExpiry'],
  };
}

/** Approval policies, number series revision, order lines and close settings, recording an e-way bill, corridors. */
export abstract class GeneratedGatewaySmallOps extends GeneratedGatewayMilk {
  approvalPolicies(): Promise<ApprovalPolicyRow[]> {
    return this.call(this.approvalsApi.listApprovalPolicies(), (list) => list.map(policyOf));
  }

  setApprovalPolicy(subjectType: string, draft: ApprovalPolicyDraft): Promise<ApprovalPolicyRow> {
    const due = draft.dueMinutes.trim();
    return this.call(this.approvalsApi.setApprovalPolicy({
      subjectType,
      approvalPolicyRequest: {
        required: draft.required, decidePermissions: permissionCodes(draft.decidePermissions),
        ordered: draft.ordered, allowSelfApproval: draft.allowSelfApproval,
        dueMinutes: due ? Number(due) : undefined, onExpiry: draft.onExpiry as ApiExpiry,
      },
    }), policyOf);
  }

  resetApprovalPolicy(subjectType: string): Promise<ApprovalPolicyRow> {
    return this.call(this.approvalsApi.resetApprovalPolicy({ subjectType }), policyOf);
  }

  numberSeriesRevisionState(id: string): Promise<SeriesRevisionState> {
    return this.call(this.numbering.getNumberSeries({ id }), (n) => ({
      version: text(n.version), issuedThisYear: text(n.issuedThisYear),
      draft: {
        prefix: text(n.prefix), yearCode: String(n.yearCode), separator: text(n.separator),
        counterLength: text(n.counterLength), yearStartMonth: text(n.yearStartMonth), timeZone: text(n.timeZone),
        reuseReleased: n.reuseReleased, description: text(n.description),
      },
    }));
  }

  reviseNumberSeries(id: string, version: string, draft: SeriesRevisionDraft): Promise<void> {
    return this.call(this.numbering.reviseNumberSeries({
      id, ifMatch: version,
      reviseNumberSeriesRequest: {
        prefix: draft.prefix.trim(), yearCode: draft.yearCode as ApiYearCode, separator: draft.separator,
        counterLength: Number(draft.counterLength), yearStartMonth: Number(draft.yearStartMonth),
        timeZone: blankAsAbsent(draft.timeZone), reuseReleased: draft.reuseReleased,
        description: draft.description.trim(),
      },
    }), () => undefined);
  }

  orderCloseSettings(): Promise<OrderCloseSettingsView> {
    return this.call(this.orders.getOrderCloseSettings(), (s) => ({
      tolerancePercent: text(s.tolerancePercent ?? 0), autoFulfil: s.autoFulfil === true, customised: s.customised === true,
    }));
  }

  setOrderCloseSettings(tolerancePercent: string, autoFulfil: boolean): Promise<OrderCloseSettingsView> {
    return this.call(this.orders.setOrderCloseSettings({
      setOrderCloseSettingsRequest: { tolerancePercent: Number(tolerancePercent), autoFulfil },
    }), (s) => ({
      tolerancePercent: text(s.tolerancePercent ?? 0), autoFulfil: s.autoFulfil === true, customised: s.customised === true,
    }));
  }

  addOrderLine(orderId: string, line: OrderLineDraft): Promise<string> {
    return this.call(this.orders.addOrderLine({ id: orderId, addOrderLineRequest: lineRequest(line) }), (r) => text(r.id));
  }

  cancelOrderLine(orderId: string, version: string, lineId: string, reason: string): Promise<LineCancellationView> {
    return this.call(this.orders.cancelOrderLine({
      id: orderId, lineId, ifMatch: version, cancelOrderRequest: { reason: reason.trim() || undefined },
    }), (r) => ({
      cancelledConsignments: r.cancelledConsignmentIds?.length ?? 0, releasedLines: r.releasedLineIds?.length ?? 0,
    }));
  }

  recordEwayBill(draft: EwayRecordDraft): Promise<string> {
    return this.call(this.ewayApi.recordEwayBill({
      consignmentId: draft.consignmentId.trim(),
      recordEwayBillRequest: {
        billNo: draft.billNo.trim(), validUntil: instantOf(draft.validUntil),
        generatedAt: draft.generatedAt.trim() ? instantOf(draft.generatedAt) : undefined,
        vehicleNo: blankAsAbsent(draft.vehicleNo), distanceKm: draft.distanceKm.trim() ? Number(draft.distanceKm) : undefined,
      },
    }), (r) => text(r.id));
  }

  corridors(): Promise<CorridorRow[]> {
    return this.call(this.telematics.listRouteCorridors({ limit: 100 }), (s) => s.items.map((c) => ({
      id: text(c.id), name: text(c.name), origin: text(c.originTerminalCode), destination: text(c.destinationTerminalCode),
      pointCount: text(c.pointCount), widthMetres: text(c.widthMetres), updatedAt: instant(c.updatedAt as unknown as string),
    })));
  }

  importCorridor(draft: CorridorDraft): Promise<string> {
    const width = draft.widthMetres.trim();
    return this.call(this.telematics.importRouteCorridor({
      routeCorridorRequest: {
        originTerminalId: draft.originTerminalId.trim(), destinationTerminalId: draft.destinationTerminalId.trim(),
        name: draft.name.trim(), points: corridorPoints(draft.points), widthMetres: width ? Number(width) : undefined,
      },
    }), (r) => text(r.id));
  }

  clearCorridor(id: string): Promise<void> {
    return this.call(this.telematics.clearRouteCorridor({ id }), () => undefined);
  }
}
