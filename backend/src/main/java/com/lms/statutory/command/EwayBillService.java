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
package com.lms.statutory.command;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.identity.access.AccessSubject;
import com.lms.planning.api.ConsignmentLookupPort;
import com.lms.planning.api.ConsignmentLookupPort.ConsignmentRef;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import com.lms.statutory.api.EwayBillAction;
import com.lms.statutory.api.EwayBillAdapter;
import com.lms.statutory.api.EwayBillRequest;
import com.lms.statutory.api.EwayBillResponse;
import com.lms.statutory.api.EwayBillTripPartB;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Write side of the e-way bills (issue #231): generate or fetch a bill for a
 * consignment, record one keyed from the statutory portal, and act on a bill
 * (Part-B vehicle, extension, cancellation, change of vehicle).
 *
 * <p>Each command is three steps and holds no database connection across the
 * middle one. First a short transaction reads the bill and the unit's settings
 * and checks every rule that does not need the adapter. Then the adapter is
 * called on a worker with the unit's timeout, and the call is audited whatever
 * its outcome (action, consignment, outcome; never a credential). Last a short
 * transaction applies the answer to the bill conditionally on the version
 * read, with its audit entry in the same transaction, so a refusal changes
 * nothing and two people acting at once cannot both win.
 */
@Service
public class EwayBillService implements EwayBillTripPartB {

    /** Written on the audit trail of the consignment for every adapter call. */
    static final String CALL_AUDIT = "EWAY_BILL_ADAPTER_CALL";
    static final String BILL_TYPE = "EWAY_BILL";

    private final ConsignmentLookupPort consignments;
    private final EwayBillStore store;
    private final EwayBillSettings settings;
    private final EwayAdapters adapters;
    private final AuditTrail audit;
    private final TransactionTemplate tx;

    public EwayBillService(ConsignmentLookupPort consignments, EwayBillStore store, EwayBillSettings settings,
                           EwayAdapters adapters, AuditTrail audit, PlatformTransactionManager transactionManager) {
        this.consignments = consignments;
        this.store = store;
        this.settings = settings;
        this.adapters = adapters;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // -------------------------------------------------------- generate / fetch

    /**
     * Raises a bill for the consignment through the unit's adapter, or, with
     * {@code fetchOnly}, records the bill the statutory system already holds
     * for the document. The document is the consignment's reference unless
     * another is given; the distance defaults to the unit's setting.
     *
     * @return the bill's id
     */
    @PreAuthorize("hasAuthority('EWAY_BILL_MANAGE')")
    public UUID generate(UUID consignmentId, String documentRef, Integer distanceKm, String vehicleNo,
                         boolean fetchOnly) {
        if (distanceKm != null && distanceKm < 1) {
            throw new BusinessRuleViolationException("eway-distance-invalid", "The distance must be at least 1 km");
        }
        String vehicle = vehicleNo == null || vehicleNo.isBlank() ? null : EwayBillRules.requireVehicle(vehicleNo);

        record Plan(ConsignmentRef consignment, EwayBillSettings.Config config, EwayBillAdapter adapter,
                    int sequence) {
        }
        Plan plan = tx.execute(status -> {
            ConsignmentRef consignment = consignments.find(consignmentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Consignment", consignmentId));
            if (!consignment.isOpen()) {
                throw new BusinessRuleViolationException("eway-consignment-closed",
                        "Consignment " + consignment.trackingRef() + " is " + consignment.status()
                                + "; no bill can be raised for it");
            }
            EwayBillSettings.Config config = settings.forUnit(consignment.orgUnitId());
            EwayBillAdapter adapter = adapterFor(config);
            store.liveOf(consignmentId).ifPresent(live -> {
                throw new BusinessRuleViolationException("eway-bill-already-live",
                        "Consignment " + consignment.trackingRef() + " already has e-way bill " + live.billNo());
            });
            return new Plan(consignment, config, adapter, store.countFor(consignmentId));
        });

        Instant now = Instant.now();
        String document = documentRef == null || documentRef.isBlank() ? plan.consignment().trackingRef()
                : documentRef.trim();
        int distance = distanceKm == null ? plan.config().defaultDistanceKm() : distanceKm;
        EwayBillAction action = fetchOnly ? EwayBillAction.FETCH : EwayBillAction.GENERATE;
        EwayBillResponse answer = call(plan.adapter(), plan.config(), plan.consignment(), new EwayBillRequest(action,
                document, null, vehicle, null, distance, plan.config().kmPerDay(), plan.config().extensionHours(),
                null, plan.config().gstin(), EwayBillRules.requireReference(plan.config().credentialRef()),
                plan.sequence(), now));
        if (answer.billNo() == null || answer.billNo().isBlank() || answer.validUntil() == null) {
            throw new BusinessRuleViolationException("eway-adapter-failed",
                    "The e-way bill system accepted the request but gave no bill number or validity");
        }
        Instant generatedAt = answer.generatedAt() == null ? now : answer.generatedAt();
        String source = plan.adapter().code();

        return tx.execute(status -> {
            store.liveOf(consignmentId).ifPresent(live -> {
                throw new BusinessRuleViolationException("eway-bill-already-live",
                        "Consignment " + plan.consignment().trackingRef() + " already has e-way bill "
                                + live.billNo());
            });
            requireFreeNumber(answer.billNo());
            UUID id = UUID.randomUUID();
            store.insert(id, TenantContext.requireTenantId(), plan.consignment().orgUnitId(), consignmentId,
                    answer.billNo(), generatedAt, answer.validUntil(), answer.vehicleNo(), distance, source,
                    actor());
            audit.recordAtomically(fetchOnly ? "EWAY_BILL_FETCHED" : "EWAY_BILL_GENERATED", BILL_TYPE, id, null,
                    snapshot(plan.consignment().trackingRef(), answer.billNo(), EwayBillStatus.ACTIVE,
                            answer.validUntil(), answer.vehicleNo(), "source", source));
            return id;
        });
    }

    /**
     * Records a bill that was raised on the statutory portal by hand. Needs no
     * adapter, so a unit that requires a bill at dispatch can satisfy the gate
     * without one.
     *
     * @param generatedAt when it was generated; now when null
     * @return the bill's id
     */
    @PreAuthorize("hasAuthority('EWAY_BILL_MANAGE')")
    public UUID record(UUID consignmentId, String billNo, Instant generatedAt, Instant validUntil, String vehicleNo,
                       Integer distanceKm) {
        String number = billNo == null ? "" : billNo.trim();
        if (number.isEmpty() || number.length() > 40) {
            throw new BusinessRuleViolationException("eway-bill-number-invalid",
                    "A bill number is required and is at most 40 characters");
        }
        String vehicle = vehicleNo == null || vehicleNo.isBlank() ? null : EwayBillRules.requireVehicle(vehicleNo);
        if (distanceKm != null && distanceKm < 1) {
            throw new BusinessRuleViolationException("eway-distance-invalid", "The distance must be at least 1 km");
        }
        Instant now = Instant.now();
        Instant generated = generatedAt == null ? now : generatedAt;
        if (validUntil == null || !validUntil.isAfter(generated) || generated.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw new BusinessRuleViolationException("eway-validity-invalid",
                    "The validity must come after the generation time, which cannot be in the future");
        }
        if (!validUntil.isAfter(now)) {
            throw new BusinessRuleViolationException("eway-bill-already-expired",
                    "The bill's validity has already passed; there is nothing to record");
        }
        return tx.execute(status -> {
            ConsignmentRef consignment = consignments.find(consignmentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Consignment", consignmentId));
            if (!consignment.isOpen()) {
                throw new BusinessRuleViolationException("eway-consignment-closed",
                        "Consignment " + consignment.trackingRef() + " is " + consignment.status()
                                + "; no bill can be recorded for it");
            }
            store.liveOf(consignmentId).ifPresent(live -> {
                throw new BusinessRuleViolationException("eway-bill-already-live",
                        "Consignment " + consignment.trackingRef() + " already has e-way bill " + live.billNo());
            });
            requireFreeNumber(number);
            UUID id = UUID.randomUUID();
            store.insert(id, TenantContext.requireTenantId(), consignment.orgUnitId(), consignmentId, number,
                    generated, validUntil, vehicle, distanceKm, "MANUAL", actor());
            audit.recordAtomically("EWAY_BILL_RECORDED", BILL_TYPE, id, null,
                    snapshot(consignment.trackingRef(), number, EwayBillStatus.ACTIVE, validUntil, vehicle,
                            "source", "MANUAL"));
            return id;
        });
    }

    // --------------------------------------------------------------- actions

    /**
     * Applies a named action to a bill. A name that is not an action is
     * refused with {@code eway-action-unknown}; GENERATE and FETCH are
     * actions of the consignment, not of a bill, and are refused as not
     * applicable. EXTEND, CANCEL and CHANGE_VEHICLE need a reason.
     */
    @PreAuthorize("hasAuthority('EWAY_BILL_MANAGE')")
    public void act(UUID billId, String actionName, String reason, String vehicleNo) {
        EwayBillAction action = EwayBillAction.parse(actionName).orElseThrow(() ->
                new BusinessRuleViolationException("eway-action-unknown", "Unknown e-way bill action '"
                        + shorten(actionName) + "'; expected one of " + List.of(EwayBillAction.values())));
        apply(billId, action, reason, vehicleNo);
    }

    /** Updates the vehicle of every live bill on the load's consignments, one outcome each. */
    @Override
    @PreAuthorize("hasAuthority('EWAY_BILL_MANAGE')")
    public List<ItemOutcome> updateVehicle(UUID loadId, String vehicleNo) {
        String vehicle = EwayBillRules.requireVehicle(vehicleNo);
        record Item(ConsignmentRef consignment, EwayBillStore.Bill bill) {
        }
        List<Item> items = tx.execute(status -> {
            List<ConsignmentRef> refs = consignments.onLoad(loadId);
            Map<UUID, EwayBillStore.Bill> live = store.liveOf(refs.stream().map(ConsignmentRef::id).toList());
            return refs.stream().map(ref -> new Item(ref, live.get(ref.id()))).toList();
        });
        List<ItemOutcome> outcomes = new ArrayList<>();
        for (Item item : items) {
            ConsignmentRef ref = item.consignment();
            if (item.bill() == null) {
                outcomes.add(new ItemOutcome(ref.id(), ref.trackingRef(), null, null, "SKIPPED", "eway-bill-missing",
                        "Consignment " + ref.trackingRef() + " has no live e-way bill"));
                continue;
            }
            EwayBillStore.Bill bill = item.bill();
            try {
                apply(bill.id(), EwayBillAction.PART_B, null, vehicle);
                outcomes.add(new ItemOutcome(ref.id(), ref.trackingRef(), bill.id(), bill.billNo(), "UPDATED", null,
                        null));
            } catch (BusinessRuleViolationException refusal) {
                outcomes.add(new ItemOutcome(ref.id(), ref.trackingRef(), bill.id(), bill.billNo(),
                        outcomeOf(refusal.code()), refusal.code(), refusal.getMessage()));
            }
        }
        return outcomes;
    }

    private static String outcomeOf(String code) {
        if ("eway-bill-disabled".equals(code)) {
            return "SKIPPED";
        }
        return code.startsWith("eway-adapter") ? "ERROR" : "REFUSED";
    }

    private void apply(UUID billId, EwayBillAction action, String reasonText, String vehicleText) {
        boolean needsReason = action == EwayBillAction.EXTEND || action == EwayBillAction.CANCEL
                || action == EwayBillAction.CHANGE_VEHICLE;
        boolean needsVehicle = action == EwayBillAction.PART_B || action == EwayBillAction.CHANGE_VEHICLE;
        String reason = needsReason ? EwayBillRules.requireReason(reasonText) : null;
        String vehicle = needsVehicle ? EwayBillRules.requireVehicle(vehicleText) : null;
        Instant now = Instant.now();

        record Plan(EwayBillStore.Bill bill, ConsignmentRef consignment, EwayBillSettings.Config config,
                    EwayBillAdapter adapter) {
        }
        Plan plan = tx.execute(status -> {
            EwayBillStore.Bill bill = store.find(billId)
                    .orElseThrow(() -> new ResourceNotFoundException("E-way bill", billId));
            EwayBillSettings.Config config = settings.forUnit(bill.orgUnitId());
            EwayBillAdapter adapter = adapterFor(config);
            EwayBillRules.requireAllowed(action, bill.status(), bill.generatedAt(), bill.validUntil(),
                    bill.vehicleNo() != null, now, config.cancelWindowHours());
            if (action == EwayBillAction.CHANGE_VEHICLE && vehicle.equals(bill.vehicleNo())) {
                throw new BusinessRuleViolationException("eway-vehicle-unchanged",
                        "Vehicle " + vehicle + " is already on Part-B");
            }
            ConsignmentRef consignment = consignments.find(bill.consignmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Consignment", bill.consignmentId()));
            return new Plan(bill, consignment, config, adapter);
        });

        EwayBillStore.Bill bill = plan.bill();
        EwayBillResponse answer = call(plan.adapter(), plan.config(), plan.consignment(), new EwayBillRequest(action,
                plan.consignment().trackingRef(), bill.billNo(), vehicle == null ? bill.vehicleNo() : vehicle, reason,
                bill.distanceKm(), plan.config().kmPerDay(), plan.config().extensionHours(), bill.validUntil(),
                plan.config().gstin(), EwayBillRules.requireReference(plan.config().credentialRef()), 0, now));
        if (action == EwayBillAction.EXTEND
                && (answer.validUntil() == null || !answer.validUntil().isAfter(bill.validUntil()))) {
            throw new BusinessRuleViolationException("eway-adapter-failed",
                    "The e-way bill system did not extend the validity");
        }

        tx.executeWithoutResult(status -> {
            EwayBillStore.Bill fresh = store.find(billId)
                    .orElseThrow(() -> new ResourceNotFoundException("E-way bill", billId));
            if (fresh.version() != bill.version()) {
                throw EwayBillStore.stale(billId);
            }
            String tracking = plan.consignment().trackingRef();
            Json before = snapshot(tracking, fresh.billNo(), fresh.status(), fresh.validUntil(), fresh.vehicleNo());
            switch (action) {
                case PART_B -> {
                    store.setVehicle(fresh, vehicle, now);
                    audit.recordAtomically("EWAY_BILL_PART_B_UPDATED", BILL_TYPE, billId, before,
                            snapshot(tracking, fresh.billNo(), fresh.status(), fresh.validUntil(), vehicle));
                }
                case CHANGE_VEHICLE -> {
                    store.setVehicle(fresh, vehicle, now);
                    audit.recordAtomically("EWAY_BILL_VEHICLE_CHANGED", BILL_TYPE, billId, before,
                            snapshot(tracking, fresh.billNo(), fresh.status(), fresh.validUntil(), vehicle,
                                    "reason", reason));
                }
                case EXTEND -> {
                    store.extend(fresh, answer.validUntil());
                    audit.recordAtomically("EWAY_BILL_EXTENDED", BILL_TYPE, billId, before,
                            snapshot(tracking, fresh.billNo(), EwayBillStatus.EXTENDED, answer.validUntil(),
                                    fresh.vehicleNo(), "reason", reason));
                }
                case CANCEL -> {
                    store.cancel(fresh, reason, now);
                    audit.recordAtomically("EWAY_BILL_CANCELLED", BILL_TYPE, billId, before,
                            snapshot(tracking, fresh.billNo(), EwayBillStatus.CANCELLED, fresh.validUntil(),
                                    fresh.vehicleNo(), "reason", reason));
                }
                default -> throw new IllegalStateException("Unexpected action " + action);
            }
        });
    }

    // --------------------------------------------------------------- helpers

    /** The adapter the unit has chosen, or the refusal that the feature is off or the adapter is missing. */
    private EwayBillAdapter adapterFor(EwayBillSettings.Config config) {
        if (!config.enabled()) {
            throw new BusinessRuleViolationException("eway-bill-disabled",
                    "E-way bills are not enabled for this unit; set statutory.ewayBill.adapter");
        }
        return adapters.find(config.adapter()).orElseThrow(() -> new BusinessRuleViolationException(
                "eway-adapter-unavailable", "No e-way bill adapter named " + config.adapter() + " is installed"));
    }

    /**
     * One audited call. The audit entry is written in a transaction of its own
     * (there is none open here) so that a refusal or a timeout is on the
     * record as well as a success. A refusal or failure then becomes the
     * problem the caller sees.
     */
    private EwayBillResponse call(EwayBillAdapter adapter, EwayBillSettings.Config config, ConsignmentRef consignment,
                                  EwayBillRequest request) {
        EwayBillResponse answer;
        try {
            answer = adapters.call(adapter, request, config.timeout());
        } catch (EwayAdapters.CallFailed failed) {
            auditCall(adapter, request, consignment, "ERROR", failed.code(), null);
            throw new BusinessRuleViolationException(failed.code(), failed.getMessage());
        }
        if (!answer.accepted()) {
            auditCall(adapter, request, consignment, "REFUSED", answer.errorCode(), null);
            throw new BusinessRuleViolationException("eway-bill-refused", "The e-way bill system refused to "
                    + request.action().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ": "
                    + (answer.errorMessage() == null ? answer.errorCode() : answer.errorMessage()));
        }
        auditCall(adapter, request, consignment, "OK", null, answer.billNo());
        return answer;
    }

    private void auditCall(EwayBillAdapter adapter, EwayBillRequest request, ConsignmentRef consignment,
                           String outcome, String code, String billNo) {
        // Nothing of the credentials goes in: not the reference, not the registration.
        audit.record(CALL_AUDIT, "CONSIGNMENT", consignment.id(), null,
                Json.object("action", request.action().name(), "adapter", adapter.code(), "outcome", outcome,
                        "code", code, "consignment", consignment.trackingRef(),
                        "billNo", billNo == null ? request.billNo() : billNo));
    }

    private void requireFreeNumber(String billNo) {
        if (store.numberTaken(billNo)) {
            throw new BusinessRuleViolationException("eway-bill-number-taken",
                    "E-way bill number " + billNo + " is already recorded");
        }
    }

    private static Json snapshot(String consignment, String billNo, EwayBillStatus status, Instant validUntil,
                                 String vehicleNo, String... extra) {
        List<String> pairs = new ArrayList<>(List.of("consignment", consignment, "billNo", billNo, "status",
                status.name(), "validUntil", String.valueOf(validUntil)));
        pairs.add("vehicleNo");
        pairs.add(vehicleNo);
        pairs.addAll(List.of(extra));
        return Json.object(pairs.toArray(String[]::new));
    }

    private static String shorten(String text) {
        String clean = text == null ? "" : text.replaceAll("[^A-Za-z0-9_ -]", "?");
        return clean.length() > 40 ? clean.substring(0, 40) + "..." : clean;
    }

    private static UUID actor() {
        try {
            return UUID.fromString(AccessSubject.current().userId());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
