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
import java.util.UUID;

import com.lms.order.api.MaterialClass;

/**
 * One line of an order as a caller wants it, before it has an identity.
 *
 * <p>The input of a line replacement: the new set is described in these and the
 * order turns each into a stored line, so a caller never supplies an id, a
 * tenant or a planning flag.
 */
public record OrderLineSpec(
        int lineNo,
        String materialCode,
        String materialDescription,
        MaterialClass materialClass,
        String hazmatUnCode,
        BigDecimal quantity,
        String uom,
        BigDecimal deadWeightKg,
        BigDecimal lengthM,
        BigDecimal widthM,
        BigDecimal heightM,
        BigDecimal volumetricDivisor,
        UUID consigneePartnerId,
        UUID destinationTerminalId) {
}
