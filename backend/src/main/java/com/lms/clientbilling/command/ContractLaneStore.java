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
package com.lms.clientbilling.command;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ContractLane;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads and writes the lanes of contract versions, with their slabs and party
 * lists, as the one value each lane is.
 *
 * <p>Plain SQL rather than repositories: a lane's slabs and consignee lists
 * are written and read together with it, and the party table has no identifier
 * of its own. The database refuses any write to the lanes of a version that
 * is not a draft, so this class never needs to ask.
 */
@Component
public class ContractLaneStore {

    private static final String SUCCESSOR_SETTING = "app.contract_successor";

    private final JdbcClient jdbc;

    public ContractLaneStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record LaneRow(UUID id, UUID contractId, String laneCode, UUID originTerminalId, String originZone,
                           UUID destinationTerminalId, String destinationZone, String freightMode, String loadType,
                           String rateBasis, BigDecimal rate, Integer transitDays) {
    }

    private record SlabRow(UUID laneId, BigDecimal minWeightKg, BigDecimal maxWeightKg, String rateBasis,
                           BigDecimal rate) {
    }

    private record PartyRow(UUID laneId, String role, UUID partnerId) {
    }

    /** The lanes of one version, by code. */
    public List<ContractLane> lanesOf(UUID contractId) {
        return lanesOf(List.of(contractId)).getOrDefault(contractId, List.of());
    }

