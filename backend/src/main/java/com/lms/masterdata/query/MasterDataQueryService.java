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
 */package com.lms.masterdata.query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for master data (vision document 3.2).
 *
 * <p>Written when the REST layer needed it and not before, which is why it
 * arrives after the module it reads. The command side had been driven directly
 * by the acceptance suite, and a suite that creates a partner and then asserts
 * on the aggregate it just created never notices that nobody can list partners.
 *
 * <p>Every listing is keyset-paginated on a column with a unique index, so a
 * page costs the same wherever it falls. The cursor columns are
 * {@code business_partner.code}, {@code vehicle.registration_no},
 * {@code driver.licence_no} and {@code terminal.code} -- each unique per tenant
 * by an existing constraint, which is what makes them safe to seek on.
 */
@Service
@Transactional(readOnly = true)
public class MasterDataQueryService {

    private final JdbcClient jdbc;

    public MasterDataQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- partners

    private static final String PARTNER_SELECT = """
            SELECT p.id, p.code, p.legal_name, p.partner_type, p.status, p.tax_id,
                   p.credit_limit, p.payment_terms, p.on_time_pct, p.claims_ratio,
                   p.scorecard_at,
                   coalesce(docs.expired_count, 0) AS expired_document_count,
                   p.created_at
              FROM business_partner p
              LEFT JOIN LATERAL (
                    SELECT count(*)::int AS expired_count
                      FROM compliance_document d
                     WHERE d.owner_type = 'PARTNER' AND d.owner_id = p.id
                       AND d.expires_on IS NOT NULL AND d.expires_on < current_date
                   ) docs ON true
            """;

    @PreAuthorize("hasAuthority('PARTNER_READ')")
    public Optional<PartnerView> findPartner(UUID id) {
        return jdbc.sql(PARTNER_SELECT + " WHERE p.id = :id")
                .param("id", id)
                .query(PartnerView.class)
                .optional();
    }

    @PreAuthorize("hasAuthority('PARTNER_READ')")
    public Optional<PartnerView> findPartnerByCode(String code) {
        return jdbc.sql(PARTNER_SELECT + " WHERE p.code = :code")
                .param("code", code)
                .query(PartnerView.class)
                .optional();
    }

    @PreAuthorize("hasAuthority('PARTNER_READ')")
    public Slice<PartnerView> listPartners(String partnerType, String status,
                                           String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);

        List<PartnerView> rows = jdbc.sql(PARTNER_SELECT + """
                         WHERE (CAST(:partnerType AS text) IS NULL
                                OR p.partner_type = CAST(:partnerType AS text))
                           AND (CAST(:status AS text) IS NULL OR p.status = CAST(:status AS text))
                           AND (CAST(:cursor AS text) IS NULL OR p.code > CAST(:cursor AS text))
                         ORDER BY p.code
                         LIMIT :limit
                        """)
                .param("partnerType", partnerType)
                .param("status", status)
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query(PartnerView.class)
                .list();

