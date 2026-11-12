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
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.lms.approvals.api.ApprovalRequests;
import com.lms.approvals.api.ApprovalSubject;
import com.lms.approvals.api.ApprovalSubjectTypes;
import com.lms.approvals.api.ApprovalTicket;
import com.lms.finance.api.ReceivableChargePort;
import com.lms.finance.command.domain.ChargeCatalogueItem;
import com.lms.finance.command.domain.ChargeDirection;
import com.lms.finance.command.domain.ManualCharge;
import com.lms.finance.events.ManualChargeApproved;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raising, approving and cancelling charges made by hand (issue #225).
 *
 * <p>A charge is raised from a catalogue entry against a trip, a consignment
 * or an order, with a quantity, a rate (the entry's unless overridden), a
 * reason and an evidence reference. Whether it needs an approval is decided on
 * the server from {@code finance.manualCharge.approvalThreshold}, resolved for
 * the record in hand (its unit, the customer of a receivable, the vendor of a
 * payable) and never from the request: above the threshold, and when the
 * tenant's {@code MANUAL_CHARGE} policy asks, the charge waits as
 * {@code PENDING_APPROVAL} and does nothing until the approval is decided.
 *
 * <p>When it takes effect a receivable becomes a client charge on the
 * customer's next invoice and a payable a line of the trip's bill while that
 * is a draft (see {@link PayableAttachment}). Nothing already raised, priced
 * or approved is re-priced. Cancelling is possible only before billing: while
 * pending (the approval request is withdrawn), or while the client charge is
 * on no invoice and the bill line is on a bill still a draft.
 */
@Service
public class ManualChargeService {

    /** The action a manual charge is approved as. */
    public static final String APPROVE_ACTION = "APPROVE";
    static final String AUDIT_TYPE = "MANUAL_CHARGE";

    /** What a person asks for. {@code rate} and {@code direction} are optional. */
    public record RecordCommand(UUID catalogueId, ManualCharge.SubjectType subjectType, UUID subjectId,
                                ChargeDirection.Side direction, UUID customerId, BigDecimal quantity,
                                BigDecimal rate, String reason, String evidenceRef) {
    }

    /** The charge and, when it waits for a decision, the approval request. */
    public record Outcome(UUID id, ApprovalTicket approval) {
    }

    private final ChargeCatalogueRepository catalogue;
    private final ManualChargeRepository charges;
    private final MoneySubjects subjects;
    private final PayableAttachment attachment;
    private final ReceivableChargePort receivables;
    private final ApprovalRequests approvals;
    private final Settings settings;
    private final DocumentNumbers numbers;
    private final TransitionAudit audit;
    private final ApplicationEventPublisher events;

    public ManualChargeService(ChargeCatalogueRepository catalogue, ManualChargeRepository charges,
                               MoneySubjects subjects, PayableAttachment attachment,
                               ReceivableChargePort receivables, ApprovalRequests approvals, Settings settings,
                               DocumentNumbers numbers, TransitionAudit audit, ApplicationEventPublisher events) {
        this.catalogue = catalogue;
        this.charges = charges;
        this.subjects = subjects;
        this.attachment = attachment;
        this.receivables = receivables;
        this.approvals = approvals;
        this.settings = settings;
        this.numbers = numbers;
        this.audit = audit;
        this.events = events;
    }

    @Transactional
    @PreAuthorize("hasAuthority('MANUAL_CHARGE_RECORD')")
    public Outcome record(RecordCommand command) {
        UUID tenantId = TenantContext.requireTenantId();
        ChargeCatalogueItem entry = catalogue.findById(command.catalogueId())
                .orElseThrow(() -> new ResourceNotFoundException("ChargeCatalogue", command.catalogueId()));
        if (!entry.active()) {
            throw new BusinessRuleViolationException("charge-inactive",
                    "Charge " + entry.code() + " is switched off in the catalogue");
        }
        ChargeDirection.Side side = sideFor(entry, command.direction());
        MoneySubjects.Found found = subjects.find(command.subjectType(), command.subjectId());
        ManualCharge.Subject subject = found.subject();
        UUID party = side == ChargeDirection.Side.RECEIVABLE ? customerOf(found, command.customerId())
                : vendorOf(subject);

        SettingContext context = side == ChargeDirection.Side.RECEIVABLE
                ? SettingContext.ofOrgUnit(subject.orgUnitId()).withCustomer(party)
                : SettingContext.ofOrgUnit(subject.orgUnitId()).withVendor(party);
        BigDecimal rate = command.rate() != null ? command.rate() : entry.defaultRate();
        BigDecimal amount = entry.basis().amount(command.quantity() == null ? BigDecimal.ZERO : command.quantity(),
                rate == null ? BigDecimal.ZERO : rate);
        boolean needsApproval = amount.compareTo(settings.effective(
                FinanceSettingDefinitions.MANUAL_CHARGE_APPROVAL_THRESHOLD, context)) > 0
                && approvals.isRequired(ApprovalSubjectTypes.MANUAL_CHARGE, context);

        String number = numbers.next(DocumentType.MANUAL_CHARGE, subject.orgUnitId())
                .orElse("MC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT));
        ManualCharge raised = charges.save(ManualCharge.raise(UUID.randomUUID(), tenantId, number, entry, side,
                subject, party, command.quantity(), rate, command.reason(), command.evidenceRef(), needsApproval,
                Actors.currentUserId()));

        if (!needsApproval) {
            ManualCharge active = activate(raised);
            audit.record("MANUAL_CHARGE_RECORDED", AUDIT_TYPE, active.id(), null, snapshot(active));
            return new Outcome(active.id(), null);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chargeNo", raised.chargeNo());
        payload.put("chargeCode", raised.chargeCode());
        payload.put("direction", side.name());
        payload.put("subjectType", subject.type().name());
        payload.put("subjectRef", subject.ref());
        payload.put("amount", raised.amount().toPlainString());
        payload.put("currency", raised.currency());
        payload.put("reason", raised.reason());
        ApprovalTicket ticket = approvals.request(
                new ApprovalSubject(ApprovalSubjectTypes.MANUAL_CHARGE, raised.id(), subject.orgUnitId()), context,
                APPROVE_ACTION, payload,
                "Manual charge " + raised.chargeCode() + " " + raised.amount().toPlainString() + " "
                        + raised.currency() + " on " + subject.ref() + " (" + raised.chargeNo() + ")");
        charges.save(raised.withApproval(ticket.id()));
        audit.record("MANUAL_CHARGE_RECORDED", AUDIT_TYPE, raised.id(), null,
                snapshot(raised, "approvalId", ticket.id().toString()));
        return new Outcome(raised.id(), ticket);
    }

    /**
     * Cancels a charge that has not been billed, with the reason: a pending
     * one by withdrawing its approval request, an approved receivable by
     * taking its client charge back while that is on no invoice, an approved
     * payable by removing its line while the bill is a draft.
     */
    @Transactional
    @PreAuthorize("hasAuthority('MANUAL_CHARGE_RECORD')")
    public void cancel(UUID id, long expectedVersion, String reason) {
        ManualCharge before = require(id);
        if (before.version() == null || before.version() != expectedVersion) {
            throw new OptimisticLockingFailureException("Charge " + before.chargeNo() + " changed since it was read");
        }
        ManualCharge cancelled = before.cancelled(reason, Instant.now());
        if (before.status() == ManualCharge.Status.PENDING_APPROVAL) {
            approvals.withdraw(ApprovalSubjectTypes.MANUAL_CHARGE, id, APPROVE_ACTION, reason);
        } else if (before.isReceivable()) {
            if (before.clientChargeId() != null && !receivables.withdraw(before.clientChargeId())) {
                throw new BusinessRuleViolationException("manual-charge-already-billed",
                        "Charge " + before.chargeNo() + " is on a customer invoice already; it is credited, not cancelled");
            }
        } else {
            cancelled = attachment.removeFromBill(before).cancelled(reason, Instant.now());
        }
        charges.save(cancelled);
        audit.record("MANUAL_CHARGE_CANCELLED", AUDIT_TYPE, id, snapshot(before),
                snapshot(cancelled, "reason", cancelled.cancelReason()));
    }

    /**
     * The decision came: approved. Called by the owner's approval handler in
     * the deciding transaction, which is why it is unguarded. Refuses (and the
     * request stays open) when the charge is no longer waiting.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyApproved(UUID chargeId, UUID approvalId) {
        ManualCharge pending = require(chargeId);
        if (pending.status() != ManualCharge.Status.PENDING_APPROVAL) {
            throw new BusinessRuleViolationException("manual-charge-not-pending",
                    "Charge " + pending.chargeNo() + " is " + pending.status() + " and no longer waits for approval");
        }
        ManualCharge active = activate(pending.approved(Instant.now()));
        audit.record("MANUAL_CHARGE_APPROVED", AUDIT_TYPE, chargeId, snapshot(pending),
                snapshot(active, "approvalId", String.valueOf(approvalId)));
    }

    /** The approval was rejected, withdrawn by its requester or expired: the charge never takes effect. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closedWithoutApproval(UUID chargeId, String approvalStatus) {
        ManualCharge pending = charges.findById(chargeId).orElse(null);
        if (pending == null || pending.status() != ManualCharge.Status.PENDING_APPROVAL) {
            return;
        }
        ManualCharge closed = "CANCELLED".equals(approvalStatus)
                ? pending.cancelled("Approval request withdrawn", Instant.now())
                : pending.closedWithoutApproval(Instant.now());
        charges.save(closed);
        audit.record("MANUAL_CHARGE_" + (closed.status() == ManualCharge.Status.CANCELLED ? "CANCELLED" : "REJECTED"),
                AUDIT_TYPE, chargeId, snapshot(pending), snapshot(closed, "approval", approvalStatus));
    }

    /** Puts an approved charge to work: the receivable on the customer's invoice, the payable on the bill or in the queue. */
    private ManualCharge activate(ManualCharge approved) {
        ManualCharge done;
        if (approved.isReceivable()) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            UUID clientCharge = receivables.capture(new ReceivableChargePort.Capture(approved.customerPartnerId(),
                    "MANUAL:" + approved.id(),
                    approved.chargeName() + " (" + approved.chargeCode() + "): " + approved.reason(),
                    approved.quantity(), approved.basis().unit(), approved.rate(), approved.amount(), today,
                    approved.chargeNo() + " / " + approved.subjectRef(), approved.tripId())).orElse(null);
            done = clientCharge == null ? approved : approved.withClientCharge(clientCharge);
        } else {
            done = attachment.attachToTripBill(approved);
        }
        ManualCharge saved = charges.save(done);
        events.publishEvent(new ManualChargeApproved(saved.tenantId(), saved.id(), saved.chargeNo(),
                saved.direction().name(), saved.chargeCode(), saved.subjectType().name(), saved.subjectId(),
                saved.tripId(), saved.customerPartnerId(), saved.vendorPartnerId(), saved.amount(), saved.currency(),
                saved.clientChargeId(), saved.freightBillId(), Instant.now()));
        return saved;
    }

    private ManualCharge require(UUID id) {
        return charges.findById(id).orElseThrow(() -> new ResourceNotFoundException("ManualCharge", id));
    }

    private static ChargeDirection.Side sideFor(ChargeCatalogueItem entry, ChargeDirection.Side asked) {
        if (entry.direction() == ChargeDirection.BOTH) {
            if (asked == null) {
                throw new BusinessRuleViolationException("manual-charge-direction-required",
                        "Charge " + entry.code() + " may be billed to the customer or paid to the vendor; say which");
            }
            return asked;
        }
        ChargeDirection.Side only = ChargeDirection.Side.valueOf(entry.direction().name());
        if (asked != null && asked != only) {
            throw new BusinessRuleViolationException("manual-charge-direction-not-allowed",
                    "Charge " + entry.code() + " is " + entry.direction() + " only");
        }
        return only;
    }

    private static UUID customerOf(MoneySubjects.Found found, UUID asked) {
        if (asked != null) {
            if (!found.customers().contains(asked)) {
                throw new BusinessRuleViolationException("manual-charge-customer-not-aboard",
                        "That customer has no freight on " + found.subject().ref());
            }
            return asked;
        }
        if (found.subject().customerPartnerId() == null) {
            throw new BusinessRuleViolationException("manual-charge-customer-required",
                    found.subject().ref() + " carries several customers' freight or none; name the customer to bill");
        }
        return found.subject().customerPartnerId();
    }

    private static UUID vendorOf(ManualCharge.Subject subject) {
        if (subject.type() != ManualCharge.SubjectType.TRIP) {
            throw new BusinessRuleViolationException("manual-charge-payable-needs-trip",
                    "A charge paid to a vendor is raised against a trip, whose vendor it is owed to");
        }
        if (subject.vendorPartnerId() == null) {
            throw new BusinessRuleViolationException("manual-charge-trip-has-no-vendor",
                    "Trip " + subject.ref() + " runs on the tenant's own fleet; there is no vendor to pay");
        }
        return subject.vendorPartnerId();
    }

    private static Json snapshot(ManualCharge c, String... extra) {
        String[] pairs = new String[14 + extra.length];
        int i = 0;
        for (String s : new String[] {"chargeNo", c.chargeNo(), "status", c.status().name(), "direction",
                c.direction().name(), "chargeCode", c.chargeCode(), "amount", c.amount().toPlainString(),
                "subject", c.subjectType() + ":" + c.subjectRef(),
                "billedTo", String.valueOf(c.isReceivable() ? c.clientChargeId() : c.freightBillId())}) {
            pairs[i++] = s;
        }
        for (String e : extra) {
            pairs[i++] = e;
        }
        return Json.object(pairs);
    }
}
