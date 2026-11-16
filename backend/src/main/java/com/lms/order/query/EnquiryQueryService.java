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

package com.lms.order.query;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lms.identity.access.AccessSubject;
import com.lms.shared.query.Search;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the enquiry desk, newest first, keyset on creation time and id
 * ({@code enquiry_list_idx}, or {@code enquiry_status_idx} for one status). A
 * partner-bound caller sees only enquiries made for that customer. The
 * detail loads the lines and every quote revision in two statements, however
 * many lines there are.
 */
@Service
@Transactional(readOnly = true)
public class EnquiryQueryService {

    private static final String SELECT = """
            SELECT e.id, e.enquiry_no, e.status, e.org_unit_id, e.customer_partner_id, c.legal_name AS customer_name,
                   e.reference, e.notes, e.deadline_at, e.cancel_reason, e.cancelled_at, e.order_id,
                   o.order_no, e.converted_at, agg.line_count, agg.won_line_count,
                   e.version, e.created_at, e.updated_at
              FROM enquiry e
              JOIN business_partner c ON c.id = e.customer_partner_id
              LEFT JOIN sales_order o ON o.id = e.order_id
              LEFT JOIN LATERAL (
                    SELECT count(*)::int AS line_count,
                           (count(*) FILTER (WHERE l.status IN ('WON', 'PARTIAL')))::int AS won_line_count
                      FROM enquiry_line l WHERE l.enquiry_id = e.id
                   ) agg ON true
            """;

    private static final String PARTNER_SCOPE = """
               AND (CAST(:partner AS uuid) IS NULL OR e.customer_partner_id = CAST(:partner AS uuid))
            """;

    private static final String LINE_SELECT = """
            SELECT l.id, l.line_no, l.origin_terminal_id, ot.code AS origin_code,
                   l.destination_terminal_id, dt.code AS destination_code, l.load_type, l.material,
                   l.quantity, l.uom, l.weight_kg, l.wanted_pickup_on, l.wanted_delivery_on, l.status,
                   l.won_quantity, l.lost_reason, l.outcome_at, k.code AS suggested_contract_code,
                   l.suggested_lane_code, l.suggested_rate_basis, l.suggested_rate, l.suggested_amount, l.currency
              FROM enquiry_line l
              JOIN terminal ot ON ot.id = l.origin_terminal_id
              JOIN terminal dt ON dt.id = l.destination_terminal_id
              LEFT JOIN client_contract k ON k.id = l.suggested_contract_id
             WHERE l.enquiry_id = :id
             ORDER BY l.line_no
            """;

    private final JdbcClient jdbc;

    public EnquiryQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EnquiryView> find(UUID id) {
        Optional<Head> header = jdbc.sql(SELECT + " WHERE e.id = :id" + PARTNER_SCOPE)
                .param("id", id).param("partner", AccessSubject.current().partnerScope())
                .query(Head.class).optional();
        return header.map(head -> head.view(linesOf(id)));
    }

    /** A page of enquiries, optionally of one status, one customer, or whose number or reference contains {@code q}. */
    public Slice<EnquiryView> list(String status, UUID customerId, String q, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Position after = Position.parse(cursor);
        List<Head> rows = jdbc.sql(SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR e.status = CAST(:status AS text))
                           AND (CAST(:customerId AS uuid) IS NULL OR e.customer_partner_id = CAST(:customerId AS uuid))
                           AND (CAST(:q AS text) IS NULL OR lower(e.enquiry_no) LIKE CAST(:q AS text)
                                OR lower(coalesce(e.reference, '')) LIKE CAST(:q AS text))
                           AND (CAST(:atBefore AS timestamptz) IS NULL
                                OR (e.created_at, e.id) < (CAST(:atBefore AS timestamptz), CAST(:idBefore AS uuid)))
                        """ + PARTNER_SCOPE + """
                         ORDER BY e.created_at DESC, e.id DESC
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("customerId", customerId)
                .param("q", Search.containsPattern(q))
                .param("atBefore", after == null ? null : Timestamp.from(after.at()))
                .param("idBefore", after == null ? null : after.id())
                .param("partner", AccessSubject.current().partnerScope())
                .param("limit", pageSize + 1)
                .query(Head.class).list();
        Slice<Head> page = Slice.of(rows, pageSize, row -> Position.of(row.createdAt(), row.id()));
        return new Slice<>(page.items().stream().map(row -> row.view(List.of())).toList(), page.nextCursor(),
                page.hasMore());
    }

