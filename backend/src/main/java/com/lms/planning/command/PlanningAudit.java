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
package com.lms.planning.command;

import java.util.List;
import java.util.UUID;

import com.lms.planning.command.domain.Consignment;
import com.lms.planning.command.domain.LoadUnit;
import com.lms.shared.audit.TransitionAudit;
import com.lms.shared.config.Json;

/**
 * What planning writes to the audit trail: the resource types, and the
 * snapshots, kept in one place so a consignment or a load reads the same
 * whichever transition recorded it.
 *
 * <p>Snapshots are built from the aggregate the caller already holds. Nothing
 * here reads the database, which is what keeps an audited transition one
 * insert dearer and no more.
 */
final class PlanningAudit {

    static final String CONSIGNMENT = "Consignment";
    static final String LOAD = "Load";

    private PlanningAudit() {
    }

    static Json consignment(Consignment consignment) {
        return Json.object("status", consignment.status().name(),
                "trackingRef", consignment.trackingRef(),
                "orderId", id(consignment.orderId()));
    }

    /** A consignment on a load, naming the load. */
    static Json consignmentOn(Consignment consignment, UUID loadId) {
        return Json.object("status", consignment.status().name(),
                "trackingRef", consignment.trackingRef(),
                "orderId", id(consignment.orderId()),
                "loadId", id(loadId));
    }

    static Json load(LoadUnit load) {
        return Json.object("status", load.status().name(),
                "loadNo", load.loadNo(),
                "awardedVendorPartnerId", id(load.awardedVendorPartnerId()));
    }

    /** One entry per consignment, each from its own earlier state to its later one. */
    static TransitionAudit.Entry entry(Consignment before, Consignment after) {
        return new TransitionAudit.Entry(after.id(), consignment(before), consignment(after));
    }

    static TransitionAudit.Entry entry(LoadUnit before, LoadUnit after) {
        return new TransitionAudit.Entry(after.id(), load(before), load(after));
    }

    static List<TransitionAudit.Entry> entries(List<Consignment> before, List<Consignment> after) {
        return java.util.stream.IntStream.range(0, after.size())
                .mapToObj(i -> entry(before.get(i), after.get(i))).toList();
    }

    private static String id(UUID id) {
        return id == null ? null : id.toString();
    }
}
