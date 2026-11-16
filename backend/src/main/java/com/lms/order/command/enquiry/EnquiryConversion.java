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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lms.order.api.FreightMode;
import com.lms.order.api.MaterialClass;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.domain.SalesOrder;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an enquiry's won lines into one DRAFT order (FR-ORD-030) through the
 * order module's own raise and add-line commands, so everything an order is
 * held to (the customer and terminals exist, the customer's contract and
 * credit) applies exactly as for an order typed in.
 *
 * <p>Idempotent. The header is locked first; an enquiry that already has its
 * order answers with it and does nothing more, so a retry after a lost
 * response, or two people pressing the button, make one order.
 */
@Service
public class EnquiryConversion {

    private static final java.util.Set<String> KILO_UNITS = java.util.Set.of("KG", "KGS", "KILOGRAM", "KILOGRAMS");

    private final EnquiryStore store;
    private final OrderCommandService orders;
    private final AuditTrail audit;

    public EnquiryConversion(EnquiryStore store, OrderCommandService orders, AuditTrail audit) {
        this.store = store;
        this.orders = orders;
        this.audit = audit;
    }

    /**
     * @param alreadyConverted true when the order existed before this call
     */
    public record Conversion(UUID orderId, boolean alreadyConverted) {
    }

    @Transactional
    @PreAuthorize("hasAuthority('ORDER_CREATE') and hasAuthority('ORDER_UPDATE')")
    public Conversion convert(UUID enquiryId) {
        EnquiryStore.Header header = store.lock(enquiryId).orElseThrow(() ->
                new ResourceNotFoundException("Enquiry", enquiryId));
        if (header.orderId() != null) {
            return new Conversion(header.orderId(), true);
        }
        if (header.state() != EnquiryStatus.WON && header.state() != EnquiryStatus.PARTIAL) {
            throw new BusinessRuleViolationException("enquiry-not-decided",
                    "Enquiry " + header.enquiryNo() + " is " + header.status()
                            + "; only one whose lines are all decided and some won is converted");
        }
        List<EnquiryStore.Line> won = store.lines(enquiryId).stream().filter(l -> l.state().isWon())
                .sorted(Comparator.comparingInt(EnquiryStore.Line::lineNo)).toList();
        UUID origin = won.get(0).originTerminalId();
        if (won.stream().anyMatch(l -> !l.originTerminalId().equals(origin))) {
            throw new BusinessRuleViolationException("enquiry-origins-differ",
                    "The won lines start at different terminals, and an order has one origin;"
                            + " cancel and re-enquire the lines separately");
        }

        UUID orderId = orders.raiseOrder(header.orgUnitId(), header.customerPartnerId(), null, origin,
                startOf(won.stream().map(EnquiryStore.Line::wantedPickupOn).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null)),
                startOf(won.stream().map(EnquiryStore.Line::wantedDeliveryOn).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null)),
                SalesOrder.OrderSource.MANUAL, FreightMode.FTL, null, null, null, sharedLoadType(won));
        int lineNo = 1;
        for (EnquiryStore.Line line : won) {
            BigDecimal quantity = line.wonQuantity();
            orders.addLine(orderId, lineNo++, "GENERAL",
                    line.material() == null ? "Enquiry " + header.enquiryNo() + " line " + line.lineNo()
                            : line.material(),
                    MaterialClass.GENERAL, null, quantity, line.uom(), weightOf(line, quantity), null, null, null,
                    null, header.customerPartnerId(), line.destinationTerminalId());
        }
        store.markConverted(enquiryId, orderId);
        audit.recordAtomically("ENQUIRY_CONVERTED", EnquiryService.AUDIT_TYPE, enquiryId,
                Json.object("status", header.status()),
                Json.object("status", "CONVERTED", "orderId", orderId.toString(),
                        "lines", String.valueOf(won.size())));
        return new Conversion(orderId, false);
    }

    private static Instant startOf(LocalDate day) {
        return day == null ? null : day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** One load type for the order when every won line asks for the same, else none. */
    private static String sharedLoadType(List<EnquiryStore.Line> lines) {
        List<String> types = lines.stream().map(EnquiryStore.Line::loadType).distinct().toList();
        return types.size() == 1 ? types.get(0) : null;
    }

    private static BigDecimal weightOf(EnquiryStore.Line line, BigDecimal wonQuantity) {
        if (line.weightKg() != null) {
            return line.weightKg().multiply(wonQuantity).divide(line.quantity(), 3, RoundingMode.HALF_UP);
        }
        if (KILO_UNITS.contains(line.uom().strip().toUpperCase(java.util.Locale.ROOT))) {
            return wonQuantity;
        }
        throw new BusinessRuleViolationException("enquiry-line-weight-missing",
                "Line " + line.lineNo() + " states no weight and is not counted in kilograms, so the order"
                        + " could not be weighed");
    }
}
