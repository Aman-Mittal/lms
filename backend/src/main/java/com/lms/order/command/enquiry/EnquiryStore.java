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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Plain SQL for the enquiry tables, always inside the caller's transaction and
 * tenant scope (row-level security confines every statement). An enquiry is
 * written as a whole under one lock on its header, so its lines and quotes
 * carry no version of their own: the header's version, bumped by every write,
 * is the {@code If-Match} of the whole.
 */
@Component
class EnquiryStore {

    /** The header as the commands need it. */
    record Header(UUID id, UUID orgUnitId, String enquiryNo, UUID customerPartnerId, String status,
                  Instant deadlineAt, UUID orderId, Long version) {

        EnquiryStatus state() {
            return EnquiryStatus.valueOf(status);
        }
    }

    /** One line, without its quotes. */
    record Line(UUID id, int lineNo, UUID originTerminalId, UUID destinationTerminalId, String loadType,
                String material, BigDecimal quantity, String uom, BigDecimal weightKg, LocalDate wantedPickupOn,
                LocalDate wantedDeliveryOn, String status, BigDecimal wonQuantity) {

        LineStatus state() {
            return LineStatus.valueOf(status);
        }
    }

    /** The quote a customer holds on a line. */
    record Quote(UUID id, int revision) {
    }

    private static final String LINE_COLUMNS = """
            id, line_no, origin_terminal_id, destination_terminal_id, load_type, material, quantity, uom,
            weight_kg, wanted_pickup_on, wanted_delivery_on, status, won_quantity
            """;

    private final JdbcClient jdbc;

    EnquiryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertEnquiry(UUID id, UUID tenantId, UUID orgUnitId, String enquiryNo, UUID customerId, String reference,
                       String notes, Instant deadlineAt, UUID createdBy) {
        jdbc.sql("""
                        INSERT INTO enquiry (id, tenant_id, org_unit_id, enquiry_no, customer_partner_id, reference,
                                             notes, status, deadline_at, created_by, version)
                        VALUES (:id, :tenantId, :orgUnitId, :enquiryNo, :customer, :reference, :notes, 'OPEN',
                                :deadline, :createdBy, 0)
                        """)
                .param("id", id).param("tenantId", tenantId).param("orgUnitId", orgUnitId)
                .param("enquiryNo", enquiryNo).param("customer", customerId).param("reference", reference)
                .param("notes", notes).param("deadline", deadlineAt.atOffset(ZoneOffset.UTC))
                .param("createdBy", createdBy).update();
    }

