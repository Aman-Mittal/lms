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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.planning.api.ConsignmentLookupPort;
import com.lms.planning.api.ConsignmentLookupPort.ConsignmentRef;
import com.lms.statutory.api.EwayBillDispatchGate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dispatch gate: when the unit requires a bill at dispatch
 * ({@code statutory.ewayBill.requiredAtDispatch}), every consignment still to
 * move on the load needs a live bill (active, or extended) that is valid after
 * the moment of departure. Joins the dispatch transaction and reads the bills
 * of the whole load in one statement. With the setting off, which is the
 * default, it reads nothing from the bill table at all.
 */
@Component
class EwayBillGate implements EwayBillDispatchGate {

    private final EwayBillSettings settings;
    private final ConsignmentLookupPort consignments;
    private final EwayBillStore store;

    EwayBillGate(EwayBillSettings settings, ConsignmentLookupPort consignments, EwayBillStore store) {
        this.settings = settings;
        this.consignments = consignments;
        this.store = store;
    }

    @Override
    @Transactional
    public List<String> blockers(UUID orgUnitId, UUID loadId, Instant at) {
        if (!settings.forUnit(orgUnitId).requiredAtDispatch()) {
            return List.of();
        }
        List<ConsignmentRef> onLoad = consignments.onLoad(loadId).stream().filter(ConsignmentRef::isOpen).toList();
        Map<UUID, EwayBillStore.Bill> live = store.liveOf(onLoad.stream().map(ConsignmentRef::id).toList());
        List<String> blockers = new ArrayList<>();
        for (ConsignmentRef consignment : onLoad) {
            EwayBillStore.Bill bill = live.get(consignment.id());
            if (bill == null) {
                blockers.add("consignment " + consignment.trackingRef() + " has no active e-way bill");
            } else if (!EwayBillRules.usableAt(bill.status(), bill.validUntil(), at)) {
                blockers.add("e-way bill " + bill.billNo() + " of consignment " + consignment.trackingRef()
                        + " is not valid at departure (valid until " + bill.validUntil() + ")");
            }
        }
        return blockers;
    }
}
