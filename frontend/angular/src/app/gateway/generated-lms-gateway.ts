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

import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, Observable } from 'rxjs';
import {
  AllocationRule, AllocationRuleAction, AllocationRuleRequest, AllocationRuleRequestMaterialClassEnum,
  AllocationRulesService, AuthenticationService, ControlTowerService, CustomFieldDefinition, CustomFieldEntityType,
  ClaimStatus as ApiClaimStatus, ClaimType as ApiClaimType, CustomFieldsService, CustomFieldType,
  DeliveryAndClaimsService, DriversService, OrdersService, PartnersService,
  PlanningService, PublicTrackingService, RegisterPartnerRequestPartnerTypeEnum, SettlementService, SourcingService,
  TerminalsService, TrackingLinksService, TripsService, VehiclesService,
} from '../api';
import {
  AllocationRuleView, AllocationSummary, ApiProblem, Choice, ClaimDraft, ClaimMove, ControlTowerSummary, Credentials, CustomFieldKind, CustomValues,
  DeliveryProofView, FieldChanges, FieldDefinition, FieldDraft, FieldType, IssuedTrackingLink, LmsGateway,
  OtpIssued, PartnerDraft, ProofDraft, PublicTrackingView, ResourceKey, Row, RuleAction, RuleDraft, RuleEvaluation,
  SignedIn, TrackingLinkView,
} from './lms-gateway';

/** A display cell: the contract's optional fields become an empty string. */
function text(v: unknown): string {
  if (v === null || v === undefined) {
    return '';
  }
  if (v instanceof Date) {
    const iso = v.toISOString();
    return iso.endsWith('T00:00:00.000Z') ? iso.slice(0, 10) : iso.slice(0, 16).replace('T', ' ');
  }
  return String(v);
}

function definitionOf(d: CustomFieldDefinition): FieldDefinition {
  return {
    id: d.id ?? '',
    kind: d.entityType as unknown as CustomFieldKind,
    key: d.key ?? '',
    label: d.label ?? '',
    type: d.fieldType as unknown as FieldType,
    required: d.required ?? false,
    options: d.options ?? [],
    active: d.active ?? true,
  };
}

function ruleOf(r: AllocationRule): AllocationRuleView {
  return {
    id: text(r.id), name: text(r.name), priority: r.priority ?? 0, active: r.active ?? true,
    originTerminalId: text(r.originTerminalId), destinationTerminalId: text(r.destinationTerminalId),
    minWeightKg: text(r.minWeightKg), maxWeightKg: text(r.maxWeightKg),
    materialClass: text(r.materialClass), action: (text(r.action) || 'PREFER') as RuleAction,
    vendors: (r.vendors ?? []).map((v) => ({ id: text(v.vendorPartnerId), label: text(v.vendorName) })),
  };
}

/** The contract's request: a condition left out is sent as absent, not as an empty string. */
function ruleRequest(draft: RuleDraft): AllocationRuleRequest {
  return {
    name: draft.name, priority: draft.priority, active: draft.active,
    originTerminalId: draft.originTerminalId || undefined,
    destinationTerminalId: draft.destinationTerminalId || undefined,
    minWeightKg: draft.minWeightKg, maxWeightKg: draft.maxWeightKg,
    materialClass: (draft.materialClass || undefined) as unknown as AllocationRuleRequestMaterialClassEnum | undefined,
    action: draft.action as unknown as AllocationRuleAction,
    vendorPartnerIds: draft.vendorIds,
  };
}

/** The adapter: the only file in the Angular app (besides app config) that knows the generated client. */
@Injectable()
export class GeneratedLmsGateway extends LmsGateway {
  private readonly auth = inject(AuthenticationService);
  private readonly controlTower = inject(ControlTowerService);
  private readonly partners = inject(PartnersService);
  private readonly vehicles = inject(VehiclesService);
  private readonly drivers = inject(DriversService);
  private readonly terminals = inject(TerminalsService);
  private readonly orders = inject(OrdersService);
  private readonly loads = inject(PlanningService);
  private readonly trips = inject(TripsService);
  private readonly freightBills = inject(SettlementService);
  private readonly customFields = inject(CustomFieldsService);
  private readonly delivery = inject(DeliveryAndClaimsService);
  private readonly links = inject(TrackingLinksService);
  private readonly publicRead = inject(PublicTrackingService);
  private readonly rules = inject(AllocationRulesService);
  private readonly sourcing = inject(SourcingService);

