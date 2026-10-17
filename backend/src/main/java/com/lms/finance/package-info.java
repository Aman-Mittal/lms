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
/**
 * Rating, accessorials and vendor settlement (vision document 3.9).
 *
 * <p>The one context where being wrong costs money on the day it happens. Its
 * whole shape follows from a single rule in 3.9.1: a rate that has priced a
 * movement is never edited. Rate cards are versions, a trip is priced against
 * the version in force on the day it was <em>dispatched</em>, and the version
 * used is recorded on the bill -- so a dispute raised in March can be answered
 * with the card that applied in January rather than with today's numbers.
 *
 * <p>Reads loads through {@code planning::api} for the lane, the chargeable
 * weight and the number of drops, and listens to {@code execution::events} for
 * the moment a trip finishes. The asymmetry is deliberate: pricing must happen
 * after the movement, so it can be an event; the lane a load ran on is a fact
 * finance has to ask for, because a bill cannot wait for it.
 *
 * <p>Listens to {@code controltower::events} for the 3.8 rule that a critical
 * exception suspends settlement. The control tower decides what is critical;
 * finance decides what "suspended" means for a bill, which is that nothing
 * may approve it for payment until the ticket is closed.
 *
 * <p>Uses {@code identity::access} to describe a freight bill to the access
 * decision point -- its amount, vendor and owning unit -- and to confine a
 * vendor-portal user's reads to that vendor's own bills. Identity decides;
 * finance only states the facts about its own records.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Rating & Settlement",
        allowedDependencies = {"planning::api", "execution::events", "controltower::events",
                "identity::access"})
package com.lms.finance;