        return Slice.of(rows, pageSize, PartnerView::code);
    }

    // ------------------------------------------------------------- vehicles

    private static final String VEHICLE_SELECT = """
            SELECT v.id, v.registration_no, v.category, v.vehicle_type, v.axle_config, v.status,
                   v.gross_weight_kg, v.tare_weight_kg,
                   -- What planning actually loads against: what the lorry can
                   -- carry once it has carried itself.
                   (v.gross_weight_kg - v.tare_weight_kg) AS payload_capacity_kg,
                   v.max_volume_m3, v.hazmat_certified, v.reefer_capable,
                   v.owner_partner_id, op.legal_name AS owner_partner_name,
                   docs.earliest_expiry AS earliest_document_expiry,
                   coalesce(docs.expired_count, 0) AS expired_document_count,
                   v.created_at
              FROM vehicle v
              LEFT JOIN business_partner op ON op.id = v.owner_partner_id
              LEFT JOIN LATERAL (
                    SELECT min(d.expires_on) AS earliest_expiry,
                           count(*) FILTER (WHERE d.expires_on < current_date)::int AS expired_count
                      FROM compliance_document d
                     WHERE d.owner_type = 'VEHICLE' AND d.owner_id = v.id
                       AND d.expires_on IS NOT NULL
                   ) docs ON true
            """;

    @PreAuthorize("hasAuthority('VEHICLE_READ')")
    public Optional<VehicleView> findVehicle(UUID id) {
        return jdbc.sql(VEHICLE_SELECT + " WHERE v.id = :id")
                .param("id", id)
                .query(VehicleView.class)
                .optional();
    }

    @PreAuthorize("hasAuthority('VEHICLE_READ')")
    public Slice<VehicleView> listVehicles(String status, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);

        List<VehicleView> rows = jdbc.sql(VEHICLE_SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR v.status = CAST(:status AS text))
                           AND (CAST(:cursor AS text) IS NULL
                                OR v.registration_no > CAST(:cursor AS text))
                         ORDER BY v.registration_no
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query(VehicleView.class)
                .list();

        return Slice.of(rows, pageSize, VehicleView::registrationNo);
    }

    // -------------------------------------------------------------- drivers

    private static final String DRIVER_SELECT = """
            SELECT d.id, d.full_name, d.phone, d.licence_no, d.licence_class,
                   d.licence_expires_on,
                   (d.licence_expires_on < current_date) AS licence_expired,
                   d.hazmat_endorsed, d.status, d.hos_minutes_today,
                   d.employer_partner_id, ep.legal_name AS employer_partner_name,
                   d.created_at
              FROM driver d
              LEFT JOIN business_partner ep ON ep.id = d.employer_partner_id
            """;

    @PreAuthorize("hasAuthority('DRIVER_READ')")
    public Optional<DriverView> findDriver(UUID id) {
        return jdbc.sql(DRIVER_SELECT + " WHERE d.id = :id")
                .param("id", id)
                .query(DriverView.class)
                .optional();
    }

    @PreAuthorize("hasAuthority('DRIVER_READ')")
    public Slice<DriverView> listDrivers(String status, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);

        // Sorted by licence number rather than by name, which is what a reader
        // would expect. Names are not unique -- two drivers called Ravi Kumar
        // is ordinary -- and a cursor on a non-unique column silently skips
        // rows at every page boundary.
        List<DriverView> rows = jdbc.sql(DRIVER_SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR d.status = CAST(:status AS text))
                           AND (CAST(:cursor AS text) IS NULL OR d.licence_no > CAST(:cursor AS text))
                         ORDER BY d.licence_no
                         LIMIT :limit
                        """)
                .param("status", status)
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query(DriverView.class)
                .list();

        return Slice.of(rows, pageSize, DriverView::licenceNo);
    }

    // ------------------------------------------------------------- terminals

    private static final String TERMINAL_SELECT = """
            SELECT t.id, t.code, t.name, t.functional_category, t.geofence_type,
                   CAST(t.polygon AS text) AS polygon,
                   t.centre_lat, t.centre_lon, t.radius_m, t.dock_count,
                   t.opens_at, t.closes_at, t.avg_dwell_minutes, t.created_at
              FROM terminal t
            """;

    @PreAuthorize("hasAuthority('TERMINAL_READ')")
    public Optional<TerminalView> findTerminal(UUID id) {
        return jdbc.sql(TERMINAL_SELECT + " WHERE t.id = :id")
                .param("id", id)
                .query(TerminalView.class)
                .optional();
    }

    @PreAuthorize("hasAuthority('TERMINAL_READ')")
    public Slice<TerminalView> listTerminals(String category, String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);

        List<TerminalView> rows = jdbc.sql(TERMINAL_SELECT + """
                         WHERE (CAST(:category AS text) IS NULL
                                OR t.functional_category = CAST(:category AS text))
                           AND (CAST(:cursor AS text) IS NULL OR t.code > CAST(:cursor AS text))
                         ORDER BY t.code
                         LIMIT :limit
                        """)
                .param("category", category)
                .param("cursor", cursor)
                .param("limit", pageSize + 1)
                .query(TerminalView.class)
                .list();

        return Slice.of(rows, pageSize, TerminalView::code);
    }

    // ------------------------------------------------------------ documents

    private static final String DOCUMENT_SELECT = """
            SELECT d.id, d.owner_type, d.owner_id,
                   -- One label whatever the owner is, so a single expiry screen
                   -- can show vehicles, drivers and partners together. Only one
                   -- of the three joins ever matches.
                   coalesce(v.registration_no, dr.full_name, bp.legal_name) AS owner_label,
                   d.document_type, d.document_no, d.issuing_authority,
                   d.issued_on, d.expires_on,
                   (d.expires_on - current_date) AS days_to_expiry,
                   (d.expires_on < current_date) AS expired
              FROM compliance_document d
              LEFT JOIN vehicle v ON d.owner_type = 'VEHICLE' AND v.id = d.owner_id
              LEFT JOIN driver dr ON d.owner_type = 'DRIVER' AND dr.id = d.owner_id
              LEFT JOIN business_partner bp ON d.owner_type = 'PARTNER' AND bp.id = d.owner_id
            """;

    @PreAuthorize("hasAuthority('VEHICLE_READ')")
    public List<ComplianceDocumentView> documentsFor(String ownerType, UUID ownerId) {
        return jdbc.sql(DOCUMENT_SELECT + """
                         WHERE d.owner_type = :ownerType AND d.owner_id = :ownerId
                         ORDER BY d.expires_on NULLS LAST, d.document_type
                        """)
                .param("ownerType", ownerType)
                .param("ownerId", ownerId)
                .query(ComplianceDocumentView.class)
                .list();
    }

    /**
     * Certificates that have lapsed or are about to.
     *
     * <p>The screen that stops a dispatch being refused at the gate. Served by
     * {@code compliance_document_expiry_idx}, which is partial on
     * {@code expires_on IS NOT NULL} -- so the scan covers only documents that
     * can expire, not every document ever filed.
     *
     * <p>Includes documents already expired, deliberately. A list of what is
     * about to lapse that silently omits what already has would answer "is
     * anything wrong" with "no" on the day it matters most.
     */
    @PreAuthorize("hasAuthority('VEHICLE_READ')")
    public List<ComplianceDocumentView> documentsExpiringWithin(int days) {
        return jdbc.sql(DOCUMENT_SELECT + """
                         WHERE d.expires_on IS NOT NULL
                           AND d.expires_on <= current_date + CAST(:days AS int)
                         ORDER BY d.expires_on
                        """)
                .param("days", days)
                .query(ComplianceDocumentView.class)
                .list();
    }
}
