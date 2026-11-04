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
package com.lms.order.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Implemented by planning, so that closing an order can ask what has become of
 * the freight cut from each of its lines.
 *
 * <p>The same direction as {@link OrderCancellationHook}: order publishes the
 * question and planning answers it, so order never depends on planning. A
 * synchronous answer rather than state order keeps for itself, because the
 * close is refused or allowed on what the freight is doing at that moment.
 */
public interface OrderFreightProbe {

    /**
     * Per order line, the consignments that carry some of it. A line no
     * consignment carries is absent. A split consignment is counted through its
     * parts, and a cancelled one not at all.
     */
    Map<UUID, LineFreight> freightByLine(UUID orderId);

    /**
     * @param live      consignments carrying the line that are neither cancelled nor split
     * @param delivered of those, the ones delivered
     * @param waiting   of those, the ones that have not left: not on a load, or on
     *                  one that has not been dispatched
     * @param liveKg      dead weight of the live consignments
     * @param deliveredKg dead weight of the delivered ones
     */
    record LineFreight(int live, int delivered, int waiting, BigDecimal liveKg, BigDecimal deliveredKg) {

        private static final BigDecimal HUNDRED = new BigDecimal("100");

        /** Everything cut for the line has been delivered, and something was. */
        public boolean fulfilled() {
            return live > 0 && delivered == live;
        }

        /**
         * Something has been delivered, and what has not is no more than
         * {@code percent} of the line, measured by the weight of the freight
         * carrying it. A split consignment's parts each carry a share of the
         * whole, so this is how much of the line's quantity is still on the
         * road. Compared without division, so nothing rounds.
         */
        public boolean fulfilledWithin(BigDecimal percent) {
            if (fulfilled()) {
                return true;
            }
            if (delivered == 0 || liveKg.signum() <= 0) {
                return false;
            }
            return liveKg.subtract(deliveredKg).multiply(HUNDRED).compareTo(liveKg.multiply(percent)) <= 0;
        }
    }
}
