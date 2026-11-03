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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.identity.access.AccessSubject;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for road rate cards: every version, newest first, and one card
 * with its weight bands.
 *
 * <p>Readable by whoever manages rate cards and by whoever reads freight
 * bills, since a bill names the card it was priced on and settling one means
 * checking that card.
 *
 * <p>Scoped twice, both as predicates in the query so a page is a full page of
 * what the caller may see:
 * <ul>
 *   <li>by organisation: a card is visible along the caller's line of the
 *       tree. A card published under one of the caller's ancestors applies to
 *       the caller's unit, so it is visible; a card published beneath the
 *       caller is in the subtree the caller may publish into, so it is visible
 *       too. A card of a sibling branch is not found rather than forbidden.
 *       Both directions are prefix comparisons of materialised paths.</li>
 *   <li>by partner: a vendor's own staff see only that vendor's cards, as with
 *       its bills.</li>
 * </ul>
 * Both fall away for a call with no organisation or partner bound.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyAuthority('TARIFF_MANAGE', 'INVOICE_READ')")
public class TariffQueryService {

    private static final String SELECT = """
            SELECT t.id, t.org_unit_id, ou.name AS org_unit_name, t.code,
                   t.vendor_partner_id, vp.code AS vendor_code, vp.legal_name AS vendor_name,
                   t.origin_terminal_id, o.code AS origin_terminal_code,
                   t.destination_terminal_id, d.code AS destination_terminal_code,
                   t.vehicle_type, t.currency, t.effective_from, t.effective_to,
                   (t.effective_to IS NOT NULL) AS superseded,
                   t.detention_free_hours, t.detention_hourly_rate, t.additional_drop_fee,
                   t.minimum_charge,
                   (SELECT count(*) FROM tariff_slab s
                     WHERE s.tenant_id = t.tenant_id AND s.tariff_id = t.id)::int AS slab_count,
                   t.created_at
              FROM tariff t
              JOIN org_unit ou ON ou.id = t.org_unit_id
              JOIN business_partner vp ON vp.id = t.vendor_partner_id
              JOIN terminal o ON o.id = t.origin_terminal_id
              JOIN terminal d ON d.id = t.destination_terminal_id
            """;

    private static final String SCOPE = """
               AND (CAST(:orgPath AS text) IS NULL
                    OR ou.path LIKE CAST(:orgPath AS text) || '%'
                    OR CAST(:orgPath AS text) LIKE ou.path || '%')
               AND (CAST(:partner AS uuid) IS NULL OR t.vendor_partner_id = CAST(:partner AS uuid))
            """;

    private final JdbcClient jdbc;

    public TariffQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One card with its bands; empty when it is unknown or not the caller's to see. */
    public Optional<TariffCardView> findById(UUID id) {
        AccessSubject subject = AccessSubject.current();
        return jdbc.sql(SELECT + " WHERE t.id = :id" + SCOPE)
                .param("id", id)
                .param("orgPath", subject.orgPath())
                .param("partner", subject.partnerScope())
                .query(TariffView.class)
                .optional()
                .map(card -> TariffCardView.of(card, slabsOf(card.id())));
    }

    /**
     * A page of card versions, newest first; served by tariff_listing_idx.
     *
     * @param superseded true for closed versions only, false for open ones, null for both
     */
    public Slice<TariffView> list(UUID vendorPartnerId, UUID originTerminalId,
                                  UUID destinationTerminalId, String vehicleType,
                                  Boolean superseded, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Cursor after = Cursor.parse(cursor);
        AccessSubject subject = AccessSubject.current();

        List<TariffView> rows = jdbc.sql(SELECT + """
                         WHERE (CAST(:vendor AS uuid) IS NULL OR t.vendor_partner_id = CAST(:vendor AS uuid))
                           AND (CAST(:origin AS uuid) IS NULL OR t.origin_terminal_id = CAST(:origin AS uuid))
                           AND (CAST(:destination AS uuid) IS NULL
                                OR t.destination_terminal_id = CAST(:destination AS uuid))
                           AND (CAST(:vehicleType AS text) IS NULL OR t.vehicle_type = CAST(:vehicleType AS text))
                           AND (CAST(:superseded AS boolean) IS NULL
                                OR (t.effective_to IS NOT NULL) = CAST(:superseded AS boolean))
                           AND (CAST(:afterAt AS timestamptz) IS NULL
                                OR (t.created_at, t.id) < (CAST(:afterAt AS timestamptz),
                                                           CAST(:afterId AS uuid)))
                        """ + SCOPE + """
                         ORDER BY t.created_at DESC, t.id DESC
                         LIMIT :limit
                        """)
                .param("vendor", vendorPartnerId)
                .param("origin", originTerminalId)
                .param("destination", destinationTerminalId)
                .param("vehicleType", vehicleType == null || vehicleType.isBlank() ? null : vehicleType)
                .param("superseded", superseded)
                .param("afterAt", after == null ? null : after.createdAt())
                .param("afterId", after == null ? null : after.id())
                .param("orgPath", subject.orgPath())
                .param("partner", subject.partnerScope())
                .param("limit", pageSize + 1)
                .query(TariffView.class)
                .list();

        return Slice.of(rows, pageSize, view -> new Cursor(
                view.createdAt().atOffset(ZoneOffset.UTC), view.id()).encode());
    }

    private List<TariffSlabView> slabsOf(UUID tariffId) {
        return jdbc.sql("""
                        SELECT id, min_weight_kg, max_weight_kg, rate_basis, rate_amount
                          FROM tariff_slab
                         WHERE tariff_id = :tariffId
                         ORDER BY min_weight_kg
                        """)
                .param("tariffId", tariffId)
                .query(TariffSlabView.class)
                .list();
    }

    /** Where the previous page stopped: the creation instant and id of its last card. */
    record Cursor(OffsetDateTime createdAt, UUID id) {

        String encode() {
            return createdAt.toInstant() + "_" + id;
        }

        static Cursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            int split = cursor.lastIndexOf('_');
            try {
                return new Cursor(Instant.parse(cursor.substring(0, split)).atOffset(ZoneOffset.UTC),
                        UUID.fromString(cursor.substring(split + 1)));
            } catch (DateTimeParseException | IllegalArgumentException
                     | StringIndexOutOfBoundsException e) {
                throw new BusinessRuleViolationException("invalid-cursor",
                        "The cursor was not issued by this listing");
            }
        }
    }
}
