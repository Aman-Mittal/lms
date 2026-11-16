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
package com.lms.statutory.command;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The e-way bill rows, with plain SQL under the caller's tenant scope. Every
 * change is conditional on the version read, so two people acting on one bill
 * at once cannot both win: the loser gets a stale-write failure, which the API
 * answers as 409 {@code concurrent-modification}.
 */
@Component
class EwayBillStore {

    /** A bill as the command side needs it. */
    record Bill(UUID id, UUID orgUnitId, UUID consignmentId, String billNo, EwayBillStatus status,
                Instant generatedAt, Instant validUntil, String vehicleNo, Integer distanceKm, String source,
                int extensionCount, long version) {
    }

    private static final String COLUMNS = """
            id, org_unit_id, consignment_id, bill_no, status, generated_at, valid_until, vehicle_no,
            distance_km, source, extension_count, version
            """;

    private final JdbcClient jdbc;

    EwayBillStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Bill> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM eway_bill WHERE id = :id AND tenant_id = app_current_tenant()")
                .param("id", id).query(Bill.class).optional();
    }

    Optional<Bill> liveOf(UUID consignmentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM eway_bill WHERE tenant_id = app_current_tenant()"
                        + " AND consignment_id = :consignmentId AND status IN ('ACTIVE', 'EXTENDED')")
                .param("consignmentId", consignmentId).query(Bill.class).optional();
    }

    /** The live bill of each consignment that has one, by consignment id. */
    Map<UUID, Bill> liveOf(Collection<UUID> consignmentIds) {
        if (consignmentIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM eway_bill WHERE tenant_id = app_current_tenant()"
                        + " AND consignment_id IN (:ids) AND status IN ('ACTIVE', 'EXTENDED')")
                .param("ids", consignmentIds).query(Bill.class).list().stream()
                .collect(Collectors.toMap(Bill::consignmentId, bill -> bill));
    }

    /** How many bills the consignment has had, live or not. */
    int countFor(UUID consignmentId) {
        return jdbc.sql("SELECT count(*) FROM eway_bill WHERE tenant_id = app_current_tenant()"
                        + " AND consignment_id = :consignmentId")
                .param("consignmentId", consignmentId).query(Integer.class).single();
    }

    boolean numberTaken(String billNo) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM eway_bill WHERE tenant_id = app_current_tenant()"
                        + " AND bill_no = :billNo)")
                .param("billNo", billNo).query(Boolean.class).single();
    }

    void insert(UUID id, UUID tenantId, UUID orgUnitId, UUID consignmentId, String billNo, Instant generatedAt,
                Instant validUntil, String vehicleNo, Integer distanceKm, String source, UUID createdBy) {
        jdbc.sql("""
                        INSERT INTO eway_bill (id, tenant_id, org_unit_id, consignment_id, bill_no, status,
                               generated_at, valid_until, vehicle_no, vehicle_updated_at, distance_km, source,
                               created_by, version)
                        VALUES (:id, :tenantId, :orgUnitId, :consignmentId, :billNo, 'ACTIVE', :generatedAt,
                                :validUntil, :vehicleNo, CASE WHEN CAST(:vehicleNo AS text) IS NULL THEN NULL
                                ELSE :generatedAt END, :distanceKm, :source, :createdBy, 0)
                        """)
                .param("id", id).param("tenantId", tenantId).param("orgUnitId", orgUnitId)
                .param("consignmentId", consignmentId).param("billNo", billNo)
                .param("generatedAt", generatedAt.atOffset(ZoneOffset.UTC))
                .param("validUntil", validUntil.atOffset(ZoneOffset.UTC))
                .param("vehicleNo", vehicleNo).param("distanceKm", distanceKm).param("source", source)
                .param("createdBy", createdBy).update();
    }

    void setVehicle(Bill bill, String vehicleNo, Instant at) {
        write(bill, "vehicle_no = :vehicleNo, vehicle_updated_at = :at",
                Map.of("vehicleNo", vehicleNo, "at", at.atOffset(ZoneOffset.UTC)));
    }

    void extend(Bill bill, Instant validUntil) {
        write(bill, "status = 'EXTENDED', valid_until = :validUntil, extension_count = extension_count + 1,"
                + " expiring_flagged_at = NULL", Map.of("validUntil", validUntil.atOffset(ZoneOffset.UTC)));
    }

    void cancel(Bill bill, String reason, Instant at) {
        write(bill, "status = 'CANCELLED', cancelled_at = :at, cancel_reason = :reason, expiring_flagged_at = NULL",
                Map.of("reason", reason, "at", at.atOffset(ZoneOffset.UTC)));
    }

    void expire(Bill bill, Instant at) {
        write(bill, "status = 'EXPIRED', expired_at = :at, expiring_flagged_at = NULL",
                Map.of("at", at.atOffset(ZoneOffset.UTC)));
    }

    void flagExpiring(Bill bill, Instant at) {
        write(bill, "expiring_flagged_at = :at", Map.of("at", at.atOffset(ZoneOffset.UTC)));
    }

    /** Applies the assignments if the row is still at the version read; otherwise a stale write. */
    private void write(Bill bill, String assignments, Map<String, Object> values) {
        JdbcClient.StatementSpec spec = jdbc.sql("UPDATE eway_bill SET " + assignments
                        + ", version = version + 1, updated_at = now() WHERE id = :id"
                        + " AND tenant_id = app_current_tenant() AND version = :version")
                .param("id", bill.id()).param("version", bill.version());
        for (Map.Entry<String, Object> value : values.entrySet()) {
            spec = spec.param(value.getKey(), value.getValue());
        }
        if (spec.update() != 1) {
            throw stale(bill.id());
        }
    }

    /** Live bills past their validity, oldest first, locked for the sweep. */
    List<Bill> lapsed(Instant now, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM eway_bill WHERE tenant_id = app_current_tenant()"
                        + " AND status IN ('ACTIVE', 'EXTENDED') AND valid_until <= :now"
                        + " ORDER BY valid_until, id LIMIT :limit FOR UPDATE SKIP LOCKED")
                .param("now", now.atOffset(ZoneOffset.UTC)).param("limit", limit).query(Bill.class).list();
    }

    /** Live, not yet flagged bills that lapse before {@code horizon}, soonest first, locked for the sweep. */
    List<Bill> unflaggedBefore(Instant now, Instant horizon, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM eway_bill WHERE tenant_id = app_current_tenant()"
                        + " AND status IN ('ACTIVE', 'EXTENDED') AND expiring_flagged_at IS NULL"
                        + " AND valid_until > :now AND valid_until <= :horizon"
                        + " ORDER BY valid_until, id LIMIT :limit FOR UPDATE SKIP LOCKED")
                .param("now", now.atOffset(ZoneOffset.UTC)).param("horizon", horizon.atOffset(ZoneOffset.UTC))
                .param("limit", limit).query(Bill.class).list();
    }

    static OptimisticLockingFailureException stale(UUID id) {
        return new OptimisticLockingFailureException("E-way bill " + id + " was changed by someone else");
    }
}
