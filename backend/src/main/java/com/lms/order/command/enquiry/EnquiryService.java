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

package com.lms.order.command.enquiry;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lms.identity.access.AccessSubject;
import com.lms.masterdata.api.PartnerStandingPort;
import com.lms.masterdata.api.TerminalCategoryPort;
import com.lms.order.api.OrderContractPort;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.Settings;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The enquiry desk's write side (FR-ORD-029): an enquiry with lines, a rate
 * suggested from the customer's contract, quotes issued, revised and revoked
 * per line, an outcome per line, and cancellation.
 *
 * <p>Every change locks the enquiry's header, checks the {@code If-Match}
 * version against it, bumps it, and writes its audit entry in the same
 * transaction. Conversion to an order is {@link EnquiryConversion}; the
 * deadline sweep is {@link EnquiryDeadlines}.
 */
@Service
public class EnquiryService {

    static final String AUDIT_TYPE = "Enquiry";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EnquiryStore store;
    private final EnquiryRateDiscovery discovery;
    private final Settings settings;
    private final AuditTrail audit;
    private final TerminalCategoryPort terminals;
    private final PartnerStandingPort partners;

    public EnquiryService(EnquiryStore store, EnquiryRateDiscovery discovery, Settings settings, AuditTrail audit,
                          TerminalCategoryPort terminals, PartnerStandingPort partners) {
        this.store = store;
        this.discovery = discovery;
        this.settings = settings;
        this.audit = audit;
        this.terminals = terminals;
        this.partners = partners;
    }

    /**
     * Opens an enquiry. Its deadline is fixed now, from the policy resolved for
     * its unit and customer, and each line is given the rate its customer's
     * contract lanes suggest, where one matches.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_CREATE') and @abac.permitsIn('ORDER_CREATE', #orgUnitId)")
    public UUID create(UUID orgUnitId, UUID customerPartnerId, String reference, String notes,
                       List<EnquiryLineSpec> lines) {
        UUID tenantId = TenantContext.requireTenantId();
        if (lines == null || lines.isEmpty() || lines.size() > EnquiryRules.MAX_LINES) {
            throw new IllegalArgumentException("An enquiry has 1 to " + EnquiryRules.MAX_LINES + " lines");
        }
        if (!partners.exists(customerPartnerId)) {
            throw new ResourceNotFoundException("BusinessPartner", customerPartnerId);
        }
        List<EnquiryLineSpec> clean = lines.stream().map(this::checked).toList();

        int hours = settings.effective(EnquirySettingDefinitions.RESPONSE_DEADLINE_HOURS,
                SettingContext.ofOrgUnit(orgUnitId).withCustomer(customerPartnerId));
        Instant now = Instant.now();
        Instant deadline = EnquiryRules.deadline(now, hours);
        UUID id = UUID.randomUUID();
        String number = "ENQ-" + now.atZone(ZoneOffset.UTC).getYear() + "-"
                + String.format("%06d", RANDOM.nextInt(1_000_000));
        store.insertEnquiry(id, tenantId, orgUnitId, number, customerPartnerId, blankToNull(reference),
                blankToNull(notes), deadline, actorId());
        int lineNo = 1;
        for (EnquiryLineSpec spec : clean) {
            store.insertLine(tenantId, id, lineNo++, spec);
        }
        int suggested = suggestRates(id, customerPartnerId);
        audit.recordAtomically("ENQUIRY_CREATED", AUDIT_TYPE, id, null,
                Json.object("enquiryNo", number, "customerPartnerId", customerPartnerId.toString(),
                        "lines", String.valueOf(clean.size()), "deadlineAt", deadline.toString(),
                        "ratesSuggested", String.valueOf(suggested)));
        return id;
    }

    /** Looks for a contract rate again on every open line, for a contract or lane that changed since. */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public void discoverRates(UUID enquiryId, long expectedVersion) {
        EnquiryStore.Header header = open(enquiryId, expectedVersion);
        int suggested = suggestRates(enquiryId, header.customerPartnerId());
        store.advance(enquiryId, header.state());
        audit.recordAtomically("ENQUIRY_RATES_DISCOVERED", AUDIT_TYPE, enquiryId, null,
                Json.object("ratesSuggested", String.valueOf(suggested)));
    }

    /** Issues the first quote on an open line; a line holds one live quote, which {@code reviseQuote} replaces. */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public UUID issueQuote(UUID enquiryId, UUID lineId, long expectedVersion, BigDecimal amount, Instant validUntil,
                           String note) {
        EnquiryStore.Header header = open(enquiryId, expectedVersion);
        openLine(enquiryId, lineId);
        if (store.issuedQuote(lineId).isPresent()) {
            throw new BusinessRuleViolationException("enquiry-quote-exists",
                    "The line already holds a quote; revise it instead");
        }
        UUID quote = newQuote(header, lineId, amount, validUntil, note);
        store.advance(enquiryId, header.state());
        audit.recordAtomically("ENQUIRY_QUOTE_ISSUED", AUDIT_TYPE, enquiryId, null,
                Json.object("lineId", lineId.toString(), "quoteId", quote.toString(), "amount", amount.toPlainString()));
        return quote;
    }

