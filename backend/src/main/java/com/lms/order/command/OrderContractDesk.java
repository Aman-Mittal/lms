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
package com.lms.order.command;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.masterdata.api.TerminalGeofencePort;
import com.lms.order.api.OrderContractPort;
import com.lms.order.command.domain.OrderLine;
import com.lms.order.command.domain.SalesOrder;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.Settings;
import com.lms.shared.geo.GeoUtils;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.geo.LatLon;
import org.springframework.stereotype.Component;

/**
 * Where an order meets its client contract: which version and lane it is raised
 * under, and how many days it is promised to take.
 *
 * <p>Kept apart from {@link OrderCommandService}, like {@link OrderCreditGate},
 * so the order's own rules and the commercial agreement it is judged by can be
 * read and changed separately. The contract's owner answers through
 * {@link OrderContractPort}; with none in force (or no implementation at all,
 * as in a slice of the application) the order is accepted as it always was.
 *
 * <p>Transit days are the lane's, else the straight-line distance between the
 * order's origin and the farthest destination of its lines divided by the
 * {@code order.transit.kmPerDay} setting, rounded up to at least a day.
 */
@Component
class OrderContractDesk {

    private final java.util.Optional<OrderContractPort> contracts;
    private final TerminalGeofencePort terminals;
    private final Settings settings;

    OrderContractDesk(java.util.Optional<OrderContractPort> contracts, TerminalGeofencePort terminals,
                      Settings settings) {
        this.contracts = contracts;
        this.terminals = terminals;
        this.settings = settings;
    }

    /**
     * The order with its contract, lane and transit days resolved from what it
     * holds now, or unchanged (all cleared) when the customer has no contract in
     * force. Throws the contract's refusals.
     *
     * @param laneChoice the lane the person chose, or null to have one found
     * @param legs       where each line is delivered and to whom; empty while only
     *                   the header is being judged
     */
    SalesOrder resolved(SalesOrder order, UUID laneChoice, List<OrderContractPort.Leg> legs) {
        Optional<OrderContractPort.Resolution> found = contracts.flatMap(port -> port.resolve(
                new OrderContractPort.Request(order.customerPartnerId(), orderDate(order),
                        order.originTerminalId(), order.freightMode() == null ? null : order.freightMode().name(),
                        order.loadType(), laneChoice, legs)));
        if (found.isEmpty() && laneChoice != null) {
            throw new BusinessRuleViolationException("order-outside-contract-lanes",
                    "A contract lane was chosen, but the customer has no contract in force on "
                            + orderDate(order));
        }
        if (found.isEmpty()) {
            return order.withContract(null, null, null, transitFromDistance(order, legs), false);
        }
        OrderContractPort.Resolution r = found.get();
        Integer days = r.transitDays() != null ? r.transitDays() : transitFromDistance(order, legs);
        return order.withContract(r.contractId(), r.contractFamilyId(), r.laneId(), days, laneChoice != null);
    }

    /** The legs of an order's lines that still stand. */
    static List<OrderContractPort.Leg> legsOfLines(List<OrderLine> lines) {
        List<OrderContractPort.Leg> legs = new ArrayList<>();
        for (OrderLine line : lines) {
            if (!line.cancelled()) {
                legs.add(new OrderContractPort.Leg(line.destinationTerminalId(), line.consigneePartnerId()));
            }
        }
        return legs;
    }

    /** The legs of lines about to be stored. */
    static List<OrderContractPort.Leg> legsOfSpecs(List<OrderLineSpec> specs) {
        return specs.stream().map(spec -> new OrderContractPort.Leg(spec.destinationTerminalId(),
                spec.consigneePartnerId())).toList();
    }

    /**
     * The day an order is judged by: its requested pickup day, else today. A
     * back-dated order therefore finds the contract version in force then.
     */
    static LocalDate orderDate(SalesOrder order) {
        Instant pickup = order.requestedPickupAt();
        return (pickup == null ? Instant.now() : pickup).atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** The setting context of an order: its unit, customer, mode and contract. */
    static SettingContext contextOf(SalesOrder order) {
        return SettingContext.ofOrgUnit(order.orgUnitId()).withCustomer(order.customerPartnerId())
                .withFreightMode(order.freightMode() == null ? null : order.freightMode().name())
                .withContract(order.contractFamilyId());
    }

    /**
     * Days from distance: the farthest line destination from the origin, over the
     * tenant's kilometres per day; null while no destination or no terminal
     * position is known.
     */
    private Integer transitFromDistance(SalesOrder order, List<OrderContractPort.Leg> legs) {
        Optional<LatLon> origin = terminals.centreOf(order.originTerminalId());
        if (origin.isEmpty()) {
            return null;
        }
        double farthestMetres = -1;
        for (OrderContractPort.Leg leg : legs) {
            Optional<LatLon> destination = terminals.centreOf(leg.destinationTerminalId());
            if (destination.isPresent()) {
                farthestMetres = Math.max(farthestMetres, GeoUtils.haversineMetres(origin.get(), destination.get()));
            }
        }
        if (farthestMetres < 0) {
            return null;
        }
        int kmPerDay = settings.effective(OrderSettingDefinitions.TRANSIT_KM_PER_DAY, contextOf(order));
        return daysFor(farthestMetres / 1000.0, kmPerDay);
    }

    /** Whole days to cover {@code km} at {@code kmPerDay}, rounded up, at least one. */
    static int daysFor(double km, int kmPerDay) {
        BigDecimal days = BigDecimal.valueOf(km).divide(BigDecimal.valueOf(Math.max(1, kmPerDay)), 0,
                RoundingMode.CEILING);
        return Math.max(1, days.intValue());
    }
}
