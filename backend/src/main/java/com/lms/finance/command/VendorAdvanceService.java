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

package com.lms.finance.command;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.lms.approvals.api.ApprovalRequests;
import com.lms.approvals.api.ApprovalSubject;
import com.lms.approvals.api.ApprovalSubjectTypes;
import com.lms.approvals.api.ApprovalTicket;
import com.lms.execution.api.TripLookupPort;
import com.lms.finance.command.domain.AdvanceCap;
import com.lms.finance.command.domain.VendorAdvance;
import com.lms.finance.events.VendorAdvancePaid;
import com.lms.identity.numbering.DocumentNumbers;
import com.lms.identity.numbering.DocumentType;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.Settings;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Advances to a vendor against a trip (YRD-028 to 032): requested, approved
 * through the approvals module, paid with a reference, and deducted when the
 * vendor's invoice settles the trip.
 *
 * <p>The cap is the tenant's: all live advances on a trip together stay within
 * {@code finance.advance.maxPercentOfFreight} (resolved for the trip's unit and
 * vendor) of the trip's freight, which is the trip's freight bill or, before it
 * is billed, the platform's own price of the load ({@link AdvanceFreight}).
 * One advance per mode per trip while it is alive. Cancelling is possible only
 * before it is paid.
 */
@Service
public class VendorAdvanceService {

    /** The action an advance is approved as. */
    public static final String APPROVE_ACTION = "APPROVE";
    static final String AUDIT_TYPE = "VENDOR_ADVANCE";

    /** What a person asks for. */
    public record RequestCommand(UUID tripId, VendorAdvance.Mode mode, BigDecimal amount, String reason) {
    }

    /** The advance and, when it waits for a decision, the approval request. */
    public record Outcome(UUID id, ApprovalTicket approval) {
    }

    private final VendorAdvanceRepository advances;
    private final TripLookupPort trips;
    private final AdvanceFreight freight;
    private final ApprovalRequests approvals;
    private final Settings settings;
    private final DocumentNumbers numbers;
    private final TransitionAudit audit;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;

    public VendorAdvanceService(VendorAdvanceRepository advances, TripLookupPort trips, AdvanceFreight freight,
                                ApprovalRequests approvals, Settings settings, DocumentNumbers numbers,
                                TransitionAudit audit, ApplicationEventPublisher events, JdbcClient jdbc) {
        this.advances = advances;
        this.trips = trips;
        this.freight = freight;
        this.approvals = approvals;
        this.settings = settings;
        this.numbers = numbers;
        this.audit = audit;
        this.events = events;
        this.jdbc = jdbc;
    }

