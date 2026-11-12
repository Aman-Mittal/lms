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

package com.lms.finance.command.domain;

/**
 * Who a catalogue charge may be raised against: the customer (a receivable),
 * the vendor (a payable), or either.
 */
public enum ChargeDirection {

    RECEIVABLE,
    PAYABLE,
    BOTH;

    /** Whether a charge of the given side may be raised from an entry with this direction. */
    public boolean allows(Side side) {
        return this == BOTH || this.name().equals(side.name());
    }

    /** The side of the money a raised charge sits on. */
    public enum Side {
        RECEIVABLE,
        PAYABLE
    }
}