  private readonly listers: Record<ResourceKey, () => Promise<Row[]>> = {
    partners: () => this.call(this.partners.listPartners({ limit: 50 }), (s) => s.items.map((p) => ({
      id: text(p.id), code: text(p.code), legalName: text(p.legalName),
      partnerType: text(p.partnerType), status: text(p.status) }))),
    vehicles: () => this.call(this.vehicles.listVehicles({ limit: 50 }), (s) => s.items.map((v) => ({
      id: text(v.id), registrationNo: text(v.registrationNo), vehicleType: text(v.vehicleType),
      status: text(v.status), payloadCapacityKg: text(v.payloadCapacityKg) }))),
    drivers: () => this.call(this.drivers.listDrivers({ limit: 50 }), (s) => s.items.map((d) => ({
      id: text(d.id), fullName: text(d.fullName), licenceNo: text(d.licenceNo),
      licenceExpiresOn: text(d.licenceExpiresOn), status: text(d.status) }))),
    terminals: () => this.call(this.terminals.listTerminals({ limit: 50 }), (s) => s.items.map((t) => ({
      id: text(t.id), code: text(t.code), name: text(t.name),
      functionalCategory: text(t.functionalCategory), dockCount: text(t.dockCount) }))),
    orders: () => this.call(this.orders.listOrders({ limit: 50 }), (s) => s.items.map((o) => ({
      id: text(o.id), orderNo: text(o.orderNo), status: text(o.status),
      customerName: text(o.customerName), originTerminalCode: text(o.originTerminalCode) }))),
    loads: () => this.call(this.loads.listLoads({ limit: 50 }), (s) => s.items.map((l) => ({
      id: text(l.id), loadNo: text(l.loadNo), status: text(l.status),
      vehicleRegistrationNo: text(l.vehicleRegistrationNo),
      weightUtilisationPct: text(l.weightUtilisationPct) }))),
    trips: () => this.call(this.trips.listTrips({ limit: 50 }), (s) => s.items.map((t) => ({
      id: text(t.id), tripNo: text(t.tripNo), status: text(t.status), vendorName: text(t.vendorName),
      driverName: text(t.driverName) }))),
    'freight-bills': () => this.call(this.freightBills.listFreightBills({ limit: 50 }), (s) => s.items.map((b) => ({
      id: text(b.id), billNo: text(b.billNo), status: text(b.status), vendorName: text(b.vendorName),
      claimedAmount: text(b.claimedAmount), varianceAmount: text(b.varianceAmount) }))),
    exceptions: () => this.call(this.controlTower.listExceptions({ limit: 50 }), (s) => s.items.map((e) => ({
      id: text(e.id), ticketNo: text(e.ticketNo), severity: text(e.severity), status: text(e.status),
      category: text(e.category), description: text(e.description) }))),
    claims: () => this.call(this.delivery.listClaims({ limit: 50 }), (s) => s.items.map((c) => ({
      id: text(c.id), claimNo: text(c.claimNo), claimType: text(c.claimType), amount: text(c.amount),
      status: text(c.status), consignmentId: text(c.consignmentId),
      approvedAmount: text(c.approvedAmount), description: text(c.description),
      resolutionNote: text(c.resolutionNote) }))),
  };

  signIn(credentials: Credentials): Promise<SignedIn> {
    return this.call(this.auth.login({ loginRequest: credentials }), (r) => ({
      token: r.accessToken, orgUnitId: r.orgUnitId ?? '', permissions: r.permissions ?? [] }));
  }

  summary(): Promise<ControlTowerSummary> {
    return this.call(this.controlTower.summariseExceptions(), (s) => ({
      openP1: s.openP1 ?? 0, openP2: s.openP2 ?? 0, openP3: s.openP3 ?? 0,
      unassigned: s.unassigned ?? 0, settlementHolds: s.settlementHolds ?? 0,
      openWithoutTrip: s.openWithoutTrip ?? 0 }));
  }

  list(resource: ResourceKey): Promise<Row[]> {
    return this.listers[resource]();
  }