    /** The lanes of several versions, each list by code. A version with none is absent. */
    public Map<UUID, List<ContractLane>> lanesOf(Collection<UUID> contractIds) {
        if (contractIds.isEmpty()) {
            return Map.of();
        }
        List<LaneRow> rows = jdbc.sql("""
                        SELECT id, contract_id, lane_code, origin_terminal_id, origin_zone,
                               destination_terminal_id, destination_zone, freight_mode, load_type,
                               rate_basis, rate, transit_days
                          FROM client_contract_lane
                         WHERE tenant_id = :tenantId AND contract_id IN (:ids)
                         ORDER BY contract_id, lane_code
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("ids", contractIds)
                .query(LaneRow.class).list();
        return assemble(rows);
    }

    /** One lane by id, wherever its version. */
    public Optional<ContractLane> find(UUID laneId) {
        List<LaneRow> rows = jdbc.sql("""
                        SELECT id, contract_id, lane_code, origin_terminal_id, origin_zone,
                               destination_terminal_id, destination_zone, freight_mode, load_type,
                               rate_basis, rate, transit_days
                          FROM client_contract_lane
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("id", laneId)
                .query(LaneRow.class).list();
        return assemble(rows).values().stream().flatMap(List::stream).findFirst();
    }

    private Map<UUID, List<ContractLane>> assemble(List<LaneRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        UUID tenantId = TenantContext.requireTenantId();
        List<UUID> ids = rows.stream().map(LaneRow::id).toList();
        Map<UUID, List<ContractLane.Slab>> slabs = new HashMap<>();
        jdbc.sql("""
                        SELECT lane_id, min_weight_kg, max_weight_kg, rate_basis, rate
                          FROM client_contract_lane_slab
                         WHERE tenant_id = :tenantId AND lane_id IN (:ids)
                         ORDER BY lane_id, min_weight_kg
                        """)
                .param("tenantId", tenantId).param("ids", ids)
                .query(SlabRow.class).list()
                .forEach(s -> slabs.computeIfAbsent(s.laneId(), k -> new ArrayList<>())
                        .add(new ContractLane.Slab(s.minWeightKg(), s.maxWeightKg(),
                                ContractLane.RateBasis.valueOf(s.rateBasis()), s.rate())));
        Map<UUID, Set<UUID>> consignors = new HashMap<>();
        Map<UUID, Set<UUID>> consignees = new HashMap<>();
        jdbc.sql("""
                        SELECT lane_id, role, partner_id
                          FROM client_contract_lane_party
                         WHERE tenant_id = :tenantId AND lane_id IN (:ids)
                         ORDER BY lane_id, role, partner_id
                        """)
                .param("tenantId", tenantId).param("ids", ids)
                .query(PartyRow.class).list()
                .forEach(p -> ("CONSIGNOR".equals(p.role()) ? consignors : consignees)
                        .computeIfAbsent(p.laneId(), k -> new LinkedHashSet<>()).add(p.partnerId()));

        Map<UUID, List<ContractLane>> byContract = new HashMap<>();
        for (LaneRow r : rows) {
            ContractLane lane = new ContractLane(r.id(), r.contractId(), r.laneCode(),
                    new ContractLane.Place(r.originTerminalId(), r.originZone()),
                    new ContractLane.Place(r.destinationTerminalId(), r.destinationZone()), r.freightMode(),
                    r.loadType(), ContractLane.RateBasis.valueOf(r.rateBasis()), r.rate(), r.transitDays(),
                    slabs.get(r.id()), consignors.get(r.id()), consignees.get(r.id()));
            byContract.computeIfAbsent(r.contractId(), k -> new ArrayList<>()).add(lane);
        }
        return byContract;
    }

    /** Writes lanes under a version: the version must be a draft, or the database refuses. */
    public void insertAll(UUID contractId, List<ContractLane> lanes) {
        UUID tenantId = TenantContext.requireTenantId();
        for (ContractLane lane : lanes) {
            jdbc.sql("""
                            INSERT INTO client_contract_lane (id, tenant_id, contract_id, lane_code,
                                origin_terminal_id, origin_zone, destination_terminal_id, destination_zone,
                                freight_mode, load_type, rate_basis, rate, transit_days)
                            VALUES (:id, :tenantId, :contractId, :code, :originTerminal, :originZone,
                                :destinationTerminal, :destinationZone, :mode, :loadType, :basis, :rate, :days)
                            """)
                    .param("id", lane.id()).param("tenantId", tenantId).param("contractId", contractId)
                    .param("code", lane.laneCode())
                    .param("originTerminal", lane.origin().terminalId())
                    .param("originZone", lane.origin().zone())
                    .param("destinationTerminal", lane.destination().terminalId())
                    .param("destinationZone", lane.destination().zone())
                    .param("mode", lane.freightMode()).param("loadType", lane.loadType())
                    .param("basis", lane.rateBasis().name()).param("rate", lane.rate())
                    .param("days", lane.transitDays())
                    .update();
            for (ContractLane.Slab slab : lane.slabs()) {
                jdbc.sql("""
                                INSERT INTO client_contract_lane_slab (id, tenant_id, lane_id, min_weight_kg,
                                    max_weight_kg, rate_basis, rate)
                                VALUES (:id, :tenantId, :laneId, :min, :max, :basis, :rate)
                                """)
                        .param("id", UUID.randomUUID()).param("tenantId", tenantId).param("laneId", lane.id())
                        .param("min", slab.minWeightKg()).param("max", slab.maxWeightKg())
                        .param("basis", slab.rateBasis().name()).param("rate", slab.rate())
                        .update();
            }
            insertParties(tenantId, lane.id(), "CONSIGNOR", lane.consignors());
            insertParties(tenantId, lane.id(), "CONSIGNEE", lane.consignees());
        }
    }

    private void insertParties(UUID tenantId, UUID laneId, String role, Set<UUID> partners) {
        for (UUID partner : partners) {
            jdbc.sql("""
                            INSERT INTO client_contract_lane_party (tenant_id, lane_id, role, partner_id)
                            VALUES (:tenantId, :laneId, :role, :partner)
                            """)
                    .param("tenantId", tenantId).param("laneId", laneId).param("role", role)
                    .param("partner", partner).update();
        }
    }

    /** Removes every lane of a draft, children first. */
    public void deleteAllOf(UUID contractId) {
        UUID tenantId = TenantContext.requireTenantId();
        jdbc.sql("""
                        DELETE FROM client_contract_lane_party WHERE tenant_id = :tenantId
                           AND lane_id IN (SELECT id FROM client_contract_lane WHERE contract_id = :contractId)
                        """).param("tenantId", tenantId).param("contractId", contractId).update();
        jdbc.sql("""
                        DELETE FROM client_contract_lane_slab WHERE tenant_id = :tenantId
                           AND lane_id IN (SELECT id FROM client_contract_lane WHERE contract_id = :contractId)
                        """).param("tenantId", tenantId).param("contractId", contractId).update();
        jdbc.sql("DELETE FROM client_contract_lane WHERE tenant_id = :tenantId AND contract_id = :contractId")
                .param("tenantId", tenantId).param("contractId", contractId).update();
    }

    /**
     * Copies a version's lanes under its successor, the one write a version in
     * force accepts, and only inside this transaction (the database setting is
     * local to it and switched off again).
     */
    public void copyAll(UUID fromContractId, UUID toContractId) {
        List<ContractLane> copies = lanesOf(fromContractId).stream()
                .map(lane -> lane.copiedTo(UUID.randomUUID(), toContractId)).toList();
        if (copies.isEmpty()) {
            return;
        }
        jdbc.sql("SELECT set_config('" + SUCCESSOR_SETTING + "', 'on', true)").query(String.class).single();
        try {
            insertAll(toContractId, copies);
        } finally {
            jdbc.sql("SELECT set_config('" + SUCCESSOR_SETTING + "', 'off', true)").query(String.class).single();
        }
    }
}
