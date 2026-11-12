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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.clientbilling.command.domain.ContractLane;
import com.lms.clientbilling.command.domain.LaneMatching;
import com.lms.order.api.OrderContractPort;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves {@link OrderContractPort}: which contract, and which lane of it, an
 * order is raised under.
 *
 * <p>The version in force on the order's date answers, so an order dated in
 * the past is judged by the terms of that day and not today's. With no
 * contract in force the answer is empty and nothing changes for the order. With
 * one, its load types are checked (always), and then the lane: the one the
 * person chose is held to every rule, otherwise the first lane that serves the
 * order is found. An order no lane serves is accepted as a direct order when
 * the contract allows direct orders, and refused by name when it does not.
 * Before any line exists only the header can be judged, so no lane is assigned
 * until the order has lines.
 *
 * <p>The zones a terminal belongs to are read with SQL from the LTL zone
 * register, like the other facts this module reads across aggregates.
 */
@Component
class OrderContractResolver implements OrderContractPort {

    private final ClientContractRepository contracts;
    private final ContractLaneStore laneStore;
    private final JdbcClient jdbc;

    OrderContractResolver(ClientContractRepository contracts, ContractLaneStore laneStore, JdbcClient jdbc) {
        this.contracts = contracts;
        this.laneStore = laneStore;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Resolution> resolve(Request request) {
        UUID tenantId = TenantContext.requireTenantId();
        Optional<ClientContract> found = contracts.findInForceOn(tenantId, request.customerPartnerId(),
                request.orderDate());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ClientContract contract = found.get();
        LaneMatching.requireLoadTypeAllowed(contract.allowedLoadTypes(), request.loadType());

        List<ContractLane> lanes = laneStore.lanesOf(contract.id());
        LaneMatching.Order order = orderOf(request);

        if (request.requestedLane() != null) {
            ContractLane lane = LaneMatching.byId(lanes, request.requestedLane()).orElseThrow(() ->
                    new BusinessRuleViolationException(LaneMatching.OUTSIDE_LANES,
                            "The chosen lane is not a lane of contract " + contract.code()
                                    + ", the version in force on " + request.orderDate()));
            LaneMatching.require(lane, order);
            return Optional.of(answer(contract, lane));
        }
        if (request.legs().isEmpty()) {
            return Optional.of(answer(contract, null));
        }
        LaneMatching.Outcome outcome = LaneMatching.match(lanes, order);
        if (outcome.matched()) {
            return Optional.of(answer(contract, outcome.lane()));
        }
        if (contract.directOrdersAllowed()) {
            return Optional.of(answer(contract, null));
        }
        throw new BusinessRuleViolationException(outcome.refusal(), outcome.detail() + " (contract "
                + contract.code() + " does not allow direct orders)");
    }

    private static Resolution answer(ClientContract contract, ContractLane lane) {
        return new Resolution(contract.id(), contract.familyKey(), contract.code(),
                lane == null ? null : lane.id(), lane == null ? null : lane.laneCode(),
                lane == null ? null : lane.transitDays());
    }

    private LaneMatching.Order orderOf(Request request) {
        List<UUID> terminals = new ArrayList<>();
        terminals.add(request.originTerminalId());
        request.legs().forEach(leg -> terminals.add(leg.destinationTerminalId()));
        Map<UUID, String> zones = zonesOf(terminals);
        return new LaneMatching.Order(site(request.originTerminalId(), zones), request.freightMode(),
                com.lms.clientbilling.command.domain.ContractLane.normaliseLoadType(request.loadType()),
                request.legs().stream().map(leg -> new LaneMatching.Leg(site(leg.destinationTerminalId(), zones),
                        leg.consigneePartnerId())).toList());
    }

    private static LaneMatching.Site site(UUID terminalId, Map<UUID, String> zones) {
        return new LaneMatching.Site(terminalId, zones.get(terminalId));
    }

    private Map<UUID, String> zonesOf(List<UUID> terminalIds) {
        Set<UUID> distinct = Set.copyOf(terminalIds.stream().filter(java.util.Objects::nonNull).toList());
        Map<UUID, String> zones = new HashMap<>();
        if (distinct.isEmpty()) {
            return zones;
        }
        jdbc.sql("""
                        SELECT terminal_id, zone_code FROM ltl_zone_terminal
                         WHERE tenant_id = :tenantId AND terminal_id IN (:ids)
                        """)
                .param("tenantId", TenantContext.requireTenantId()).param("ids", distinct)
                .query((rs, row) -> {
                    zones.put(rs.getObject("terminal_id", UUID.class), rs.getString("zone_code"));
                    return null;
                }).list();
        return zones;
    }
}