  registerPartner(orgUnitId: string, draft: PartnerDraft): Promise<void> {
    return this.call(this.partners.registerPartner({
      registerPartnerRequest: {
        orgUnitId, code: draft.code, legalName: draft.legalName,
        partnerType: draft.partnerType as RegisterPartnerRequestPartnerTypeEnum,
      },
    }), () => undefined);
  }

  listFields(kind?: CustomFieldKind): Promise<FieldDefinition[]> {
    return this.call(
      this.customFields.listCustomFieldDefinitions({
        entityType: kind as unknown as CustomFieldEntityType | undefined,
      }),
      (slice) => slice.items.map(definitionOf));
  }

  defineField(draft: FieldDraft): Promise<void> {
    return this.call(this.customFields.defineCustomField({
      defineCustomFieldRequest: {
        entityType: draft.kind as unknown as CustomFieldEntityType, key: draft.key,
        label: draft.label, fieldType: draft.type as unknown as CustomFieldType,
        required: draft.required, options: draft.options,
      },
    }), () => undefined);
  }

  reviseField(id: string, changes: FieldChanges): Promise<void> {
    return this.call(this.customFields.reviseCustomField({
      id,
      reviseCustomFieldRequest: {
        label: changes.label, required: changes.required, options: changes.options,
        active: changes.active,
      },
    }), () => undefined);
  }

  customValues(kind: CustomFieldKind, recordId: string): Promise<CustomValues> {
    return this.call(this.customFields.getCustomFieldValues({
      entityType: kind as unknown as CustomFieldEntityType, entityId: recordId,
    }), (v) => v.values as CustomValues);
  }

  saveCustomValues(kind: CustomFieldKind, recordId: string, values: CustomValues): Promise<CustomValues> {
    return this.call(this.customFields.setCustomFieldValues({
      entityType: kind as unknown as CustomFieldEntityType, entityId: recordId,
      customFieldValues: { values },
    }), (v) => v.values as CustomValues);
  }

  raiseClaim(draft: ClaimDraft): Promise<string> {
    return this.call(this.delivery.raiseClaim({
      raiseClaimRequest: {
        consignmentId: draft.consignmentId, claimType: draft.claimType as unknown as ApiClaimType,
        amount: draft.amount, description: draft.description,
      },
    }), (made) => made.id ?? '');
  }

  transitionClaim(id: string, move: ClaimMove): Promise<void> {
    return this.call(this.delivery.transitionClaim({
      id,
      transitionClaimRequest: {
        target: move.target as unknown as ApiClaimStatus, approvedAmount: move.approvedAmount,
        resolutionNote: move.resolutionNote,
      },
    }), () => undefined);
  }

  issueDeliveryOtp(consignmentId: string): Promise<OtpIssued> {
    return this.call(this.delivery.issueDeliveryOtp({ consignmentId }),
      (issued) => ({ expiresAt: text(issued.expiresAt), otp: issued.otp }));
  }

  recordDeliveryProof(consignmentId: string, draft: ProofDraft): Promise<void> {
    return this.call(this.delivery.recordDeliveryProof({
      consignmentId,
      recordDeliveryProofRequest: {
        otp: draft.otp, recipientName: draft.recipientName, photoRef: draft.photoRef || undefined,
      },
    }), () => undefined);
  }

  async deliveryProof(consignmentId: string): Promise<DeliveryProofView | null> {
    try {
      return await this.call(this.delivery.getDeliveryProof({ consignmentId }), (p) => ({
        consignmentId: p.consignmentId ?? consignmentId,
        status: (p.status as unknown as string) === 'CONFIRMED' ? 'CONFIRMED' as const : 'OTP_ISSUED' as const,
        recipientName: text(p.recipientName), photoRef: text(p.photoRef),
        deliveredAt: text(p.deliveredAt), otpExpiresAt: text(p.otpExpiresAt),
      }));
    } catch (e) {
      if (e instanceof ApiProblem && e.status === 404) {
        return null;
      }
      throw e;
    }
  }

  issueTrackingLink(consignmentId: string, expiresInDays?: number): Promise<IssuedTrackingLink> {
    return this.call(this.links.issueTrackingLink({
      consignmentId, issueTrackingLinkRequest: expiresInDays ? { expiresInDays } : undefined,
    }), (issued) => ({ id: issued.id, token: issued.token, expiresAt: text(issued.expiresAt) }));
  }

