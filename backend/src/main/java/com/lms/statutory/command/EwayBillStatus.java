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
package com.lms.statutory.command;

/** Where an e-way bill stands. */
public enum EwayBillStatus {
    /** Valid, as generated. */
    ACTIVE,
    /** Valid, its validity extended at least once. */
    EXTENDED,
    /** Cancelled within the window; final. */
    CANCELLED,
    /** Past its validity; final. */
    EXPIRED;

    /** Whether the bill still counts as the consignment's bill. */
    public boolean isLive() {
        return this == ACTIVE || this == EXTENDED;
    }
}
