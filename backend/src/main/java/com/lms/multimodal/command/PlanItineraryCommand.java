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

import java.util.List;

import com.lms.multimodal.command.domain.ItineraryLeg;

/**
 * Intent to move a shipment over ordered legs.
 *
 * @param currency optional; INR when not given. Every leg's cost is in it.
 * @param legs     in travel order; the first is leg 1
 */
public record PlanItineraryCommand(String shipmentRef, String currency,
                                   List<ItineraryLeg.Plan> legs) {
}