    /** Replaces the live quote on an open line with a new revision; the old one is kept as superseded. */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public UUID reviseQuote(UUID enquiryId, UUID lineId, long expectedVersion, BigDecimal amount,
                            Instant validUntil, String note) {
        EnquiryStore.Header header = open(enquiryId, expectedVersion);
        openLine(enquiryId, lineId);
        EnquiryStore.Quote live = store.issuedQuote(lineId).orElseThrow(() ->
                new BusinessRuleViolationException("enquiry-quote-missing", "The line holds no quote to revise"));
        store.supersede(live.id());
        UUID quote = newQuote(header, lineId, amount, validUntil, note);
        store.advance(enquiryId, header.state());
        audit.recordAtomically("ENQUIRY_QUOTE_REVISED", AUDIT_TYPE, enquiryId,
                Json.object("lineId", lineId.toString(), "quoteId", live.id().toString(),
                        "revision", String.valueOf(live.revision())),
                Json.object("lineId", lineId.toString(), "quoteId", quote.toString(),
                        "amount", amount.toPlainString()));
        return quote;
    }

    /** Withdraws the live quote on an open line. */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public void revokeQuote(UUID enquiryId, UUID lineId, long expectedVersion, String reason) {
        EnquiryStore.Header header = open(enquiryId, expectedVersion);
        openLine(enquiryId, lineId);
        EnquiryStore.Quote live = store.issuedQuote(lineId).orElseThrow(() ->
                new BusinessRuleViolationException("enquiry-quote-missing", "The line holds no quote to revoke"));
        store.revoke(live.id(), blankToNull(reason));
        store.advance(enquiryId, header.state());
        audit.recordAtomically("ENQUIRY_QUOTE_REVOKED", AUDIT_TYPE, enquiryId, null,
                Json.object("lineId", lineId.toString(), "quoteId", live.id().toString(), "reason", reason));
    }