    UUID insertLine(UUID tenantId, UUID enquiryId, int lineNo, EnquiryLineSpec spec) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO enquiry_line (id, tenant_id, enquiry_id, line_no, origin_terminal_id,
                                                  destination_terminal_id, load_type, material, quantity, uom,
                                                  weight_kg, wanted_pickup_on, wanted_delivery_on)
                        VALUES (:id, :tenantId, :enquiryId, :lineNo, :origin, :destination, :loadType, :material,
                                :quantity, :uom, :weight, :pickup, :delivery)
                        """)
                .param("id", id).param("tenantId", tenantId).param("enquiryId", enquiryId).param("lineNo", lineNo)
                .param("origin", spec.originTerminalId()).param("destination", spec.destinationTerminalId())
                .param("loadType", spec.loadType()).param("material", spec.material())
                .param("quantity", spec.quantity()).param("uom", spec.uom()).param("weight", spec.weightKg())
                .param("pickup", spec.wantedPickupOn()).param("delivery", spec.wantedDeliveryOn()).update();
        return id;
    }

    /** The header locked for the transaction; empty when it is not the tenant's. */
    Optional<Header> lock(UUID id) {
        return jdbc.sql("""
                        SELECT id, org_unit_id, enquiry_no, customer_partner_id, status, deadline_at, order_id, version
                          FROM enquiry WHERE id = :id FOR UPDATE
                        """)
                .param("id", id).query(Header.class).optional();
    }

    List<Line> lines(UUID enquiryId) {
        return jdbc.sql("SELECT " + LINE_COLUMNS + " FROM enquiry_line WHERE enquiry_id = :id ORDER BY line_no")
                .param("id", enquiryId).query(Line.class).list();
    }

    Optional<Line> line(UUID enquiryId, UUID lineId) {
        return jdbc.sql("SELECT " + LINE_COLUMNS + " FROM enquiry_line WHERE enquiry_id = :e AND id = :l")
                .param("e", enquiryId).param("l", lineId).query(Line.class).optional();
    }

    /** Moves the header to {@code status} and gives it the next version. */
    void advance(UUID id, EnquiryStatus status) {
        jdbc.sql("UPDATE enquiry SET status = :status, version = version + 1, updated_at = now() WHERE id = :id")
                .param("status", status.name()).param("id", id).update();
    }

    void cancelHeader(UUID id, String reason) {
        jdbc.sql("""
                        UPDATE enquiry SET status = 'CANCELLED', cancel_reason = :reason, cancelled_at = now(),
                                           version = version + 1, updated_at = now()
                         WHERE id = :id
                        """)
                .param("reason", reason).param("id", id).update();
    }

    void markConverted(UUID id, UUID orderId) {
        jdbc.sql("""
                        UPDATE enquiry SET status = 'CONVERTED', order_id = :orderId, converted_at = now(),
                                           version = version + 1, updated_at = now()
                         WHERE id = :id
                        """)
                .param("orderId", orderId).param("id", id).update();
    }

    void decide(UUID lineId, LineStatus status, BigDecimal wonQuantity, String lostReason) {
        jdbc.sql("""
                        UPDATE enquiry_line SET status = :status, won_quantity = :won, lost_reason = :reason,
                                                outcome_at = now(), updated_at = now()
                         WHERE id = :id AND status = 'OPEN'
                        """)
                .param("status", status.name()).param("won", wonQuantity).param("reason", lostReason)
                .param("id", lineId).update();
    }

    /** Closes the lines still open: not won, not lost, simply no longer asked for. */
    int cancelOpenLines(UUID enquiryId) {
        return jdbc.sql("""
                        UPDATE enquiry_line SET status = 'CANCELLED', outcome_at = now(), updated_at = now()
                         WHERE enquiry_id = :id AND status = 'OPEN'
                        """)
                .param("id", enquiryId).update();
    }

    void suggest(UUID lineId, UUID contractId, String laneCode, String basis, BigDecimal rate, BigDecimal amount) {
        jdbc.sql("""
                        UPDATE enquiry_line SET suggested_contract_id = :contract, suggested_lane_code = :lane,
                                                suggested_rate_basis = :basis, suggested_rate = :rate,
                                                suggested_amount = :amount, updated_at = now()
                         WHERE id = :id
                        """)
                .param("contract", contractId).param("lane", laneCode).param("basis", basis).param("rate", rate)
                .param("amount", amount).param("id", lineId).update();
    }

    Optional<Quote> issuedQuote(UUID lineId) {
        return jdbc.sql("SELECT id, revision FROM enquiry_quote WHERE line_id = :l AND status = 'ISSUED'")
                .param("l", lineId).query(Quote.class).optional();
    }

    int nextRevision(UUID lineId) {
        Integer last = jdbc.sql("SELECT max(revision) FROM enquiry_quote WHERE line_id = :l")
                .param("l", lineId).query(Integer.class).optional().orElse(null);
        return last == null ? 1 : last + 1;
    }

    UUID insertQuote(UUID tenantId, UUID enquiryId, UUID lineId, int revision, BigDecimal amount,
                     Instant validUntil, String note, UUID issuedBy) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO enquiry_quote (id, tenant_id, enquiry_id, line_id, revision, amount, valid_until,
                                                   note, status, issued_by)
                        VALUES (:id, :tenantId, :enquiryId, :lineId, :revision, :amount, :validUntil, :note,
                                'ISSUED', :issuedBy)
                        """)
                .param("id", id).param("tenantId", tenantId).param("enquiryId", enquiryId).param("lineId", lineId)
                .param("revision", revision).param("amount", amount)
                .param("validUntil", validUntil.atOffset(ZoneOffset.UTC)).param("note", note)
                .param("issuedBy", issuedBy).update();
        return id;
    }

    void supersede(UUID quoteId) {
        jdbc.sql("UPDATE enquiry_quote SET status = 'SUPERSEDED' WHERE id = :id").param("id", quoteId).update();
    }

    void revoke(UUID quoteId, String reason) {
        jdbc.sql("""
                        UPDATE enquiry_quote SET status = 'REVOKED', revoked_at = now(), revoke_reason = :reason
                         WHERE id = :id
                        """)
                .param("reason", reason).param("id", quoteId).update();
    }

    /** Withdraws every quote a customer still holds on an enquiry; how many. */
    int revokeIssued(UUID enquiryId, String reason) {
        return jdbc.sql("""
                        UPDATE enquiry_quote SET status = 'REVOKED', revoked_at = now(), revoke_reason = :reason
                         WHERE enquiry_id = :id AND status = 'ISSUED'
                        """)
                .param("reason", reason).param("id", enquiryId).update();
    }
}
