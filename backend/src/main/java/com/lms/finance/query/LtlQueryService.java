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
package com.lms.finance.query;

import java.util.List;
import java.util.UUID;

import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side for part-load receivables. */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('INVOICE_READ')")
public class LtlQueryService {

    private static final String SELECT = """
            SELECT r.id, r.docket_id, r.docket_no, r.status,
                   r.customer_partner_id, cp.legal_name AS customer_name,
                   r.currency, rc.code AS rate_card_code, r.priced_on,
                   r.origin_zone, r.destination_zone,
                   r.dead_weight_kg, r.volume_cft, r.volumetric_weight_kg, r.chargeable_weight_kg,
                   r.minimum_applied, r.out_of_delivery_area,
                   r.rate_basis, r.rate_amount,
                   r.freight_amount, r.docket_charge, r.oda_surcharge, r.total_amount,
                   r.reason, r.created_at
              FROM docket_receivable r
              JOIN business_partner cp ON cp.id = r.customer_partner_id
              LEFT JOIN ltl_rate_card rc ON rc.id = r.rate_card_id
            """;

    private final JdbcClient jdbc;

    public LtlQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A page of receivables keyed on the docket number, optionally narrowed to
     * one customer -- a customer statement -- and to one status.
     *
     * <p>Docket numbers are unique per tenant, so the cursor needs no
     * tie-breaker. The unfiltered listing rides
     * {@code docket_receivable_no_unique}; the per-customer one rides
     * {@code docket_receivable_customer_idx}.
     */
    public Slice<DocketReceivableView> list(UUID customerPartnerId, String status, String cursor,
                                            Integer limit) {
        int pageSize = Slice.clampLimit(limit);

        List<DocketReceivableView> rows = jdbc.sql(SELECT + """
                         WHERE (CAST(:customer AS uuid) IS NULL
                                OR r.customer_partner_id = CAST(:customer AS uuid))
                           AND (CAST(:status AS text) IS NULL OR r.status = CAST(:status AS text))
                           AND (CAST(:cursor AS text) IS NULL OR r.docket_no > CAST(:cursor AS text))
                         ORDER BY r.docket_no
                         LIMIT :limit
                        """)
                .param("customer", customerPartnerId)
                .param("status", status)
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query(DocketReceivableView.class)
                .list();

        return Slice.of(rows, pageSize, DocketReceivableView::docketNo);
    }
}
