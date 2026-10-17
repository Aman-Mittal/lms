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
package com.lms.sourcing.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A lane's routing guide with its vendors in rank order. */
public record RoutingGuideView(
        UUID id,
        String originTerminalCode,
        String destinationTerminalCode,
        String vehicleType,
        String strategy,
        boolean active,
        List<Entry> entries) {

    public record Entry(int rankNo, UUID vendorPartnerId, String vendorName,
                        int responseSlaMinutes, BigDecimal agreedRate) {
    }
}
