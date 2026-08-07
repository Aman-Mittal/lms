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
package com.lms.sourcing.command;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Awards per vendor per lane per calendar month, for the round-robin share.
 *
 * <p>A running tally rather than a count over the offer history. The history is
 * pruned eventually, and "fair share this month" must not quietly change
 * meaning the day old offers are deleted.
 *
 * <p>Plain SQL rather than a repository: the table is a counter with a
 * composite key and no surrogate id, so modelling it as an entity would mean
 * inventing an identifier nothing uses.
 */
@Component
public class VendorAwardTally {

    private final JdbcClient jdbc;

    public VendorAwardTally(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Awards so far in the month containing {@code on}, by vendor. */
    public Map<UUID, Integer> countsFor(UUID tenantId, UUID guideId, LocalDate on) {
        Map<UUID, Integer> counts = new HashMap<>();
        jdbc.sql("""
                        SELECT vendor_partner_id, awarded_count
                          FROM vendor_award_tally
                         WHERE tenant_id = :tenantId
                           AND guide_id = :guideId
                           AND period_month = :period
                        """)
                .param("tenantId", tenantId)
                .param("guideId", guideId)
                .param("period", monthOf(on))
                .query()
                .listOfRows()
                .forEach(row -> counts.put((UUID) row.get("vendor_partner_id"),
                        ((Number) row.get("awarded_count")).intValue()));
        return counts;
    }

    /**
     * Records an award.
     *
     * <p>An upsert rather than a read-then-write. Two allocations for the same
     * lane completing at once would both read the same count and both write it
     * back plus one, losing an award and skewing the share for the rest of the
     * month. Letting the primary key arbitrate makes that race impossible.
     */
    public void recordAward(UUID tenantId, UUID guideId, UUID vendorPartnerId, LocalDate on) {
        jdbc.sql("""
                        INSERT INTO vendor_award_tally
                            (tenant_id, guide_id, period_month, vendor_partner_id, awarded_count)
                        VALUES (:tenantId, :guideId, :period, :vendorId, 1)
                        ON CONFLICT (guide_id, period_month, vendor_partner_id)
                        DO UPDATE SET awarded_count = vendor_award_tally.awarded_count + 1
                        """)
                .param("tenantId", tenantId)
                .param("guideId", guideId)
                .param("period", monthOf(on))
                .param("vendorId", vendorPartnerId)
                .update();
    }

    /** The first of the month, so the period is a value rather than a range. */
    private static LocalDate monthOf(LocalDate date) {
        return date.withDayOfMonth(1);
    }
}
