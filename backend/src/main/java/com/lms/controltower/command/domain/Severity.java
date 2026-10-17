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
package com.lms.controltower.command.domain;

/** Vision document 3.8. */
public enum Severity {
    P1,
    P2,
    P3;

    /**
     * Whether a ticket of this severity holds up money.
     *
     * <p>3.8: critical exceptions -- P1 and P2 -- suspend invoicing and
     * settlement until a manager closes the ticket. Paying a vendor for a trip
     * whose freight was stolen, before anybody has established what happened,
     * is the cost leakage the whole platform exists to prevent.
     */
    public boolean suspendsSettlement() {
        return this == P1 || this == P2;
    }
}
