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
package com.lms.sourcing.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for sourcing.
 *
 * <p>The pending offer is resolved in a lateral subquery rather than by loading
 * each allocation's offer history. A sourcing desk watches a list of live
 * allocations; doing this per row in the application would be an N+1 over a
 * link to a 0.1-CPU instance.
 */
@Service
@Transactional(readOnly = true)
public class SourcingQueryService {

    private static final String SELECT = """
            SELECT a.id, a.load_id, l.load_no, a.status, a.strategy, a.current_rank,
                   pv.legal_name AS pending_vendor_name, pending.responds_by,
                   av.legal_name AS awarded_vendor_name, a.awarded_at,
                   coalesce(counted.offers_made, 0) AS offers_made,
                   a.created_at
              FROM allocation a
              JOIN load_unit l ON l.id = a.load_id
              LEFT JOIN LATERAL (
                    SELECT o.vendor_partner_id, o.responds_by
                      FROM allocation_offer o
                     WHERE o.allocation_id = a.id AND o.outcome = 'PENDING'
                     ORDER BY o.rank_no DESC
                     LIMIT 1
                   ) pending ON true
              LEFT JOIN business_partner pv ON pv.id = pending.vendor_partner_id
              LEFT JOIN business_partner av ON av.id = a.awarded_vendor_partner_id
              LEFT JOIN LATERAL (
                    SELECT count(*)::int AS offers_made
                      FROM allocation_offer o2
                     WHERE o2.allocation_id = a.id
                   ) counted ON true
            """;

    private final JdbcClient jdbc;

    public SourcingQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AllocationView> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE a.id = :id")
                .param("id", id)
                .query(AllocationView.class)
                .optional();
    }

    public Optional<AllocationView> findByLoad(UUID loadId) {
        return jdbc.sql(SELECT + " WHERE a.load_id = :loadId")
                .param("loadId", loadId)
                .query(AllocationView.class)
                .optional();
    }

    /**
     * A page of allocations, keyed on the load number.
     *
     * <p>Load numbers are unique per tenant and already carry a unique index,
     * so the cursor needs no tie-breaker column.
     */
    public Slice<AllocationView> list(String status, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);

        List<AllocationView> rows = jdbc.sql(SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR a.status = CAST(:status AS text))
                           AND (CAST(:cursor AS text) IS NULL OR l.load_no > CAST(:cursor AS text))
                         ORDER BY l.load_no
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query(AllocationView.class)
                .list();

        return Slice.of(rows, pageSize, AllocationView::loadNo);
    }

    /** The full cascade trail for one allocation, oldest offer first. */
    public List<OfferView> findOffers(UUID allocationId) {
        return jdbc.sql("""
                        SELECT o.id, o.rank_no, o.vendor_partner_id, p.legal_name AS vendor_name,
                               o.offered_at, o.responds_by, o.outcome, o.responded_at
                          FROM allocation_offer o
                          JOIN business_partner p ON p.id = o.vendor_partner_id
                         WHERE o.allocation_id = :allocationId
                         ORDER BY o.rank_no
                        """)
                .param("allocationId", allocationId)
                .query(OfferView.class)
                .list();
    }
}