    private List<EnquiryLineView> linesOf(UUID enquiryId) {
        List<LineRow> lines = jdbc.sql(LINE_SELECT).param("id", enquiryId).query(LineRow.class).list();
        Map<UUID, List<QuoteView>> quotes = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT id, line_id, revision, amount, currency, valid_until, note, status, revoked_at,
                               revoke_reason, created_at
                          FROM enquiry_quote WHERE enquiry_id = :id ORDER BY line_id, revision
                        """)
                .param("id", enquiryId).query(QuoteView.class).list()
                .forEach(quote -> quotes.computeIfAbsent(quote.lineId(), k -> new ArrayList<>()).add(quote));
        return lines.stream().map(l -> l.view(quotes.getOrDefault(l.id(), List.of()))).toList();
    }

    /** The columns of the enquiry statement: a view without its lines, which are read separately. */
    private record Head(UUID id, String enquiryNo, String status, UUID orgUnitId, UUID customerPartnerId,
                        String customerName, String reference, String notes, Instant deadlineAt, String cancelReason,
                        Instant cancelledAt, UUID orderId, String orderNo, Instant convertedAt, int lineCount,
                        int wonLineCount, Long version, Instant createdAt, Instant updatedAt) {

        EnquiryView view(List<EnquiryLineView> lines) {
            return new EnquiryView(id, enquiryNo, status, orgUnitId, customerPartnerId, customerName, reference,
                    notes, deadlineAt, cancelReason, cancelledAt, orderId, orderNo, convertedAt, lineCount,
                    wonLineCount, version, createdAt, updatedAt, lines);
        }
    }

    /** The columns of the line statement: a view without its quotes, which are read separately. */
    private record LineRow(UUID id, int lineNo, UUID originTerminalId, String originCode,
                           UUID destinationTerminalId, String destinationCode, String loadType, String material,
                           java.math.BigDecimal quantity, String uom, java.math.BigDecimal weightKg,
                           java.time.LocalDate wantedPickupOn, java.time.LocalDate wantedDeliveryOn, String status,
                           java.math.BigDecimal wonQuantity, String lostReason, Instant outcomeAt,
                           String suggestedContractCode, String suggestedLaneCode, String suggestedRateBasis,
                           java.math.BigDecimal suggestedRate, java.math.BigDecimal suggestedAmount, String currency) {

        EnquiryLineView view(List<QuoteView> quotes) {
            return new EnquiryLineView(id, lineNo, originTerminalId, originCode, destinationTerminalId,
                    destinationCode, loadType, material, quantity, uom, weightKg, wantedPickupOn, wantedDeliveryOn,
                    status, wonQuantity, lostReason, outcomeAt, suggestedContractCode, suggestedLaneCode,
                    suggestedRateBasis, suggestedRate, suggestedAmount, currency, quotes);
        }
    }

    /** The cursor: the creation time to the microsecond, as stored, and the id that breaks ties. */
    private record Position(Instant at, UUID id) {

        static String of(Instant at, UUID id) {
            Instant micros = at.truncatedTo(ChronoUnit.MICROS);
            return "t" + micros.getEpochSecond() + "." + String.format("%06d", micros.getNano() / 1000) + "_" + id;
        }

        static Position parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                if (!cursor.startsWith("t")) {
                    throw new IllegalArgumentException("wrong kind");
                }
                String[] parts = cursor.substring(1).split("_", 2);
                String[] seconds = parts[0].split("\\.", 2);
                return new Position(Instant.ofEpochSecond(Long.parseLong(seconds[0]),
                        Long.parseLong(seconds[1]) * 1000), UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("The cursor is not one this list handed out", e);
            }
        }
    }
}
