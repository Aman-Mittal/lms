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
package com.lms.multimodal.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Intent to publish a version of a rail carrier's tariff between two
 * railheads.
 *
 * @param currency          optional; INR when not given
 * @param perWagonRate      optional, but at least one of the two rates is not
 * @param perContainerRate  optional, but at least one of the two rates is not
 * @param minimumCharge     optional; zero when not given
 */
public record PublishRailTariffCommand(String code, UUID carrierPartnerId,
                                       UUID originTerminalId, UUID destinationTerminalId,
                                       String currency, BigDecimal perWagonRate,
                                       BigDecimal perContainerRate, BigDecimal minimumCharge,
                                       LocalDate effectiveFrom) {
}