    @Transactional
    @PreAuthorize("hasAuthority('ADVANCE_REQUEST')")
    public Outcome request(RequestCommand command) {
        UUID tenantId = TenantContext.requireTenantId();
        TripLookupPort.TripSnapshot trip = trips.find(command.tripId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip", command.tripId()));
        if (trip.isCancelled()) {
            throw new BusinessRuleViolationException("advance-trip-cancelled",
                    "Trip " + trip.tripNo() + " is cancelled; no advance is paid against it");
        }
        if (trip.vendorPartnerId() == null) {
            throw new BusinessRuleViolationException("advance-trip-has-no-vendor",
                    "Trip " + trip.tripNo() + " runs on the tenant's own fleet; there is no vendor to advance");
        }
        TripLookupPort.TripScope scope = trips.scopeOf(command.tripId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip", command.tripId()));
        SettingContext context = SettingContext.ofOrgUnit(scope.orgUnitId()).withVendor(trip.vendorPartnerId());
        BigDecimal maxPercent = settings.effective(FinanceSettingDefinitions.ADVANCE_MAX_PERCENT_OF_FREIGHT, context);

        // Serialises the cap check and the insert for one trip: two requests that
        // each fit alone must not both be let through when together they do not.
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) held")
                .param("key", "vendor-advance:" + tenantId + ":" + command.tripId())
                .query(Integer.class)
                .single();

        AdvanceFreight.Reference reference = freight.of(trip).orElseThrow(() ->
                new BusinessRuleViolationException("advance-freight-unknown",
                        "Trip " + trip.tripNo() + " has no freight bill and no rate card prices its lane, so there "
                                + "is no freight to measure an advance against"));
        BigDecimal alreadyAdvanced = jdbc.sql("""
                        SELECT coalesce(sum(amount), 0) FROM vendor_advance
                         WHERE tenant_id = :tenantId AND trip_id = :tripId
                           AND status IN ('PENDING_APPROVAL', 'APPROVED', 'PAID', 'DEDUCTED')
                        """)
                .param("tenantId", tenantId)
                .param("tripId", command.tripId())
                .query(BigDecimal.class)
                .single();
        if (command.amount() != null && !AdvanceCap.fits(reference.amount(), maxPercent, alreadyAdvanced,
                command.amount())) {
            BigDecimal left = AdvanceCap.remaining(reference.amount(), maxPercent, alreadyAdvanced);
            throw new BusinessRuleViolationException("advance-exceeds-cap",
                    "At most " + left.toPlainString() + " more may be advanced on trip " + trip.tripNo() + ": "
                            + maxPercent.stripTrailingZeros().toPlainString() + "% of freight "
                            + reference.amount().toPlainString() + ", with " + alreadyAdvanced.toPlainString()
                            + " already advanced");
        }
        boolean modeTaken = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM vendor_advance
                                        WHERE tenant_id = :tenantId AND trip_id = :tripId AND mode = :mode
                                          AND status IN ('PENDING_APPROVAL', 'APPROVED', 'PAID', 'DEDUCTED'))
                        """)
                .param("tenantId", tenantId)
                .param("tripId", command.tripId())
                .param("mode", command.mode() == null ? "" : command.mode().name())
                .query(Boolean.class)
                .single());
        if (modeTaken) {
            throw modeTaken(trip.tripNo(), command.mode());
        }

        boolean needsApproval = approvals.isRequired(ApprovalSubjectTypes.VENDOR_ADVANCE, context);
        String number = numbers.next(DocumentType.VENDOR_ADVANCE, scope.orgUnitId())
                .orElse("ADV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        VendorAdvance raised;
        try {
            raised = advances.save(VendorAdvance.request(UUID.randomUUID(), tenantId, number, scope.orgUnitId(),
                    command.tripId(), trip.vendorPartnerId(), command.mode(), command.amount(), "INR",
                    command.reason(), reference.amount(), reference.source(), maxPercent, needsApproval,
                    Actors.currentUserId()));
        } catch (DuplicateKeyException race) {
            throw modeTaken(trip.tripNo(), command.mode());
        }
        if (!needsApproval) {
            audit.record("VENDOR_ADVANCE_REQUESTED", AUDIT_TYPE, raised.id(), null, snapshot(raised));
            return new Outcome(raised.id(), null);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("advanceNo", raised.advanceNo());
        payload.put("tripNo", trip.tripNo());
        payload.put("mode", raised.mode().name());
        payload.put("amount", raised.amount().toPlainString());
        payload.put("freight", raised.freightReference().toPlainString());
        payload.put("reason", raised.reason());
        ApprovalTicket ticket = approvals.request(
                new ApprovalSubject(ApprovalSubjectTypes.VENDOR_ADVANCE, raised.id(), scope.orgUnitId()), context,
                APPROVE_ACTION, payload, "Advance " + raised.amount().toPlainString() + " by "
                        + raised.mode() + " on trip " + trip.tripNo() + " (" + raised.advanceNo() + ")");
        advances.save(raised.withApproval(ticket.id()));
        audit.record("VENDOR_ADVANCE_REQUESTED", AUDIT_TYPE, raised.id(), null,
                snapshot(raised, "approvalId", ticket.id().toString()));
        return new Outcome(raised.id(), ticket);
    }

    /** Records that an approved advance was paid: its reference and the day. */
    @Transactional
    @PreAuthorize("hasAuthority('PAYABLES_MANAGE')")
    public void pay(UUID id, long expectedVersion, String reference, LocalDate paidOn) {
        VendorAdvance before = requireVersion(id, expectedVersion);
        VendorAdvance paid = advances.save(before.paid(reference, paidOn, Actors.currentUserId()));
        audit.record("VENDOR_ADVANCE_PAID", AUDIT_TYPE, id, snapshot(before),
                snapshot(paid, "reference", paid.paidReference()));
        events.publishEvent(new VendorAdvancePaid(paid.tenantId(), paid.id(), paid.advanceNo(), paid.tripId(),
                paid.vendorPartnerId(), paid.mode().name(), paid.amount(), paid.currency(), paid.paidReference(),
                paid.paidOn(), Instant.now()));
    }

    /** Cancels an advance that has not been paid, with the reason. */
    @Transactional
    @PreAuthorize("hasAuthority('ADVANCE_REQUEST')")
    public void cancel(UUID id, long expectedVersion, String reason) {
        VendorAdvance before = requireVersion(id, expectedVersion);
        VendorAdvance cancelled = before.cancelled(reason, Instant.now());
        if (before.status() == VendorAdvance.Status.PENDING_APPROVAL) {
            approvals.withdraw(ApprovalSubjectTypes.VENDOR_ADVANCE, id, APPROVE_ACTION, reason);
        }
        advances.save(cancelled);
        audit.record("VENDOR_ADVANCE_CANCELLED", AUDIT_TYPE, id, snapshot(before),
                snapshot(cancelled, "reason", cancelled.cancelReason()));
    }

    /** The decision came: approved. Called by the approval handler in the deciding transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyApproved(UUID id, UUID approvalId) {
        VendorAdvance pending = require(id);
        if (pending.status() != VendorAdvance.Status.PENDING_APPROVAL) {
            throw new BusinessRuleViolationException("advance-not-pending",
                    "Advance " + pending.advanceNo() + " is " + pending.status() + " and no longer waits for approval");
        }
        VendorAdvance approved = advances.save(pending.approved(Instant.now()));
        audit.record("VENDOR_ADVANCE_APPROVED", AUDIT_TYPE, id, snapshot(pending),
                snapshot(approved, "approvalId", String.valueOf(approvalId)));
    }

    /** Rejected, withdrawn or expired: the advance is closed and gives its mode back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closedWithoutApproval(UUID id, String approvalStatus) {
        VendorAdvance pending = advances.findById(id).orElse(null);
        if (pending == null || pending.status() != VendorAdvance.Status.PENDING_APPROVAL) {
            return;
        }
        VendorAdvance closed = "CANCELLED".equals(approvalStatus)
                ? pending.cancelled("Approval request withdrawn", Instant.now()) : pending.closedWithoutApproval();
        advances.save(closed);
        audit.record("VENDOR_ADVANCE_" + (closed.status() == VendorAdvance.Status.CANCELLED ? "CANCELLED" : "REJECTED"),
                AUDIT_TYPE, id, snapshot(pending), snapshot(closed, "approval", approvalStatus));
    }

    private VendorAdvance require(UUID id) {
        return advances.findById(id).orElseThrow(() -> new ResourceNotFoundException("VendorAdvance", id));
    }

    private VendorAdvance requireVersion(UUID id, long expectedVersion) {
        VendorAdvance advance = require(id);
        if (advance.version() == null || advance.version() != expectedVersion) {
            throw new OptimisticLockingFailureException(
                    "Advance " + advance.advanceNo() + " changed since it was read");
        }
        return advance;
    }

    private static BusinessRuleViolationException modeTaken(String tripNo, VendorAdvance.Mode mode) {
        return new BusinessRuleViolationException("advance-mode-already-used",
                "Trip " + tripNo + " already has a live " + mode + " advance; one per mode per trip");
    }

    private static Json snapshot(VendorAdvance a, String... extra) {
        String[] pairs = new String[10 + extra.length];
        int i = 0;
        for (String s : new String[] {"advanceNo", a.advanceNo(), "status", a.status().name(), "mode",
                a.mode().name(), "amount", a.amount().toPlainString(), "tripId", a.tripId().toString()}) {
            pairs[i++] = s;
        }
        for (String e : extra) {
            pairs[i++] = e;
        }
        return Json.object(pairs);
    }
}
