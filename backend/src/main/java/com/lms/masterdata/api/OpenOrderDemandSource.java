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
package com.lms.masterdata.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The open orders a customer has committed to, by weight.
 *
 * <p>Implemented by the order module. Weight rather than money because an
 * order carries no price: what it will cost the customer is the client
 * contract's to say ({@link ReceivablesSource#transportTariff}).
 */
public interface OpenOrderDemandSource {

    /**
     * Every VALIDATED, PARTIALLY_PLANNED or FULLY_PLANNED order of the
     * customer, plus {@code alsoCount} whatever its status unless it is
     * cancelled or fulfilled.
     *
     * @param alsoCount an order under check, possibly still a draft; null for none
     */
    List<OpenOrderDemand> openDemandOf(UUID customerPartnerId, UUID alsoCount);

    /** One order's chargeable weight across its lines. */
    record OpenOrderDemand(UUID orderId, BigDecimal chargeableWeightKg) {
    }
}
