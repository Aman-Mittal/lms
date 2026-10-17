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
package com.lms.milkrun.query;

import java.util.UUID;

/** One planned pickup; the window is in minutes after the circuit departs. */
public record MilkRunRouteStopView(
        int sequence,
        UUID terminalId,
        String terminalCode,
        String terminalName,
        int windowOpenMin,
        int windowCloseMin,
        int plannedDwellMin) {
}
