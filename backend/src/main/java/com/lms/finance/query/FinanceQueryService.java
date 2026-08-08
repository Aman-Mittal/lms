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
 */package com.lms.finance.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side for rating and settlement. */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('INVOICE_READ')")
public class FinanceQueryService {

    private static final String SELECT = """
            SELECT b.id, b.bill_no, b.status, b.currency,
                   b.trip_id, t.trip_no,
                   b.load_id, l.load_no,
                   vp.legal_name AS vendor_name,
                   tf.code AS tariff_code,
                   b.priced_on, b.chargeable_weight_kg, b.detention_hours, b.drop_count,
                   b.base_freight, b.detention_amount, b.multi_drop_amount,
                   b.fuel_surcharge_amount, b.computed_amount, b.claimed_amount,
                   b.variance_amount, b.variance_pct, b.resolution, b.matched_at,
                   b.created_at
              FROM freight_bill b
              JOIN trip t ON t.id = b.trip_id
              JOIN load_unit l ON l.id = b.load_id
              JOIN business_partner vp ON vp.id = b.vendor_partner_id
              LEFT JOIN tariff tf ON tf.id = b.tariff_id
            """;

    private final JdbcClient jdbc;

    public FinanceQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<FreightBillView> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE b.id = :id")
                .param("id", id)
                .query(FreightBillView.class)
                .optional();
    }

    public Optional<FreightBillView> findByBillNo(String billNo) {
        return jdbc.sql(SELECT + " WHERE b.bill_no = :billNo")
                .param("billNo", billNo)
                .query(FreightBillView.class)
                .optional();
    }

    /**
     * A page of bills, keyed on the bill number, which is unique per tenant.
     *
     * <p>Keyset rather than OFFSET, like every other listing here: a settlement
     * queue is read from the top and paged through, and OFFSET makes each page
     * cost more than the last.
     */
    public Slice<FreightBillView> list(String status, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);

        List<FreightBillView> rows = jdbc.sql(SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR b.status = CAST(:status AS text))
                           AND (CAST(:cursor AS text) IS NULL OR b.bill_no > CAST(:cursor AS text))
                         ORDER BY b.bill_no
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query(FreightBillView.class)
                .list();

        return Slice.of(rows, pageSize, FreightBillView::billNo);
    }

    /** The arithmetic behind one bill, in the order it should be read. */
    public List<FreightBillLineView> linesOf(UUID billId) {
        return jdbc.sql("""
                        SELECT line_no, charge_type, narrative, quantity, unit, rate, amount
                          FROM freight_bill_line
                         WHERE bill_id = :billId
                         ORDER BY line_no
                        """)
                .param("billId", billId)
                .query(FreightBillLineView.class)
                .list();
    }
}
