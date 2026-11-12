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
package com.lms.clientbilling.command.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;

/**
 * Which lane of a contract an order travels on, and which refusal it earns
 * when none does.
 *
 * <p>Pure: the zones a terminal belongs to are handed in with the terminals,
 * so every rule is a unit test. A lane serves an order when its origin covers
 * the order's origin terminal, its freight mode and load type agree where the
 * order states them, and every leg of the order -- a destination terminal
 * and the consignee receiving there -- is covered by its destination and its
 * consignee list. The first such lane by code answers: codes are unique within
 * a version, so the choice is stable.
 */
public final class LaneMatching {

    /** The order refers to a place no lane of the contract covers. */
    public static final String OUTSIDE_LANES = "order-outside-contract-lanes";
    /** The order's load type is not one the contract (or lane) allows. */
    public static final String LOAD_TYPE_NOT_ALLOWED = "order-load-type-not-allowed";
    /** A consignee the lane does not list receives on the order. */
    public static final String CONSIGNEE_NOT_ALLOWED = "order-consignee-not-allowed";

    private LaneMatching() {
    }

    /** A terminal and the LTL rating zone it belongs to, when any. */
    public record Site(UUID terminalId, String zone) {
    }

    /**
     * One leg of an order: where freight is delivered and who receives it.
     * The consignee may be unstated.
     */
    public record Leg(Site destination, UUID consigneeId) {
    }

    /**
     * What is known of an order when a lane is wanted.
     *
     * @param origin      the order's origin terminal
     * @param freightMode the order's mode, or null
     * @param loadType    the order's load type, or null
     * @param legs        the destinations of the order's lines; empty before
     *                    any line exists, when only the header can be judged
     */
    public record Order(Site origin, String freightMode, String loadType, List<Leg> legs) {

        public Order {
            legs = legs == null ? List.of() : List.copyOf(legs);
        }
    }

    /** The lane that serves the order, or why none does. */
    public record Outcome(ContractLane lane, String refusal, String detail) {

        public boolean matched() {
            return lane != null;
        }
    }

    /**
     * The first lane (by code) that serves the order in every respect; when
     * none does, the strongest refusal: a consignee the nearest lane does not
     * list, else that the order lies outside every lane.
     */
    public static Outcome match(List<ContractLane> lanes, Order order) {
        List<ContractLane> byCode = lanes.stream().sorted(Comparator.comparing(ContractLane::laneCode)).toList();
        ContractLane nearest = null;
        for (ContractLane lane : byCode) {
            if (!servesHeader(lane, order) || !servesDestinations(lane, order)) {
                continue;
            }
            if (consigneesAllowed(lane, order)) {
                return new Outcome(lane, null, null);
            }
            if (nearest == null) {
                nearest = lane;
            }
        }
        if (nearest != null) {
            return new Outcome(null, CONSIGNEE_NOT_ALLOWED, "A consignee of the order is not one lane "
                    + nearest.laneCode() + " allows to receive");
        }
        return new Outcome(null, OUTSIDE_LANES, "No lane of the contract runs from the order's origin to its"
                + " destination in this freight mode and load type");
    }

    /**
     * Judges the one lane the caller chose, throwing the refusal when the
     * order does not fit it.
     *
     * @throws BusinessRuleViolationException with the refusal's code
     */
    public static void require(ContractLane lane, Order order) {
        if (!lane.origin().covers(order.origin().terminalId(), order.origin().zone())) {
            throw new BusinessRuleViolationException(OUTSIDE_LANES,
                    "Lane " + lane.laneCode() + " does not start at the order's origin");
        }
        if (lane.freightMode() != null && order.freightMode() != null
                && !lane.freightMode().equals(order.freightMode())) {
            throw new BusinessRuleViolationException(OUTSIDE_LANES,
                    "Lane " + lane.laneCode() + " carries " + lane.freightMode() + " freight, not "
                            + order.freightMode());
        }
        if (lane.loadType() != null && order.loadType() != null && !lane.loadType().equals(order.loadType())) {
            throw new BusinessRuleViolationException(LOAD_TYPE_NOT_ALLOWED,
                    "Lane " + lane.laneCode() + " carries load type " + lane.loadType() + ", not " + order.loadType());
        }
        if (!servesDestinations(lane, order)) {
            throw new BusinessRuleViolationException(OUTSIDE_LANES,
                    "Lane " + lane.laneCode() + " does not run to every destination on the order");
        }
        if (!consigneesAllowed(lane, order)) {
            throw new BusinessRuleViolationException(CONSIGNEE_NOT_ALLOWED,
                    "A consignee of the order is not one lane " + lane.laneCode() + " allows to receive");
        }
    }

    /**
     * Refuses a load type the contract does not cover. An order that states
     * none, and a contract that lists none, pass.
     *
     * @param allowedLoadTypes the contract's list, comma separated, or null for any
     */
    public static void requireLoadTypeAllowed(String allowedLoadTypes, String loadType) {
        String wanted = ContractLane.normaliseLoadType(loadType);
        if (wanted == null || allowedLoadTypes == null || allowedLoadTypes.isBlank()) {
            return;
        }
        boolean allowed = List.of(allowedLoadTypes.toUpperCase(Locale.ROOT).split(",")).stream()
                .map(String::strip).anyMatch(wanted::equals);
        if (!allowed) {
            throw new BusinessRuleViolationException(LOAD_TYPE_NOT_ALLOWED,
                    "Load type " + wanted + " is not one the contract covers (" + allowedLoadTypes + ")");
        }
    }

    private static boolean servesHeader(ContractLane lane, Order order) {
        return lane.origin().covers(order.origin().terminalId(), order.origin().zone())
                && (lane.freightMode() == null || order.freightMode() == null
                || lane.freightMode().equals(order.freightMode()))
                && (lane.loadType() == null || order.loadType() == null || lane.loadType().equals(order.loadType()));
    }

    /** Every leg's destination is the lane's. With no legs there is nothing to contradict. */
    private static boolean servesDestinations(ContractLane lane, Order order) {
        return order.legs().stream().allMatch(leg -> lane.destination()
                .covers(leg.destination().terminalId(), leg.destination().zone()));
    }

    private static boolean consigneesAllowed(ContractLane lane, Order order) {
        return lane.consignees().isEmpty() || order.legs().stream()
                .allMatch(leg -> leg.consigneeId() != null && lane.consignees().contains(leg.consigneeId()));
    }

    /** The lane of the list with this id. */
    public static Optional<ContractLane> byId(List<ContractLane> lanes, UUID id) {
        return lanes.stream().filter(lane -> lane.id().equals(id)).findFirst();
    }
}