    /**
     * Decides an open line. When it was the last open one, the enquiry becomes
     * WON, PARTIAL or LOST by what its lines say.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public EnquiryStatus recordOutcome(UUID enquiryId, UUID lineId, long expectedVersion, LineOutcome outcome,
                                       BigDecimal wonQuantity, String reason) {
        if (outcome == null) {
            throw new IllegalArgumentException("An outcome is WON, LOST or PARTIAL");
        }
        EnquiryStore.Header header = open(enquiryId, expectedVersion);
        EnquiryStore.Line line = openLine(enquiryId, lineId);
        BigDecimal won = EnquiryRules.wonQuantity(outcome, line.quantity(), wonQuantity, reason);
        LineStatus status = LineStatus.valueOf(outcome.name());
        store.decide(lineId, status, won, outcome == LineOutcome.LOST ? reason.strip() : null);
        EnquiryStatus next = EnquiryRules.derive(store.lines(enquiryId).stream().map(EnquiryStore.Line::state)
                .toList());
        store.advance(enquiryId, next);
        audit.recordAtomically("ENQUIRY_LINE_" + outcome.name(), AUDIT_TYPE, enquiryId,
                Json.object("status", header.status()),
                Json.object("status", next.name(), "lineId", lineId.toString(), "outcome", outcome.name(),
                        "wonQuantity", won == null ? null : won.toPlainString(), "reason", blankToNull(reason)));
        return next;
    }

    /**
     * Cancels an open enquiry: its open lines are cancelled and every quote
     * held is withdrawn. Refused once any line is won (that freight is
     * converted, or cancelled as an order). Cancelling again changes nothing.
     */
    @Transactional
    @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public void cancel(UUID enquiryId, long expectedVersion, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleViolationException("enquiry-cancel-reason-required",
                    "Cancelling an enquiry says why");
        }
        EnquiryStore.Header header = store.lock(enquiryId).orElseThrow(() ->
                new ResourceNotFoundException("Enquiry", enquiryId));
        if (header.state() == EnquiryStatus.CANCELLED) {
            return;
        }
        requireVersion(header, expectedVersion);
        requireOpen(header);
        if (store.lines(enquiryId).stream().anyMatch(l -> l.state().isWon())) {
            throw new BusinessRuleViolationException("enquiry-has-won-lines",
                    "Enquiry " + header.enquiryNo() + " has won lines, which are never cancelled");
        }
        store.cancelOpenLines(enquiryId);
        store.revokeIssued(enquiryId, "enquiry cancelled");
        store.cancelHeader(enquiryId, reason.strip());
        audit.recordAtomically("ENQUIRY_CANCELLED", AUDIT_TYPE, enquiryId,
                Json.object("status", header.status()), Json.object("status", "CANCELLED", "reason", reason.strip()));
    }

    // ------------------------------------------------------------------ helpers

    private UUID newQuote(EnquiryStore.Header header, UUID lineId, BigDecimal amount, Instant validUntil,
                          String note) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("A quote's amount is above zero");
        }
        Instant now = Instant.now();
        Instant until = validUntil;
        if (until == null) {
            int hours = settings.effective(EnquirySettingDefinitions.QUOTE_VALIDITY_HOURS,
                    SettingContext.ofOrgUnit(header.orgUnitId()).withCustomer(header.customerPartnerId()));
            until = now.plus(java.time.Duration.ofHours(hours));
        } else if (!until.isAfter(now)) {
            throw new BusinessRuleViolationException("enquiry-quote-validity-invalid",
                    "A quote holds until a moment in the future");
        }
        return store.insertQuote(TenantContext.requireTenantId(), header.id(), lineId, store.nextRevision(lineId),
                amount.setScale(2, java.math.RoundingMode.HALF_UP), until, blankToNull(note), actorId());
    }

    /** Suggests a rate for each open line; how many found one. */
    private int suggestRates(UUID enquiryId, UUID customerId) {
        int found = 0;
        for (EnquiryStore.Line line : store.lines(enquiryId)) {
            if (line.state() != LineStatus.OPEN) {
                continue;
            }
            var suggestion = discovery.suggest(customerId, line);
            if (suggestion.isPresent()) {
                OrderContractPort.RateSuggestion s = suggestion.get();
                store.suggest(line.id(), s.contractId(), s.laneCode(), s.rateBasis(), s.rate(), s.amount());
                found++;
            } else {
                store.suggest(line.id(), null, null, null, null, null);
            }
        }
        return found;
    }

    private EnquiryStore.Header open(UUID enquiryId, long expectedVersion) {
        EnquiryStore.Header header = store.lock(enquiryId).orElseThrow(() ->
                new ResourceNotFoundException("Enquiry", enquiryId));
        requireVersion(header, expectedVersion);
        requireOpen(header);
        return header;
    }

    private static void requireVersion(EnquiryStore.Header header, long expectedVersion) {
        if (header.version() == null || header.version() != expectedVersion) {
            throw new OptimisticLockingFailureException(
                    "Enquiry " + header.enquiryNo() + " has changed since version " + expectedVersion);
        }
    }

    private static void requireOpen(EnquiryStore.Header header) {
        if (header.state() != EnquiryStatus.OPEN) {
            throw new BusinessRuleViolationException("enquiry-not-open",
                    "Enquiry " + header.enquiryNo() + " is " + header.status() + " and takes no more changes");
        }
    }

    private EnquiryStore.Line openLine(UUID enquiryId, UUID lineId) {
        EnquiryStore.Line line = store.line(enquiryId, lineId).orElseThrow(() ->
                new ResourceNotFoundException("EnquiryLine", lineId));
        if (line.state() != LineStatus.OPEN) {
            throw new BusinessRuleViolationException("enquiry-line-not-open",
                    "Line " + line.lineNo() + " is " + line.status() + " and is decided");
        }
        return line;
    }

    private EnquiryLineSpec checked(EnquiryLineSpec spec) {
        if (spec.originTerminalId() == null || spec.destinationTerminalId() == null) {
            throw new IllegalArgumentException("A line names its origin and destination terminals");
        }
        if (spec.originTerminalId().equals(spec.destinationTerminalId())) {
            throw new IllegalArgumentException("A line's origin and destination are different terminals");
        }
        for (UUID terminal : List.of(spec.originTerminalId(), spec.destinationTerminalId())) {
            if (terminals.functionalCategoryOf(terminal).isEmpty()) {
                throw new ResourceNotFoundException("Terminal", terminal);
            }
        }
        if (spec.quantity() == null || spec.quantity().signum() <= 0) {
            throw new IllegalArgumentException("A line's quantity is above zero");
        }
        if (spec.uom() == null || spec.uom().isBlank() || spec.uom().length() > 20) {
            throw new IllegalArgumentException("A line's unit of measure is 1 to 20 characters");
        }
        if (spec.weightKg() != null && spec.weightKg().signum() <= 0) {
            throw new IllegalArgumentException("A line's weight is above zero");
        }
        if (spec.wantedPickupOn() != null && spec.wantedDeliveryOn() != null
                && spec.wantedDeliveryOn().isBefore(spec.wantedPickupOn())) {
            throw new IllegalArgumentException("A line is wanted delivered on or after the day it is picked up");
        }
        return new EnquiryLineSpec(spec.originTerminalId(), spec.destinationTerminalId(),
                EnquiryRules.normaliseLoadType(spec.loadType()), blankToNull(spec.material()), spec.quantity(),
                spec.uom().strip(), spec.weightKg(), spec.wantedPickupOn(), spec.wantedDeliveryOn());
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static UUID actorId() {
        String id = AccessSubject.current().userId();
        try {
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
