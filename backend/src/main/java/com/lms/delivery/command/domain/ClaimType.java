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
package com.lms.delivery.command.domain;

/** What a claim is about. */
public enum ClaimType {
    DAMAGE(true),
    SHORTAGE(true),
    LOSS(false),
    DELAY(false);

    private final boolean needsProofOfDelivery;

    ClaimType(boolean needsProofOfDelivery) {
        this.needsProofOfDelivery = needsProofOfDelivery;
    }

    /**
     * Damage and shortage are found on arrival, so they cannot be claimed for
     * a consignment that has not been proved delivered. A loss or a delay can.
     */
    public boolean needsProofOfDelivery() {
        return needsProofOfDelivery;
    }
}