  trackingLinks(consignmentId: string): Promise<TrackingLinkView[]> {
    return this.call(this.links.listTrackingLinks({ consignmentId }), (list) => list.items.map((l) => ({
      id: text(l.id), createdAt: text(l.createdAt), expiresAt: text(l.expiresAt),
      revokedAt: text(l.revokedAt), status: ((l.status as unknown as string) ?? 'ACTIVE') as TrackingLinkView['status'],
    })));
  }

  revokeTrackingLink(id: string): Promise<void> {
    return this.call(this.links.revokeTrackingLink({ id }), () => undefined);
  }

  async publicTracking(token: string): Promise<PublicTrackingView | null> {
    try {
      return await this.call(this.publicRead.getPublicTracking({ token }), (t) => ({
        reference: text(t.reference), status: text(t.status), originName: text(t.originName),
        destinationName: text(t.destinationName), requestedDeliveryAt: text(t.requestedDeliveryAt),
        deliveredAt: text(t.deliveredAt), proofRecorded: t.proofRecorded ?? false,
        position: t.position
          ? { lat: text(t.position.lat), lon: text(t.position.lon), recordedAt: text(t.position.recordedAt) }
          : null,
      }));
    } catch (e) {
      if (e instanceof ApiProblem && e.status === 404) {
        return null;
      }
      throw e;
    }
  }

  allocationRules(): Promise<AllocationRuleView[]> {
    return this.call(this.rules.listAllocationRules({ limit: 200 }), (s) => s.items.map(ruleOf));
  }

  createAllocationRule(draft: RuleDraft): Promise<string> {
    return this.call(this.rules.createAllocationRule({ allocationRuleRequest: ruleRequest(draft) }), (made) => made.id ?? '');
  }

  reviseAllocationRule(id: string, draft: RuleDraft): Promise<void> {
    return this.call(this.rules.reviseAllocationRule({ id, allocationRuleRequest: ruleRequest(draft) }), () => undefined);
  }

  evaluateAllocation(loadId: string): Promise<RuleEvaluation> {
    return this.call(this.rules.evaluateAllocationRules({ allocationEvaluationRequest: { loadId } }), (e) => ({
      loadNo: text(e.loadNo), ruleName: text(e.matchedRuleName),
      action: (e.matchedRuleId ? text(e.action) : '') as RuleAction | '',
      nextVendor: text(e.nextVendor?.vendorName),
      candidates: (e.candidateOrder ?? []).map((v) => ({ id: text(v.vendorPartnerId), label: text(v.vendorName) })),
    }));
  }

  allocation(id: string): Promise<AllocationSummary> {
    return this.call(this.sourcing.getAllocation({ id }), (a) => ({
      id: text(a.id), loadNo: text(a.loadNo), status: text(a.status),
      appliedRuleName: text(a.appliedRuleName),
      appliedRuleAction: (a.appliedRuleId ? text(a.appliedRuleAction) : '') as RuleAction | '',
    }));
  }

  activeVendors(): Promise<Choice[]> {
    return this.call(this.partners.listPartners({ partnerType: 'VENDOR', status: 'ACTIVE', limit: 200 }),
      (s) => s.items.map((p) => ({ id: text(p.id), label: `${text(p.code)} · ${text(p.legalName)}` })));
  }

  terminalChoices(): Promise<Choice[]> {
    return this.call(this.terminals.listTerminals({ limit: 200 }),
      (s) => s.items.map((t) => ({ id: text(t.id), label: `${text(t.code)} · ${text(t.name)}` })));
  }

  /** Awaits a generated call, maps its result, and turns any failure into an ApiProblem. */
  private async call<T, R>(request: Observable<T>, map: (value: T) => R): Promise<R> {
    try {
      return map(await firstValueFrom(request));
    } catch (e) {
      if (e instanceof HttpErrorResponse) {
        const body = e.error as { detail?: string; errors?: Record<string, string> } | null;
        if (body?.errors) {
          throw new ApiProblem(Object.entries(body.errors).map(([k, v]) => `${k}: ${v}`).join('; '), e.status);
        }
        throw new ApiProblem(body?.detail ?? (e.status ? `${e.status} ${e.statusText}` : 'Could not reach the API. Try again.'), e.status);
      }
      throw e;
    }
  }
}
