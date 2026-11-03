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

import com.lms.identity.access.AccessSubject;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.query.Search;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
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
                   a.created_at,
                   -- The tenant's own preferences are not for a vendor to read.
                   CASE WHEN CAST(:partner AS uuid) IS NULL THEN rf.rule_id END AS applied_rule_id,
                   CASE WHEN CAST(:partner AS uuid) IS NULL THEN rf.rule_name END AS applied_rule_name,
                   CASE WHEN CAST(:partner AS uuid) IS NULL THEN rf.action END AS applied_rule_action
              FROM allocation a
              LEFT JOIN allocation_rule_firing rf ON rf.allocation_id = a.id
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

    /**
     * The portal-user predicate on allocations: only those in which an offer
     * was addressed to the caller's company. Null for the tenant's own staff.
     */
    private static final String PARTNER_SCOPE = """
               AND (CAST(:partner AS uuid) IS NULL
                    OR EXISTS (SELECT 1 FROM allocation_offer po
                                WHERE po.allocation_id = a.id
                                  AND po.vendor_partner_id = CAST(:partner AS uuid)))
            """;

    private final JdbcClient jdbc;

    public SourcingQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AllocationView> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE a.id = :id" + PARTNER_SCOPE)
                .param("id", id)
                .param("partner", AccessSubject.current().partnerScope())
                .query(AllocationView.class)
                .optional();
    }

    public Optional<AllocationView> findByLoad(UUID loadId) {
        // A load can have several allocations (cancelled ones are kept as
        // history); the live one is the answer, else the latest.
        return jdbc.sql(SELECT + " WHERE a.load_id = :loadId" + PARTNER_SCOPE
                        + " ORDER BY (a.status = 'CANCELLED'), a.created_at DESC LIMIT 1")
                .param("loadId", loadId)
                .param("partner", AccessSubject.current().partnerScope())
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
        return list(status, cursor, limit, null);
    }

    /**
     * As {@link #list(String, String, Integer)}, narrowed to allocations whose
     * load number or id contains {@code q} -- a person quotes the load, a
     * pasted link carries the id.
     */
    public Slice<AllocationView> list(String status, String cursor, Integer limit, String q) {
        int pageSize = Slice.clampLimit(limit);

        List<AllocationView> rows = jdbc.sql(SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR a.status = CAST(:status AS text))
                           AND (CAST(:cursor AS text) IS NULL OR l.load_no > CAST(:cursor AS text))
                           AND (CAST(:q AS text) IS NULL OR lower(l.load_no) LIKE CAST(:q AS text)
                                OR CAST(a.id AS text) LIKE CAST(:q AS text))
                        """ + PARTNER_SCOPE + """
                         ORDER BY l.load_no
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("partner", AccessSubject.current().partnerScope())
                .param("cursor", cursor)
                .param("q", Search.containsPattern(q))
                .param("limit", pageSize + 1)
                .query(AllocationView.class)
                .list();

        return Slice.of(rows, pageSize, AllocationView::loadNo);
    }

    /**
     * A guide and its vendors.
     *
     * <p>Two statements rather than one join folded in Java: the header row and
     * the rank list are different shapes, and a guide has a handful of entries,
     * so the second round trip costs less than the code to unflatten a join.
     */
    @PreAuthorize("hasAnyAuthority('ROUTING_GUIDE_MANAGE', 'LOAD_ALLOCATE')")
    public Optional<RoutingGuideView> findGuide(UUID guideId) {
        List<RoutingGuideView.Entry> entries = jdbc.sql("""
                        SELECT e.rank_no, e.vendor_partner_id, p.legal_name AS vendor_name,
                               e.response_sla_minutes, e.agreed_rate
                          FROM routing_guide_entry e
                          JOIN business_partner p ON p.id = e.vendor_partner_id
                         WHERE e.guide_id = :guideId
                         ORDER BY e.rank_no
                        """)
                .param("guideId", guideId)
                .query((rs, row) -> new RoutingGuideView.Entry(rs.getInt("rank_no"),
                        rs.getObject("vendor_partner_id", UUID.class), rs.getString("vendor_name"),
                        rs.getInt("response_sla_minutes"), rs.getBigDecimal("agreed_rate")))
                .list();

        return jdbc.sql("""
                        SELECT g.id, o.code AS origin_code, d.code AS destination_code,
                               g.vehicle_type, g.strategy, g.active
                          FROM routing_guide g
                          JOIN terminal o ON o.id = g.origin_terminal_id
                          JOIN terminal d ON d.id = g.destination_terminal_id
                         WHERE g.id = :guideId
                        """)
                .param("guideId", guideId)
                .query((rs, row) -> new RoutingGuideView(rs.getObject("id", UUID.class),
                        rs.getString("origin_code"), rs.getString("destination_code"),
                        rs.getString("vehicle_type"), rs.getString("strategy"),
                        rs.getBoolean("active"), entries))
                .optional();
    }

    /**
     * A page of routing guides, without their vendors.
     *
     * <p>A guide has no code or name of its own: it is its lane and vehicle
     * type, so that is what {@code q} searches -- the origin or destination
     * terminal code, or the vehicle type. Keyed on the guide id, which is
     * unique, so the cursor needs no tie-breaker; the order is stable rather
     * than meaningful, which is all a picker paging through matches needs.
     */
    @PreAuthorize("hasAnyAuthority('ROUTING_GUIDE_MANAGE', 'LOAD_ALLOCATE')")
    public Slice<RoutingGuideView> listGuides(Boolean active, String cursor, Integer limit, String q) {
        int pageSize = Slice.clampLimit(limit);

        List<RoutingGuideView> rows = jdbc.sql("""
                        SELECT g.id, o.code AS origin_code, d.code AS destination_code,
                               g.vehicle_type, g.strategy, g.active
                          FROM routing_guide g
                          JOIN terminal o ON o.id = g.origin_terminal_id
                          JOIN terminal d ON d.id = g.destination_terminal_id
                         WHERE (CAST(:active AS boolean) IS NULL OR g.active = CAST(:active AS boolean))
                           AND (CAST(:cursor AS uuid) IS NULL OR g.id > CAST(:cursor AS uuid))
                           AND (CAST(:q AS text) IS NULL OR lower(o.code) LIKE CAST(:q AS text)
                                OR lower(d.code) LIKE CAST(:q AS text)
                                OR lower(g.vehicle_type) LIKE CAST(:q AS text))
                         ORDER BY g.id
                         LIMIT :limit
                        """)
                .param("active", active)
                .param("cursor", guideCursor(cursor))
                .param("q", Search.containsPattern(q))
                .param("limit", pageSize + 1)
                .query((rs, row) -> new RoutingGuideView(rs.getObject("id", UUID.class),
                        rs.getString("origin_code"), rs.getString("destination_code"),
                        rs.getString("vehicle_type"), rs.getString("strategy"),
                        rs.getBoolean("active"), List.of()))
                .list();

        return Slice.of(rows, pageSize, guide -> guide.id().toString());
    }

    /** A guide cursor is a guide id; anything else was not issued by this listing. */
    private static UUID guideCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(cursor);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolationException("invalid-cursor",
                    "The cursor was not issued by this listing");
        }
    }

    /** The full cascade trail for one allocation, oldest offer first. */
    public List<OfferView> findOffers(UUID allocationId) {
        return jdbc.sql("""
                        SELECT o.id, o.rank_no, o.vendor_partner_id, p.legal_name AS vendor_name,
                               o.offered_at, o.responds_by, o.outcome, o.responded_at
                          FROM allocation_offer o
                          JOIN business_partner p ON p.id = o.vendor_partner_id
                         WHERE o.allocation_id = :allocationId
                           -- A portal vendor sees the offers addressed to them,
                           -- not who else was asked or at which rank.
                           AND (CAST(:partner AS uuid) IS NULL
                                OR o.vendor_partner_id = CAST(:partner AS uuid))
                         ORDER BY o.rank_no
                        """)
                .param("allocationId", allocationId)
                .param("partner", AccessSubject.current().partnerScope())
                .query(OfferView.class)
                .list();
    }
}
